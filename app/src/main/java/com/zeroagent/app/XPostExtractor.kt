package com.zeroagent.app

import org.json.JSONArray

/** Parses only posts whose visible like count can be read unambiguously. */
object XPostExtractor {
    fun parseLikes(raw: String): Long? {
        val normalized = raw.trim().replace(",", "").replace("，", "").replace(" ", "")
        val match = Regex("^([0-9]+(?:\\.[0-9]+)?)([KMB万億]?)$", RegexOption.IGNORE_CASE)
            .matchEntire(normalized) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val factor = when (match.groupValues[2].uppercase()) {
            "K" -> 1_000.0
            "M" -> 1_000_000.0
            "B" -> 1_000_000_000.0
            "万" -> 10_000.0
            "億" -> 100_000_000.0
            else -> 1.0
        }
        val count = value * factor
        return if (count.isFinite() && count >= 0 && count <= Long.MAX_VALUE.toDouble()) count.toLong() else null
    }

    fun parseJson(json: String): List<PopularPostMemory.Post> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { index ->
            val item = arr.optJSONObject(index) ?: return@mapNotNull null
            val likes = parseLikes(item.optString("likes")) ?: return@mapNotNull null
            PopularPostMemory.Post(
                id = item.optString("id"),
                url = item.optString("url"),
                author = item.optString("author"),
                text = item.optString("text"),
                likes = likes
            )
        }
    } catch (_: Exception) { emptyList() }

    /** Read-only extraction from rendered X post cards, no requests or interaction. */
    val script = """
(function() {
 if (!['x.com','www.x.com','twitter.com','www.twitter.com'].includes(location.hostname)) return '[]';
 const visible = el => { const r=el.getBoundingClientRect(); const s=getComputedStyle(el); return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'; };
 const posts=[];
 for (const card of Array.from(document.querySelectorAll('article[data-testid="tweet"]')).slice(0,80)) {
   if (!visible(card)) continue;
   const link=Array.from(card.querySelectorAll('a[href*="/status/"]')).find(a => /\/status\/\d+/.test(a.getAttribute('href')||''));
   if (!link) continue;
   const url=new URL(link.getAttribute('href'), location.origin);
   const match=url.pathname.match(/^\/([^/]+)\/status\/(\d+)/);
   if (!match) continue;
   const like=card.querySelector('[data-testid="like"]');
   const unlike=card.querySelector('[data-testid="unlike"]');
   const button=like||unlike;
   if (!button || !visible(button)) continue;
   const label=(button.getAttribute('aria-label')||'').trim();
   const count=label.match(/(?:^|\s)([\d,.]+\s*[KMB万億]?)(?=\s*(?:Likes?|いいね))/i) ||
       label.match(/(?:Likes?|いいね)\s*[:：]?\s*([\d,.]+\s*[KMB万億]?)/i);
   if (!count) continue;
   const text=Array.from(card.querySelectorAll('[data-testid="tweetText"]')).map(e=>e.innerText).join(' ').slice(0,2000);
   posts.push({id:match[2],url:url.origin+url.pathname,author:match[1],text,likes:count[1].replace(/\s+/g,'')});
 }
 return JSON.stringify(posts);
})()
""".trimIndent()
}
