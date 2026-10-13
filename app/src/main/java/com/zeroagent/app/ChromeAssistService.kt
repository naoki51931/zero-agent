package com.zeroagent.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Path
import android.accessibilityservice.GestureDescription
import kotlinx.coroutines.delay
import android.widget.CheckBox
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import android.widget.EditText
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
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
    private var chromeActive = false
    private var browserTransitionToken = 0
    private var browserConsentShown = false
    private var demonstrating = false
    private var demoSteps = JSONArray()
    private var demoGoal = ""
    private var demoLastEventAt = 0L
    private var consentPanel: LinearLayout? = null
    private var autoRunning = false
    private var runToken = 0
    private var stepsRemaining = 0
    private var runGoal = ""
    private var runKey = ""
    private var runModel = ""
    private var nodes = emptyList<AccessibilityNodeInfo>()
    private var nodeLabels = emptyList<String>()
    private var candidateBounds = emptyList<android.graphics.Rect>()
    private var proposedIndex: Int? = null
    private var proposedAction: BrowserAction? = null
    private var objectiveInput: EditText? = null
    private val protectedWords = Regex("login|sign.in|password|checkout|purchase|payment|delete|send|publish|submit|consent|captcha|ログイン|認証|購入|支払|削除|送信|投稿|同意|パスワード", RegexOption.IGNORE_CASE)

    override fun onServiceConnected() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
            setBackgroundColor(0xB8F2ECFA.toInt())
        }
        val status = TextView(this).apply { text = "ZERO AGENT: Chromeを開いてください" }
        message = status
        val consent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        consent.addView(TextView(this).apply { text = "ChromeでZero Agentを使いますか？"; textSize = 16f })
        val choiceRow = LinearLayout(this)
        choiceRow.addView(Button(this).apply {
            text = "使う"
            setOnClickListener {
                consent.visibility = android.view.View.GONE
                message?.text = "Zero Agentを使用中。AIの操作提案を利用できます"
            }
        })
        choiceRow.addView(Button(this).apply {
            text = "使わない"
            setOnClickListener {
                consent.visibility = android.view.View.GONE
                panel?.visibility = android.view.View.GONE
            }
        })
        consent.addView(choiceRow)
        consentPanel = consent
        val objective = EditText(this).apply {
            hint = "Chromeで何をしたい？"
            setSingleLine(true)
            setText(LocalSettings(this@ChromeAssistService).get("objective"))
        }
        objectiveInput = objective
        val demoStart = Button(this).apply {
            text = "お手本開始"
            setOnClickListener {
                demonstrating = true
                demoSteps = JSONArray()
                demoGoal = objectiveInput?.text?.toString()?.trim()?.take(150).orEmpty()
                message?.text = "お手本記録中。Chromeを手動操作してください"
            }
        }
        val demoStop = Button(this).apply {
            text = "お手本終了・保存"
            setOnClickListener {
                demonstrating = false
                val prefs = getSharedPreferences("browser_demonstrations", MODE_PRIVATE)
                val old = try { JSONArray(prefs.getString("examples", "[]")) } catch (_: Exception) { JSONArray() }
                val saved = JSONArray()
                for (i in (old.length() - 9).coerceAtLeast(0) until old.length()) saved.put(old.getJSONObject(i))
                if (demoSteps.length() > 0) saved.put(JSONObject().put("goal", demoGoal).put("steps", demoSteps))
                prefs.edit().putString("examples", saved.toString()).apply()
                message?.text = "お手本を保存: " + demoSteps.length() + "操作"
            }
        }
        val suggest = Button(this).apply { text = "AIに操作を提案"; setOnClickListener { analyze() } }
        val accept = Button(this).apply { text = "提案を実行"; isEnabled = false; setOnClickListener { clickApproved() } }
        val auto = Button(this).apply {
            text = "AIで連続操作（最大5回）"
            setOnClickListener {
                if (autoRunning) return@setOnClickListener
                val settings = LocalSettings(this@ChromeAssistService)
                runKey = settings.get("apiKey")
                runModel = settings.get("model")
                runGoal = objectiveInput?.text?.toString()?.take(500).orEmpty().ifBlank { settings.get("objective") }
                if (runKey.isBlank() || runModel.isBlank()) { message?.text = "APIキーとモデルを設定してください"; return@setOnClickListener }
                autoRunning = true
                stepsRemaining = 5
                runToken++
                analyze(true, runToken)
            }
        }
        val stop = Button(this).apply { text = "停止"; setOnClickListener {
            autoRunning = false; runToken++; stepsRemaining = 0
            proposedIndex = null; proposedAction = null; approve?.isEnabled = false
            message?.text = "停止しました"
        } }
        approve = accept
        val dragHandle = TextView(this).apply {
            text = "☰ ここをドラッグして移動"
            textSize = 16f
            setPadding(24, 18, 24, 18)
            setBackgroundColor(0xC8D4C8ED.toInt())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP; y = (48 * resources.displayMetrics.density).toInt() }
        val controls = listOf(status, objective, demoStart, demoStop, suggest, accept, auto, stop)
        var compact = false
        fun setCompact(value: Boolean) {
            compact = value
            controls.forEach { it.visibility = if (value) android.view.View.GONE else android.view.View.VISIBLE }
            params.width = if (value) (190 * resources.displayMetrics.density).toInt() else WindowManager.LayoutParams.MATCH_PARENT
            dragHandle.text = if (value) "☰ 移動 / タップで展開" else "☰ ドラッグで縮小・移動"
            if (layout.isAttachedToWindow) manager.updateViewLayout(layout, params)
        }
        var startRawX = 0f
        var startRawY = 0f
        var startPanelX = 0
        var startPanelY = 0
        var moved = false
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startPanelX = params.x
                    startPanelY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startRawX
                    val dy = event.rawY - startRawY
                    if (!moved && (kotlin.math.abs(dx) > 12 || kotlin.math.abs(dy) > 12)) {
                        moved = true
                        if (!compact) setCompact(true)
                        startPanelX = params.x
                        startPanelY = params.y
                        startRawX = event.rawX
                        startRawY = event.rawY
                    }
                    if (moved) {
                        val width = if (compact) params.width else resources.displayMetrics.widthPixels
                        val maxX = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
                        val maxY = (resources.displayMetrics.heightPixels - layout.height).coerceAtLeast(0)
                        params.x = (startPanelX + (event.rawX - startRawX).toInt()).coerceIn(0, maxX)
                        params.y = (startPanelY + (event.rawY - startRawY).toInt()).coerceIn(0, maxY)
                        manager.updateViewLayout(layout, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) setCompact(!compact)
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
        val toggle = Button(this).apply {
            text = "小さくする"
            setOnClickListener { setCompact(true) }
        }
        layout.addView(dragHandle); layout.addView(consent); layout.addView(status); layout.addView(objective); layout.addView(demoStart); layout.addView(demoStop); layout.addView(suggest); layout.addView(accept); layout.addView(auto); layout.addView(stop); layout.addView(toggle)
        manager.addView(layout, params)
        panel = layout
        setCompact(true)
        consent.visibility = android.view.View.GONE
        layout.visibility = android.view.View.GONE
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() == packageName) return
        if (demonstrating && (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED)) recordDemonstration(event)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val isChrome = event.packageName?.toString() in setOf("com.android.chrome", "com.microsoft.emmx", "org.mozilla.firefox", "com.brave.browser", "com.sec.android.app.sbrowser", "com.opera.browser")
        if (isChrome) {
            browserTransitionToken++
            if (!chromeActive) {
                chromeActive = true
                panel?.visibility = android.view.View.VISIBLE
                if (!browserConsentShown) {
                    consentPanel?.visibility = android.view.View.VISIBLE
                    browserConsentShown = true
                }
            }
        } else if (chromeActive) {
            // Chrome emits short-lived non-browser window events while navigating,
            // opening the keyboard, or changing tabs. Verify the foreground later.
            val token = ++browserTransitionToken
            scope.launch {
                delay(900)
                if (token != browserTransitionToken) return@launch
                val foreground = rootInActiveWindow
                val foregroundPackage = foreground?.packageName?.toString()
                foreground?.recycle()
                if (foregroundPackage == null || foregroundPackage == packageName ||
                    foregroundPackage in setOf("com.android.chrome", "com.microsoft.emmx",
                        "org.mozilla.firefox", "com.brave.browser",
                        "com.sec.android.app.sbrowser", "com.opera.browser")) return@launch
                chromeActive = false
                browserConsentShown = false
                panel?.visibility = android.view.View.GONE
                proposedIndex = null
                proposedAction = null
                approve?.isEnabled = false
                autoRunning = false
                runToken++
            }
        }
    }

    private fun analyze(automatic: Boolean = false, token: Int = runToken) {
        if (pending) return
        proposedAction = null
        proposedIndex = null
        approve?.isEnabled = false
        val root = rootInActiveWindow
        if (root?.packageName?.toString() != "com.android.chrome") {
            message?.text = "Chromeを開いてから実行してください"
            if (automatic) autoRunning = false
            return
        }
        nodes.forEach { it.recycle() }
        val found = mutableListOf<AccessibilityNodeInfo>()
        val labels = mutableListOf<String>()
        val bounds = mutableListOf<android.graphics.Rect>()
        fun visit(node: AccessibilityNodeInfo, depth: Int): String {
            if (depth > 22 || found.size >= 100 || !node.isVisibleToUser) return ""
            val own = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                .joinToString(" ").trim()
            val children = mutableListOf<String>()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val label = visit(child, depth + 1)
                if (label.isNotBlank() && children.size < 5) children.add(label)
                child.recycle()
            }
            val label = if (own.isNotBlank()) own else children.joinToString(" ").take(120)
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if ((node.isClickable || node.isEditable || (own.isNotBlank() && node.childCount == 0)) && !rect.isEmpty && !node.isPassword &&
                label.isNotBlank() && !protectedWords.containsMatchIn(label)) {
                found.add(AccessibilityNodeInfo.obtain(node))
                labels.add(label.take(120))
                bounds.add(rect)
            }
            return label
        }
        visit(root, 0)
        root.recycle()
        nodes = found
        nodeLabels = labels
        candidateBounds = bounds
        if (found.isEmpty()) { message?.text = "読み取れる操作対象がありません"; if (automatic) autoRunning = false; return }
        val inventory = JSONArray()
        found.forEachIndexed { i, node ->
            inventory.put(JSONObject().put("id", i).put("editable", node.isEditable).put("clickable", node.isClickable || !node.isEditable).put("text",
                nodeLabels[i]).put("x", candidateBounds[i].centerX()).put("y", candidateBounds[i].centerY()).put("region", if (candidateBounds[i].centerY() > resources.displayMetrics.heightPixels * 0.8) "bottom" else "upper"))
        }
        val settings = LocalSettings(this)
        val key = settings.get("apiKey")
        val model = settings.get("model")
        if (key.isBlank() || model.isBlank()) { message?.text = "アプリでAPIキーとモデルを設定してください"; if (automatic) autoRunning = false; return }
        message?.text = "AIが画面の文字と操作対象を解析しています…"
        pending = true
        scope.launch {
            try {
                val goal = if (automatic) runGoal else objectiveInput?.text?.toString()?.take(500).orEmpty().ifBlank { settings.get("objective") }
                val action = BrowserAgent.suggest(if (automatic) runKey else key, if (automatic) runModel else model,
                    "目標: $goal。Chrome画面の候補から安全なクリックまたは入力欄への入力を一つ選んでください。入力が必要な場合は目標に明示された一般的な検索語だけを使ってください。",
                    inventory.toString() + "\n過去の操作結果（正数は画面変化、負数は変化なし）: " + learnedHints(goal) + "; お手本の操作順: " + demonstrationHints(goal))
                if (automatic && (!autoRunning || token != runToken)) return@launch
                val index = Regex("""\[data-za-id="([0-9]+)"\]""")
                    .matchEntire(action.selector)?.groupValues?.get(1)?.toIntOrNull()
                if (action.type in listOf("click", "fill") && index != null && index in nodes.indices && (action.type != "fill" || (nodes[index].isEditable && action.value.length <= 300))) {
                    proposedAction = action
                    proposedIndex = index
                    message?.text = "AI提案: ${action.explanation}\n操作: ${action.type} / 対象: ${nodeLabels.getOrNull(index)}\n入力: ${if (action.type == "fill") action.value else "なし"}"
                    approve?.isEnabled = !automatic
                    if (automatic) {
                        delay(650)
                        if (autoRunning && token == runToken) {
                            val learningLabel = nodeLabels.getOrNull(index).orEmpty()
                            val before = screenFingerprint()
                            val succeeded = clickApproved(true)
                            val changed = succeeded && screenChanged(before)
                            if (succeeded && action.type == "click") learnClick(goal, learningLabel, changed)
                            stepsRemaining--
                            if (changed && stepsRemaining > 0 && autoRunning && token == runToken) {
                                message?.text = "画面の変化を確認。次の操作を解析します"
                                pending = false
                                analyze(true, token)
                            } else {
                                autoRunning = false
                                message?.text = if (succeeded && !changed)
                                    "画面の変化を確認できないため、連続操作を停止しました"
                                else "連続操作を終了しました"
                            }
                        }
                    }
                } else { message?.text = "安全に操作できる候補はありません"; if (automatic) autoRunning = false }
            } catch (e: Exception) { if (!automatic || token == runToken) message?.text = "解析エラー: ${e.message?.take(120)}"; if (automatic) autoRunning = false }
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

    private fun recordDemonstration(event: AccessibilityEvent) {
        if (event.packageName?.toString() != "com.android.chrome" || demoSteps.length() >= 80) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - demoLastEventAt < 300) return
        val source = event.source ?: return
        if (source.isPassword || !source.isVisibleToUser) { source.recycle(); return }
        val label = source.contentDescription?.toString().orEmpty().ifBlank { source.text?.toString().orEmpty() }.take(70)
        val safeLabel = if (source.isEditable || protectedWords.containsMatchIn(label)) "" else label
        val rect = android.graphics.Rect()
        source.getBoundsInScreen(rect)
        source.recycle()
        demoLastEventAt = now
        val kind = if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) "scroll" else "click"
        demoSteps.put(JSONObject().put("action", kind).put("label", safeLabel)
            .put("xPercent", rect.centerX() * 100 / resources.displayMetrics.widthPixels.coerceAtLeast(1))
            .put("yPercent", rect.centerY() * 100 / resources.displayMetrics.heightPixels.coerceAtLeast(1))
            .put("region", if (rect.centerY() > resources.displayMetrics.heightPixels * 0.8) "bottom" else "upper"))
        message?.text = "お手本記録中: " + demoSteps.length() + "操作"
    }

    private fun demonstrationHints(goal: String): String {
        if (goal.isBlank()) return ""
        val prefs = getSharedPreferences("browser_demonstrations", MODE_PRIVATE)
        val examples = try { JSONArray(prefs.getString("examples", "[]")) } catch (_: Exception) { JSONArray() }
        val matched = mutableListOf<String>()
        for (i in 0 until examples.length()) {
            val example = examples.optJSONObject(i) ?: continue
            val name = example.optString("goal")
            if (name.isNotBlank() && (goal.contains(name, true) || name.contains(goal, true)))
                matched.add(example.optJSONArray("steps")?.toString()?.take(1800).orEmpty())
        }
        return matched.takeLast(2).joinToString("; ").take(3500)
    }

    private fun learnClick(goal: String, label: String, changed: Boolean) {
        if (goal.isBlank() || label.isBlank() || protectedWords.containsMatchIn(label)) return
        val prefs = getSharedPreferences("browser_click_learning", MODE_PRIVATE)
        val key = (goal.trim().lowercase() + "|" + label.trim().lowercase()).take(180)
        val old = prefs.getInt(key, 0)
        prefs.edit().putInt(key, (old + if (changed) 1 else -1).coerceIn(-5, 10)).apply()
    }

    private fun learnedHints(goal: String): String {
        if (goal.isBlank()) return ""
        val prefix = goal.trim().lowercase() + "|"
        return getSharedPreferences("browser_click_learning", MODE_PRIVATE).all.entries
            .filter { it.key.startsWith(prefix) && it.value is Int }
            .sortedByDescending { it.value as Int }
            .take(12)
            .joinToString("; ") { it.key.removePrefix(prefix).take(70) + "=" + it.value }
            .take(1000)
    }

    private fun screenFingerprint(): String? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != "com.android.chrome") { root.recycle(); return null }
        val parts = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 18 || parts.size >= 120) return
            if (node.isVisibleToUser && !node.isPassword) {
                val label = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").trim()
                if (label.isNotEmpty()) parts.add(label.take(100))
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
                child.recycle()
            }
        }
        visit(root, 0)
        root.recycle()
        return parts.joinToString("|").take(8000)
    }

    private suspend fun screenChanged(before: String?): Boolean {
        if (before == null) return false
        repeat(8) {
            delay(450)
            val after = screenFingerprint()
            if (after != null && after != before) return true
        }
        return false
    }

    private fun clickNodeOrTap(node: AccessibilityNodeInfo, index: Int): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val rect = candidateBounds.getOrNull(index) ?: return false
        if (rect.isEmpty) return false
        panel?.visibility = android.view.View.GONE
        val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build()
        return dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                panel?.visibility = android.view.View.VISIBLE
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                panel?.visibility = android.view.View.VISIBLE
                message?.text = "座標タップがキャンセルされました"
            }
        }, null)
    }

    private fun clickApproved(automatic: Boolean = false): Boolean {
        val index = proposedIndex ?: return false
        val action = proposedAction ?: return false
        proposedIndex = null
        proposedAction = null
        approve?.isEnabled = false
        val node = nodes.getOrNull(index) ?: return false
        val before = if (!automatic) screenFingerprint() else null
        val learningLabel = nodeLabels.getOrNull(index).orEmpty()
        val learningGoal = objectiveInput?.text?.toString().orEmpty().ifBlank { LocalSettings(this).get("objective") }
        val root = rootInActiveWindow
        val name = listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ")
        if (root?.packageName?.toString() == "com.android.chrome" && node.refresh() &&
            node.isVisibleToUser && (if (action.type == "fill") node.isEditable else !candidateBounds[index].isEmpty) && !node.isPassword &&
            !protectedWords.containsMatchIn(name)) {
            val success = if (action.type == "fill" && action.value.length <= 300) {
                val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.value) }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } else if (action.type == "click") clickNodeOrTap(node, index) else false
            message?.text = if (success) "操作しました。${if (automatic) "次の画面を確認中" else "次の操作は再提案してください"}" else "操作できませんでした"
            root?.recycle()
            if (success && !automatic) scope.launch {
                val changed = screenChanged(before)
                if (action.type == "click") learnClick(learningGoal, learningLabel, changed)
                message?.text = if (changed) "画面変化を確認し、操作結果を学習しました" else "画面変化を確認できず、失敗候補として記録しました"
            }
            return success
        } else message?.text = "画面が変わりました。再解析してください"
        root?.recycle()
        return false
    }

    override fun onInterrupt() {}
    override fun onDestroy() {
        autoRunning = false
        runToken++
        panel?.let { wm?.removeView(it) }
        nodes.forEach { it.recycle() }
        scope.cancel()
        super.onDestroy()
    }
}