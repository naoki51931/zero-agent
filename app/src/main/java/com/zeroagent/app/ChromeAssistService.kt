package com.zeroagent.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.text.InputType
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
    private var pending = false
    private var nodes = emptyList<AccessibilityNodeInfo>()
    private var proposedIndex: Int? = null
    private var proposedAction: BrowserAction? = null
    private var objectiveInput: EditText? = null
    private var manualInput: EditText? = null
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
        val objective = EditText(this).apply {
            hint = "Chromeで何をしたい？"
            setSingleLine(true)
            setText(LocalSettings(this@ChromeAssistService).get("objective"))
        }
        objectiveInput = objective
        val suggest = Button(this).apply { text = "AIに次の操作を提案させる"; setOnClickListener { analyze() } }
        val accept = Button(this).apply { text = "提案を承認して実行"; isEnabled = false; setOnClickListener { clickApproved() } }
        val manual = EditText(this).apply { hint = "選択した入力欄に入れる文字"; setSingleLine(true) }
        manualInput = manual
        val fill = Button(this).apply { text = "選択中の入力欄に文字を入力"; setOnClickListener { fillFocused() } }
        val back = Button(this).apply { text = "戻る"; setOnClickListener { proposedIndex = null; approve?.isEnabled = false; performGlobalAction(GLOBAL_ACTION_BACK) } }
        val up = Button(this).apply { text = "上へスクロール"; setOnClickListener { scrollChrome(false) } }
        val stop = Button(this).apply { text = "提案を破棄"; setOnClickListener { proposedIndex = null; proposedAction = null; approve?.isEnabled = false; message?.text = "停止しました" } }
        approve = accept
        val scroll = Button(this).apply { text = "Chromeを下へスクロール"; setOnClickListener { scrollChrome() } }
        val toggle = Button(this).apply {
            text = "小さくする"
            setOnClickListener {
                val visible = suggest.visibility == android.view.View.VISIBLE
                suggest.visibility = if (visible) android.view.View.GONE else android.view.View.VISIBLE
                accept.visibility = suggest.visibility
                scroll.visibility = suggest.visibility
                objective.visibility = suggest.visibility
                manual.visibility = suggest.visibility
                fill.visibility = suggest.visibility
                back.visibility = suggest.visibility
                up.visibility = suggest.visibility
                stop.visibility = suggest.visibility
                status.visibility = suggest.visibility
                text = if (visible) "展開" else "小さくする"
            }
        }
        layout.addView(status); layout.addView(objective); layout.addView(suggest); layout.addView(accept); layout.addView(manual); layout.addView(fill); layout.addView(scroll); layout.addView(up); layout.addView(back); layout.addView(stop); layout.addView(toggle)
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
        if (pending) return
        proposedAction = null
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
            if (node.isVisibleToUser && (node.isClickable || node.isEditable) && !node.isPassword &&
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
        if (found.isEmpty()) { message?.text = "読み取れる操作対象がありません"; return }
        val inventory = JSONArray()
        found.forEachIndexed { i, node ->
            inventory.put(JSONObject().put("id", i).put("editable", node.isEditable).put("clickable", node.isClickable).put("text",
                listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").take(120)))
        }
        val settings = LocalSettings(this)
        val key = settings.get("apiKey")
        val model = settings.get("model")
        if (key.isBlank() || model.isBlank()) { message?.text = "アプリでAPIキーとモデルを設定してください"; return }
        message?.text = "画面の文字とボタンをAIで確認中…"
        pending = true
        scope.launch {
            try {
                val goal = objectiveInput?.text?.toString()?.take(500).orEmpty().ifBlank { settings.get("objective") }
                val action = BrowserAgent.suggest(key, model,
                    "目標: $goal。Chrome画面の候補から安全なクリックまたは入力欄への入力を一つ選んでください。入力が必要な場合は目標に明示された一般的な検索語だけを使ってください。",
                    inventory.toString())
                val index = Regex("""\[data-za-id="([0-9]+)"\]""")
                    .matchEntire(action.selector)?.groupValues?.get(1)?.toIntOrNull()
                if (action.type in listOf("click", "fill") && index != null && index in nodes.indices && (action.type != "fill" || (nodes[index].isEditable && action.value.length <= 300))) {
                    proposedAction = action
                    proposedIndex = index
                    message?.text = "AI提案: ${action.explanation}\n操作: ${action.type} / 対象: ${nodes[index].text ?: nodes[index].contentDescription}\n入力: ${if (action.type == "fill") action.value else "なし"}"
                    approve?.isEnabled = true
                } else message?.text = "安全にクリックできる候補はありません"
            } catch (e: Exception) { message?.text = "解析エラー: ${e.message?.take(120)}" }
            finally { pending = false }
        }
    }

    private fun scrollChrome(forward: Boolean = true) {
        proposedIndex = null
        approve?.isEnabled = false
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != "com.android.chrome") {
            message?.text = "Chromeを開いてください"
            return
        }
        fun scroll(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > 18) return false
            if (node.isVisibleToUser && node.isScrollable &&
                node.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val done = scroll(child, depth + 1)
                child.recycle()
                if (done) return true
            }
            return false
        }
        message?.text = if (scroll(root, 0)) "スクロールしました。再解析してください" else "スクロール対象がありません"
        root.recycle()
    }

    private fun fillFocused() {
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != "com.android.chrome") {
            message?.text = "Chromeを開いてください"
            return
        }
        val value = manualInput?.text?.toString().orEmpty()
        if (value.isBlank() || value.length > 300) { message?.text = "300文字以内で入力してください"; return }
        fun find(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 18) return null
            if (node.isFocused && node.isEditable && !node.isPassword && node.isVisibleToUser) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val result = find(child, depth + 1)
                if (result != null) return result
                child.recycle()
            }
            return null
        }
        val target = find(root, 0)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        message?.text = if (target?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true) "文字を入力しました" else "Chromeで入力欄を選択してください"
        root.recycle()
    }

    private fun clickApproved() {
        val index = proposedIndex ?: return
        val action = proposedAction ?: return
        proposedIndex = null
        proposedAction = null
        approve?.isEnabled = false
        val node = nodes.getOrNull(index) ?: return
        val root = rootInActiveWindow
        val name = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
        if (root?.packageName?.toString() == "com.android.chrome" && node.refresh() &&
            node.isVisibleToUser && (if (action.type == "fill") node.isEditable else node.isClickable) && !node.isPassword &&
            !protectedWords.containsMatchIn(name)) {
            val success = if (action.type == "fill" && action.value.length <= 300) {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.value) }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else if (action.type == "click") node.performAction(AccessibilityNodeInfo.ACTION_CLICK) else false
            message?.text = if (success) "操作しました。次の操作は再提案してください" else "操作できませんでした"
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