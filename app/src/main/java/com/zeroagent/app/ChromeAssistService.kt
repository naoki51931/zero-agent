package com.zeroagent.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ChromeAssistService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var message: TextView? = null
    private var approve: Button? = null
    private var nodes = emptyList<AccessibilityNodeInfo>()
    private var proposedIndex: Int? = null
    private val protectedWords = Regex("login|sign.in|password|checkout|purchase|payment|delete|send|publish|submit|consent|captcha|ログイン|認証|購入|支払|削除|送信|投稿|同意|パスワード", RegexOption.IGNORE_CASE)

    override fun onServiceConnected() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
            setBackgroundColor(0xFFF2ECFA.toInt())
        }
        val status = TextView(this).apply { text = "ZERO AGENT: Chromeを開いてください" }
        message = status
        val suggest = Button(this).apply { text = "AIに次のクリックを提案させる"; setOnClickListener { analyze() } }
        val accept = Button(this).apply { text = "承認してクリック"; isEnabled = false; setOnClickListener { clickApproved() } }
        approve = accept
        val toggle = Button(this).apply {
            text = "小さくする"
            setOnClickListener {
                val visible = suggest.visibility == android.view.View.VISIBLE
                suggest.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
                accept.visibility = suggest.visibility
                status.visibility = suggest.visibility
                text = if (visible) "展開" else "小さくする"
            }
        }
        layout.addView(status); layout.addView(suggest); layout.addView(accept); layout.addView(toggle)
        manager.addView(layout, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM })
        panel = layout
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.android.chrome") {
            proposedIndex = null
            approve?.isEnabled = false
        }
    }

    private fun analyze() {
        proposedIndex = null
        approve?.isEnabled = false
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != "com.android.chrome") {
            message?.text = "Chromeを開いてから実行してください"
            return
        }
        nodes.forEach { it.recycle() }
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 18 || found.size >= 60) return
            val name = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
            if (node.isVisibleToUser && node.isClickable && !node.isPassword &&
                name.isNotBlank() && !protectedWords.containsMatchIn(name)) {
                found.add(AccessibilityNodeInfo.obtain(node))
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
                child.recycle()
            }
        }
        visit(root, 0)
        root.recycle()
        nodes = found
        if (found.isEmpty()) { message?.text = "読み取れるクリック対象がありません"; return }
        val inventory = JSONArray()
        found.forEachIndexed { i, node ->
            inventory.put(JSONObject().put("id", i).put("text",
                listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").take(120)))
        }
        val settings = LocalSettings(this)
        val key = settings.get("apiKey")
        val model = settings.get("model")
        if (key.isBlank() || model.isBlank()) { message?.text = "アプリでAPIキーとモデルを設定してください"; return }
        message?.text = "画面の文字とボタンをAIで確認中…"
        scope.launch {
            try {
                val goal = settings.get("objective")
                val action = BrowserAgent.suggest(key, model,
                    "目標: $goal。Chrome画面の候補から、次に押す安全なリンクを一つ選んでください。",
                    inventory.toString())
                val index = Regex("""\[data-za-id="([0-9]+)"\]""")
                    .matchEntire(action.selector)?.groupValues?.get(1)?.toIntOrNull()
                if (action.type == "click" && index != null && index in nodes.indices) {
                    proposedIndex = index
                    message?.text = "AI提案: ${action.explanation}\n対象: ${nodes[index].text ?: nodes[index].contentDescription}"
                    approve?.isEnabled = true
                } else message?.text = "安全にクリックできる候補はありません"
            } catch (e: Exception) { message?.text = "解析エラー: ${e.message?.take(120)}" }
        }
    }

    private fun clickApproved() {
        val index = proposedIndex ?: return
        proposedIndex = null
        approve?.isEnabled = false
        val node = nodes.getOrNull(index) ?: return
        val root = rootInActiveWindow
        val name = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
        if (root?.packageName?.toString() == "com.android.chrome" && node.refresh() &&
            node.isVisibleToUser && node.isClickable && !node.isPassword &&
            !protectedWords.containsMatchIn(name)) {
            message?.text = if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                "クリックしました。次の操作は再提案してください" else "クリックできませんでした"
        } else message?.text = "画面が変わりました。再解析してください"
        root?.recycle()
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        panel?.let { wm?.removeView(it) }
        nodes.forEach { it.recycle() }
        scope.cancel()
        super.onDestroy()
    }
}