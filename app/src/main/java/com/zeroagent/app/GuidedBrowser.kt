package com.zeroagent.app

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp

/** In-app Chromium WebView with manual, step-by-step guidance. */
@Composable
fun GuidedBrowser(steps: List<String>, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf("https://www.google.com/") }
    var input by remember { mutableStateOf(url) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose { webView?.destroy() }
    }
    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else onClose() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = onClose) { Text("戻る") }
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("URL") })
            Button(onClick = {
                val target = input.trim()
                if (target.startsWith("https://") || target.startsWith("http://")) webView?.loadUrl(target)
            }) { Text("開く") }
        }
        Box(Modifier.weight(1f)) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            if (pageUrl != null) { url = pageUrl; input = pageUrl }
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            }, modifier = Modifier.fillMaxSize())
        }
        Card(Modifier.fillMaxWidth().padding(8.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ZERO AGENT 操作ガイド ${index + 1}/${steps.size}", style = MaterialTheme.typography.titleMedium)
                Text(steps.getOrElse(index) { "手順はありません" })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = index > 0, onClick = { index-- }) { Text("前へ") }
                    Button(onClick = { if (index < steps.lastIndex) index++ else onClose() }) {
                        Text(if (index < steps.lastIndex) "次へ" else "完了")
                    }
                }
            }
        }
    }
}
