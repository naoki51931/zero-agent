#!/usr/bin/env python3
"""Minimal authenticated bridge to the official SeaArt CLI.
Run on a trusted private machine, behind HTTPS with a private token.
No browser cookies, passwords or shell commands are accepted from the Android client.
"""
import json
import os
import re
import subprocess
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

TOKEN = os.environ.get("ZERO_AGENT_BRIDGE_TOKEN", "")
MODEL = os.environ.get("SEAART_MODEL_ID", "")
VERSION = os.environ.get("SEAART_MODEL_VER_ID", "")
HOST = os.environ.get("BRIDGE_HOST", "127.0.0.1")
PORT = int(os.environ.get("BRIDGE_PORT", "8788"))
LOCK = threading.Lock()


def generate(prompt):
    if not MODEL or not VERSION:
        raise RuntimeError("SEAART_MODEL_ID and SEAART_MODEL_VER_ID required")
    command = ["seaart", "text2image", "--model-id", MODEL,
               "--model-ver-id", VERSION, "--prompt", prompt,
               "--resolution", "1024x1024", "--generate-count", "1"]
    with LOCK:
        result = subprocess.run(command, capture_output=True, text=True, timeout=240)
    if result.returncode:
        raise RuntimeError("SeaArt CLI failed: " + result.stderr[-400:])
    output = result.stdout
    # CLI output formats may vary by release. Require an explicit HTTPS image URL.
    urls = re.findall(r'https://[^\s"<>]+?\.(?:png|jpe?g|webp)(?:\?[^\s"<>]*)?', output)
    if not urls:
        try:
            data = json.loads(output)
            urls = [str(x) for x in data.get("image_urls", [])]
        except (ValueError, TypeError, AttributeError):
            pass
    urls = [u for u in urls if urlparse(u).scheme == "https"]
    if not urls:
        raise RuntimeError("SeaArt completed but no downloadable image URL was returned; inspect CLI output format")
    return urls[0]


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/v1/generate":
            self.send_error(404)
            return
        import hmac
        if not TOKEN or not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + TOKEN):
            self.send_error(401)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 1 <= length <= 8192:
                raise ValueError("Invalid body size")
            body = json.loads(self.rfile.read(length))
            prompt = body.get("prompt", "")
            if not isinstance(prompt, str) or not 20 <= len(prompt) <= 3500:
                raise ValueError("Invalid prompt")
            image_url = generate(prompt)
            response = {"image_url": image_url}
            code = 200
        except Exception as exc:
            response = {"error": str(exc)[:350]}
            code = 422
        encoded = json.dumps(response).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


if __name__ == "__main__":
    if len(TOKEN) < 24:
        raise SystemExit("Set a random ZERO_AGENT_BRIDGE_TOKEN (24+ characters)")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
