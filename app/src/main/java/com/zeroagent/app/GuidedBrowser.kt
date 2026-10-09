package com.zeroagent.app

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

private val inventoryScript = """
(function(){
 const nodes=Array.from(document.querySelectorAll('a,button,input,textarea,[role="button"]')).slice(0,120);
 return JSON.stringify(nodes.map((e,i)=>{
   e.setAttribute('data-za-id',String(i));
   return {id:i,tag:e.tagName,type:e.type||'',text:(e.innerText||e.getAttribute('aria-label')||'').slice(0,90),
      placeholder:(e.placeholder||'').slice(0,80),href:(e.getAttribute('href')||'').slice(0,100)};
 }));
})()
""".trimIndent()

/** Embedded WebView: AI proposes one action; user approves before local execution. */
@Composable
fun GuidedBrowser(steps: List<String>, apiKey: String, model: String, objective: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun openInBrowser(url: String) {
        val safeUrl = url.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl)).addCategory(Intent.CATEGORY_BROWSABLE))
    }
    var index by remember { mutableIntStateOf(0) }
    val searchTerms = objective.trim().ifBlank { steps.firstOrNull().orEmpty() }.take(140)
    val startUrl = remember(searchTerms) { "https://www.google.com/search?q=" + URLEncoder.encode(searchTerms, "UTF-8") }
    var input by remember(startUrl) { mutableStateOf(startUrl) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var proposal by remember { mutableStateOf<BrowserAction?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var guideVisible by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }
    BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else onClose() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onClose, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("戻る") }
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("URL") })
            TextButton(onClick = {
                val target = input.trim()
                if (target.startsWith("https://") || target.startsWith("http://")) {
                    proposal = null
                    webView?.loadUrl(target)
                }
            }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("開く") }
        }
        TextButton(onClick = { openInBrowser(webView?.url ?: input) }, modifier = Modifier.fillMaxWidth()) {
            Text("このページをChromeなどのブラウザで開く（ログイン用）")
        }
        Box(Modifier.weight(1f)) {
            AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return false
                            if (uri.scheme == "https" && uri.host == "accounts.google.com") {
                                openInBrowser(uri.toString())
                                message = "Googleログインは安全のため外部ブラウザで開きました。認証後は戻ってください。"
                                return true
                            }
                            return false
                        }
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            if (pageUrl != null) { input = pageUrl; proposal = null }
                        }
                    }
                    loadUrl(input)
                    webView = this
                }
            }, modifier = Modifier.fillMaxSize())
        }
        if (!guideVisible) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { guideVisible = true }) { Text("吹き出しを表示") }
            }
        }
        if (guideVisible) Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).heightIn(max = 260.dp)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("ZERO AGENT ${index + 1}/${steps.size}", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { guideVisible = false }) { Text("隠す") }
                }
                Text("目標: $objective", style = MaterialTheme.typography.bodySmall)
                Text(steps.getOrElse(index) { "目標に必要な情報を調べる" })
                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && apiKey.isNotBlank() && model.isNotBlank(), onClick = {
                    val web = webView ?: return@Button
                    busy = true
                    proposal = null
                    message = "ページを解析中…"
                    web.evaluateJavascript(inventoryScript) { encoded ->
                        try {
                            val inventory = org.json.JSONTokener(encoded).nextValue() as String
                            scope.launch {
                                try {
                                    val action = BrowserAgent.suggest(apiKey, model, "最終目標: $objective\\n現在の作業: ${steps.getOrElse(index) { "" }}", inventory)
                                    proposal = if (action.type == "none") null else action
                                    message = if (action.type == "none") "安全に自動操作できる対象が見つかりません" else "操作内容を確認して実行してください"
                                } catch (e: Exception) { message = e.message ?: "解析エラー" }
                                finally { busy = false }
                            }
                        } catch (e: Exception) { message = "ページを解析できません"; busy = false }
                    }
                }) { Text(if (busy) "AI解析中…" else "AIに次のクリック・入力を提案させる") }
                proposal?.let { action ->
                    Text("提案: ${action.explanation}\n操作: ${action.type} ${action.selector}" + if (action.type == "fill") "\n入力: ${action.value.take(150)}" else "")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val payload = JSONObject().put("selector", action.selector).put("value", action.value).toString()
                            val script = """
                              (function(){
                                const a=$payload;
                                const e=document.querySelector(a.selector);
                                if(!e) return '対象が見つかりません';
                                if(e.closest('form') && (e.type==='submit'||e.tagName==='BUTTON')) return 'フォーム送信は禁止です';
                                if(e.matches('input[type=password],input[type=hidden],input[type=file]')) return '保護された入力欄です';
                                const label=((e.innerText||'')+' '+(e.getAttribute('aria-label')||'')+' '+(e.name||'')).toLowerCase();
                                if(/login|sign.?in|purchase|pay|delete|submit|send|publish|投稿|送信|削除|購入|支払|ログイン|認証|captcha/.test(label)) return '保護された操作です';
                                if(a.selector!==('[data-za-id="'+e.getAttribute('data-za-id')+'"]')) return '対象が変更されました';
                                if('${action.type}'==='fill'){
                                  if(!e.matches('input:not([type=password]),textarea') || /password|card|token|otp|code/i.test(e.name||'')) return '入力禁止';
                                  e.focus();e.value=a.value;e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
                                  return '入力しました（未送信）';
                                }
                                if(e.tagName==='A' && !/^https?:/i.test(e.href)) return 'リンクは禁止です';
                                e.click();return 'クリックしました';
                              })()
                            """.trimIndent()
                            webView?.evaluateJavascript(script) { message = it }
                            proposal = null
                        }) { Text("承認して実行") }
                        OutlinedButton(onClick = { proposal = null }) { Text("キャンセル") }
                    }
                }
                if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(enabled = index > 0, onClick = { index--; proposal = null }) { Text("前へ") }
                    Button(onClick = { if (index < steps.lastIndex) { index++; proposal = null } else onClose() }) {
                        Text(if (index < steps.lastIndex) "次へ" else "完了")
                    }
                }
            }
        }
    }
}
