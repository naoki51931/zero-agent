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
    private val recentActions = mutableSetOf<String>()
    private var recoveryAttempts = 0
    private val maxRecoveryAttempts = 1
    private var runGoal = ""
    private var runKey = ""
    private var runModel = ""
    private var nodes = emptyList<AccessibilityNodeInfo>()
    private var nodeLabels = emptyList<String>()
    private var candidateBounds = emptyList<android.graphics.Rect>()
    private var proposedIndex: Int? = null
    private var proposedAction: BrowserAction? = null
    private var objectiveInput: EditText? = null
    // Browser and native X screens are supported only while they are foreground.
    private val supportedPackages = setOf(
        "com.android.chrome", "com.microsoft.emmx", "org.mozilla.firefox",
        "com.brave.browser", "com.sec.android.app.sbrowser", "com.opera.browser",
        "com.twitter.android"
    )
    private fun isSupportedScreen(pkg: String?): Boolean = pkg in supportedPackages

    private val xResearchMemory by lazy { XResearchMemory(this) }
    private var xResearchActive = false
    private var xResearchToken = 0

    private val researchQueryStore by lazy { ResearchQueryStore(this) }

    private fun xSearchTerm(): String = xSearchTerms().firstOrNull().orEmpty()

    /** Segment the requested subject dynamically; no topic-specific keyword constants. */
    private fun xSearchTerms(): List<String> {
        val objective = objectiveInput?.text?.toString().orEmpty()
            .ifBlank { LocalSettings(this).get("objective") }.trim().take(500)
        if (objective.isBlank()) return emptyList()
        val stored = researchQueryStore.find(objective)
        if (stored.isNotEmpty()) return stored
        val subject = objective
            .replace(Regex("https?://\\S+"), " ")
            .replace(Regex("[、。,.!！?？\\n\\r]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .replace(Regex("(?:について|に関して|を対象に|を使って|で|を)(?:市場)?(?:調査|検索|分析|探す|探して|調べる|調べて|して|する|作成|制作|投稿).*$"), "")
            .trim()
        val segments = subject.split(Regex("(?:の|と|及び|および|\\s+)"))
            .map { it.trim().replace(Regex("^(?:で|を|は|が|に|の)+|(?:の|を|は|が|に)+$"), "") }
            .filter { it.length in 2..40 && !it.contains("://") }
            .distinct()
        val single = if (segments.isEmpty()) listOf(subject.take(60)) else segments.take(5)
        val pairs = single.indices.flatMap { i ->
            (i + 1 until single.size).map { j -> single[i] + " " + single[j] }
        }
        val queries = (single + pairs).filter { it.length in 2..80 }.distinct().take(12)
        researchQueryStore.remember(objective, queries)
        return queries
    }

    private fun xNodes(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != "com.twitter.android") { root.recycle(); return emptyList() }
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 20 || found.size >= 180 || !node.isVisibleToUser) return
            if (!node.isPassword && (node.isClickable || node.isEditable || node.text != null || node.contentDescription != null))
                found.add(AccessibilityNodeInfo.obtain(node))
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                visit(child, depth + 1)
                child.recycle()
            }
        }
        visit(root, 0)
        root.recycle()
        return found
    }

    private fun xLabel(n: AccessibilityNodeInfo) =
        listOfNotNull(n.text?.toString(), n.contentDescription?.toString()).joinToString(" ").trim()

    private fun xClickSearch(): Boolean {
        val items = xNodes()
        return try {
            items.firstOrNull {
                it.isClickable && Regex("^(検索|Search|Explore)(タブ)?$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(xLabel(it))
            }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } finally { items.forEach { it.recycle() } }
    }

    private fun xFillSearch(term: String): Boolean {
        val items = xNodes()
        return try {
            val edit = items.firstOrNull { it.isEditable && !it.isPassword } ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, term)
            }
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } finally { items.forEach { it.recycle() } }
    }

    private fun xSubmitSearch(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != "com.twitter.android") {
            root.recycle()
            return false
        }
        val items = xNodes()
        return try {
            // Prefer IME search action on the focused search field.
            val edit = items.firstOrNull { it.isEditable && it.isFocused && !it.isPassword }
                ?: items.firstOrNull { it.isEditable && !it.isPassword }
            if (edit != null && edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) return true
            // X may expose a dedicated search button. Do not click unrelated submit actions.
            val searchButton = items.firstOrNull {
                it.isClickable && !it.isEditable &&
                    Regex("^(検索|Search|検索する|Search posts)$", RegexOption.IGNORE_CASE)
                        .matches(xLabel(it))
            }
            searchButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } finally {
            items.forEach { it.recycle() }
            root.recycle()
        }
    }

    private fun xCollect(term: String): Int {
        val items = xNodes()
        return try { xResearchMemory.collect(term, items.map { xLabel(it) }.distinct()) }
        finally { items.forEach { it.recycle() } }
    }

    private fun startXResearch() {
        if (xResearchActive) return
        val terms = xSearchTerms()
        val term = terms.firstOrNull().orEmpty()
        if (term.length < 2) { message?.text = "調査キーワードを2文字以上入力してください"; return }
        val foreground = rootInActiveWindow
        val isX = foreground?.packageName?.toString() == "com.twitter.android"
        foreground?.recycle()
        if (!isX) { message?.text = "X公式アプリを前面に開いてください"; return }
        xResearchActive = true
        val token = ++xResearchToken
        scope.launch {
            val memory = EpisodeMemory(this@ChromeAssistService)
            try {
                for ((queryIndex, query) in terms.withIndex()) {
                message?.text = "X検索 ${queryIndex + 1}/${terms.size}: $query"
                val searchReady = xNodes().any { it.isEditable && !it.isPassword }
                if (!searchReady && !xClickSearch()) {
                    message?.text = "検索タブが見つかりません。Xの検索画面を表示して再実行してください"
                    memory.record("x.com", query, "search-tab", "", "", "failed", "検索タブが見つからない")
                    break
                }
                if (!searchReady) delay(1000)
                if (!xResearchActive || token != xResearchToken) break
                if (!xFillSearch(query)) {
                    message?.text = "検索欄を手動で開いてから再実行してください"
                    memory.record("x.com", query, "search-input", "", "", "failed", "検索欄が見つからない")
                    break
                }
                memory.record("x.com", query, "search-input", "", "filled", "success", "検索欄への入力が成功")
                message?.text = "検索を自動確定しています"
                delay(500)
                if (!xResearchActive || token != xResearchToken) break
                val beforeSubmit = screenFingerprint().orEmpty()
                val submitted = xSubmitSearch()
                if (!submitted) {
                    message?.text = "検索の自動確定に失敗しました。キーボードの検索を押して再実行してください"
                    memory.record("x.com", query, "search-submit", beforeSubmit.take(80), "", "failed",
                        "検索IMEアクション・検索ボタンが利用できない")
                    break
                }
                delay(1800)
                if (!xResearchActive || token != xResearchToken) break
                val afterSubmit = screenFingerprint().orEmpty()
                if (beforeSubmit == afterSubmit) {
                    message?.text = "検索結果への遷移を確認できませんでした。Xの画面を確認してください"
                    memory.record("x.com", query, "search-submit", beforeSubmit.take(80),
                        afterSubmit.take(80), "unknown", "検索操作後に画面変化を確認できない")
                    break
                }
                memory.record("x.com", query, "search-submit", beforeSubmit.take(80),
                    afterSubmit.take(80), "unknown", "画面変化あり。検索結果かは収集で確認")
                for (page in 1..4) {
                    if (!xResearchActive || token != xResearchToken) break
                    val before = screenFingerprint().orEmpty()
                    val saved = xCollect(query)
                    memory.record("x.com", query, "read-results", "", before.take(80),
                        if (saved > 0) "success" else "unknown",
                        if (saved > 0) "いいね数を含む候補を保存" else "候補を抽出できず")
                    message?.text = "X調査 $page/4：今回 $saved 件、累計 " + xResearchMemory.count(query) + " 件"
                    if (page < 4) {
                        val root = rootInActiveWindow
                        if (root?.packageName?.toString() != "com.twitter.android") {
                            root?.recycle()
                            break
                        }
                        val scrolled = root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        root.recycle()
                        if (!scrolled) break
                        delay(1600)
                    }
                }
                }
                if (xResearchActive && token == xResearchToken) {
                    message?.text = "調査終了: " + xResearchMemory.summaryAll(terms)
                }
            } catch (e: Exception) {
                message?.text = "X調査エラー: " + e.message.orEmpty().take(100)
            } finally { xResearchActive = false }
        }
    }

    private val protectedWords = Regex("login|sign.in|password|checkout|purchase|payment|delete|send|publish|submit|consent|captcha|ログイン|認証|購入|支払|削除|送信|投稿|同意|パスワード", RegexOption.IGNORE_CASE)

    override fun onServiceConnected() {
        val manager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = manager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 12, 20, 12)
            setBackgroundColor(0xB8F2ECFA.toInt())
        }
        val status = TextView(this).apply { text = "ZERO AGENT: ChromeまたはXを開いてください" }
        message = status
        val consent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        consent.addView(TextView(this).apply { text = "ChromeまたはXでZero Agentを使いますか？"; textSize = 16f })
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
            hint = "ChromeまたはXで何をしたい？"
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
                recentActions.clear()
                recoveryAttempts = 0
                runToken++
                analyze(true, runToken)
            }
        }
        val xResearch = Button(this).apply {
            text = "Xアプリで市場調査（閲覧のみ）"
            setOnClickListener { startXResearch() }
        }
        val xResults = Button(this).apply {
            text = "X調査結果を見る"
            setOnClickListener { message?.text = xResearchMemory.summaryAll(xSearchTerms()) }
        }
        val stop = Button(this).apply { text = "停止"; setOnClickListener {
            autoRunning = false; runToken++; stepsRemaining = 0; recentActions.clear()
            xResearchActive = false; xResearchToken++
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
        val controls = listOf(status, objective, demoStart, demoStop, suggest, accept, auto, xResearch, xResults, stop)
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
        layout.addView(dragHandle); layout.addView(consent); layout.addView(status); layout.addView(objective); layout.addView(demoStart); layout.addView(demoStop); layout.addView(suggest); layout.addView(accept); layout.addView(auto); layout.addView(xResearch); layout.addView(xResults); layout.addView(stop); layout.addView(toggle)
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
        val isChrome = isSupportedScreen(event.packageName?.toString())
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
                    isSupportedScreen(foregroundPackage)) return@launch
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
        if (!isSupportedScreen(root?.packageName?.toString())) {
            message?.text = "ChromeまたはXアプリを開いてから実行してください"
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
            val closeControl = label.trim().matches(Regex("(?i)^(×|✕|✖|✗|x|close|dismiss|閉じる|とじる)$"))
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            if ((node.isClickable || node.isEditable || (own.isNotBlank() && node.childCount == 0)) && !rect.isEmpty && !node.isPassword &&
                label.isNotBlank() && !protectedWords.containsMatchIn(label)) {
                found.add(AccessibilityNodeInfo.obtain(node))
                labels.add(if (closeControl) "閉じる (× / Close)" else label.take(120))
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
                val currentUrl = visibleBrowserUrl()
                val pageContext = "現在URL: ${currentUrl ?: "取得不可（推測禁止）"}\n" +
                    "現在の画面に存在する候補だけを操作すること。すでに目標ページなら遷移ボタンを探さない。ポップアップが操作を妨げている場合は、候補の閉じる/Close/×を優先する。購入・ログイン・同意は実行しない。\n" +
                    "候補一覧: " + inventory.toString() +
                    "\n過去の操作結果（正数は画面変化、負数は変化なし）: " + learnedHints(goal) +
                    "; お手本の操作順: " + demonstrationHints(goal) +
                    "; 過去の実行経験（画面変化は目標達成を意味しない）: " + experienceHints(goal)
                // Verify the goal against the freshly observed controls, not against
                // whether a click succeeded or the screen fingerprint changed.
                if (automatic && BrowserAgent.verifyGoal(runKey, runModel, goal,
                        "URL: ${currentUrl ?: "unknown"}; visible controls: ${inventory}")) {
                    if (autoRunning && token == runToken) {
                        autoRunning = false
                        message?.text = "現在の画面から目標達成を確認しました。連続操作を終了します"
                    }
                    return@launch
                }
                if (automatic && (!autoRunning || token != runToken)) return@launch
                val action = BrowserAgent.suggest(if (automatic) runKey else key, if (automatic) runModel else model,
                    "目標: $goal。Chrome画面の候補から安全なクリックまたは入力欄への入力を一つ選んでください。入力が必要な場合は目標に明示された一般的な検索語だけを使ってください。",
                    pageContext)
                if (automatic && (!autoRunning || token != runToken)) return@launch
                val index = Regex("""\[data-za-id="([0-9]+)"\]""")
                    .matchEntire(action.selector)?.groupValues?.get(1)?.toIntOrNull()
                if (action.type == "none") {
                    autoRunning = false
                    message?.text = "AIが次の安全な操作を見つけられませんでした: ${action.explanation.take(140)}。目標達成は未確認です"
                    return@launch
                }
                if (action.type in listOf("click", "fill") && index != null && index in nodes.indices && (if (action.type == "fill") nodes[index].isEditable && action.value.length <= 300 else nodes[index].isClickable) && nodes[index].refresh() && nodes[index].isVisibleToUser) {
                    proposedAction = action
                    proposedIndex = index
                    message?.text = "AI提案: ${action.explanation}\n操作: ${action.type} / 対象: ${nodeLabels.getOrNull(index)}\n入力: ${if (action.type == "fill") action.value else "なし"}"
                    approve?.isEnabled = !automatic
                    if (automatic) {
                        val actionSignature = "${currentUrl.orEmpty()}|${action.type}|${nodeLabels[index]}|${action.value}"
                        if (!recentActions.add(actionSignature)) {
                            autoRunning = false
                            message?.text = "同じ操作の繰り返しを検出したため停止しました。画面と目標を確認してください"
                            return@launch
                        }
                        delay(650)
                        if (autoRunning && token == runToken) {
                            val learningLabel = nodeLabels.getOrNull(index).orEmpty()
                            val before = screenFingerprint()
                            val succeeded = clickApproved(true)
                            val changed = succeeded && screenChanged(before)
                            if (action.type == "click") learnClick(goal, learningLabel, changed && succeeded)
                            rememberOutcome(goal, learningLabel, action.type, when { !succeeded -> "failed"; changed -> "screen_changed"; else -> "no_change" })
                            stepsRemaining--
                            val canContinue = succeeded && stepsRemaining > 0 &&
                                autoRunning && token == runToken &&
                                (changed || recoveryAttempts < maxRecoveryAttempts)
                            if (canContinue) {
                                if (!changed) {
                                    // One bounded recovery: re-observe and ask for a DIFFERENT
                                    // visible action. The existing recentActions guard prevents
                                    // repeating the same URL/label/action/value combination.
                                    recoveryAttempts++
                                    message?.text = "画面変化なし。操作候補を再確認します（復旧1回まで）"
                                } else {
                                    recoveryAttempts = 0
                                    message?.text = "画面の変化を確認。次の操作を解析します"
                                }
                                pending = false
                                analyze(true, token)
                            } else {
                                autoRunning = false
                                message?.text = when {
                                    !succeeded -> "操作に失敗したため停止しました"
                                    !changed -> "画面変化なし。復旧回数の上限に達したため停止しました。目標達成は未確認です"
                                    stepsRemaining <= 0 -> "最大5回の操作を終了しました。目標達成は未確認です"
                                    else -> "連続操作を停止しました。目標達成は未確認です"
                                }
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
        if (!isSupportedScreen(root?.packageName?.toString())) {
            message?.text = "ChromeまたはXアプリを開いてください"
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
        if (!isSupportedScreen(event.packageName?.toString()) || demoSteps.length() >= 80) return
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

    // Bounded device-local experience memory. Never persist entered values or full URLs.
    private fun rememberOutcome(goal: String, label: String, type: String, outcome: String) {
        if (goal.isBlank() || label.isBlank() || type !in listOf("click", "fill") ||
            protectedWords.containsMatchIn(label)) return
        val prefs = getSharedPreferences("agent_experience_v1", MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString("history", "[]")) } catch (_: Exception) { JSONArray() }
        val history = JSONArray()
        for (i in (old.length() - 79).coerceAtLeast(0) until old.length()) {
            old.optJSONObject(i)?.let { history.put(it) }
        }
        val host = try {
            val raw = visibleBrowserUrl().orEmpty()
            val normalized = if (raw.contains("://")) raw else "https://$raw"
            Uri.parse(normalized).host.orEmpty().take(120)
        } catch (_: Exception) { "" }
        history.put(JSONObject()
            .put("goal", goal.trim().lowercase().take(120))
            .put("host", host)
            .put("label", label.take(80))
            .put("action", type)
            .put("outcome", outcome)
            .put("at", System.currentTimeMillis()))
        prefs.edit().putString("history", history.toString()).apply()
    }

    private fun experienceHints(goal: String): String {
        if (goal.isBlank()) return ""
        val raw = getSharedPreferences("agent_experience_v1", MODE_PRIVATE)
            .getString("history", "[]")
        val history = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
        val normalized = goal.trim().lowercase().take(120)
        val hints = mutableListOf<String>()
        for (i in (history.length() - 1) downTo 0) {
            val item = history.optJSONObject(i) ?: continue
            if (item.optString("goal") != normalized) continue
            hints.add("${item.optString("host")}: ${item.optString("action")} ${item.optString("label")} => ${item.optString("outcome")}")
            if (hints.size == 8) break
        }
        return hints.joinToString("; ").take(1300)
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

    private fun visibleBrowserUrl(): String? {
        val root = rootInActiveWindow ?: return null
        if (!isSupportedScreen(root.packageName?.toString())) { root.recycle(); return null }
        var url: String? = null
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 16 || url != null) return
            if (node.isVisibleToUser && !node.isPassword) {
                val value = node.text?.toString()?.trim().orEmpty()
                val id = node.viewIdResourceName.orEmpty()
                if ((id.contains("url_bar") || id.contains("location_bar")) &&
                    (value.startsWith("https://") || value.startsWith("http://") ||
                     Regex("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/.*)?$").matches(value))) {
                    url = value.take(500)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
                child.recycle()
            }
        }
        walk(root, 0)
        root.recycle()
        return url
    }

    private fun screenFingerprint(): String? {
        val root = rootInActiveWindow ?: return null
        if (!isSupportedScreen(root.packageName?.toString())) { root.recycle(); return null }
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
        return ("url=" + (visibleBrowserUrl() ?: "unknown") + "|" + parts.joinToString("|")).take(8000)
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
        if (isSupportedScreen(root?.packageName?.toString()) && node.refresh() &&
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
                rememberOutcome(learningGoal, learningLabel, action.type, if (changed) "screen_changed" else "no_change")
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