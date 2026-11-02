package com.zeroagent.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * User-initiated, conservative Chrome/SeaArt UI automation.
 * No coordinate taps: an unknown UI leaves the workflow pending for inspection.
 */
class SeaArtAndroidFlow(private val service: AccessibilityService) {
    private val settings get() = LocalSettings(service)
    private fun label(node: AccessibilityNodeInfo): String =
        listOfNotNull(node.text?.toString(), node.contentDescription?.toString()).joinToString(" ").trim()

    private fun trace(message: String) {
        val time = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.JAPAN)
            .format(java.util.Date())
        val old = settings.get("seaartActionLog")
        settings.put("seaartActionLog", (old + time + " " + message + "\n").takeLast(12000))
    }

    suspend fun advance() {
        var state = settings.get("seaartPendingState")
        if (state.isBlank()) return
        val since = settings.get("seaartPendingSince").toLongOrNull() ?: 0L
        if (since <= 0 || System.currentTimeMillis() - since > 15 * 60_000L) {
            settings.put("seaartPendingState", ""); return
        }
        // No LoRA requested: this is a configuration decision, not a UI
        // interaction. Skip before accessing Chrome's accessibility root,
        // which can be null while the IME/keyboard is active.
        if (state == "awaiting_lora_menu" && settings.get("seaartPendingLora").isBlank()) {
            trace("LoRA unspecified; skip selector immediately and proceed to prompt")
            settings.put("seaartPendingState", "awaiting_editor")
            state = "awaiting_editor"
            // Continue in this pass if Chrome's editor is accessible.
        }
        val root = service.rootInActiveWindow ?: run { trace("rootInActiveWindow=null state=$state"); return }
        try {
            if (root.packageName?.toString() != "com.android.chrome") return
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40 || nodes.size >= 2500) return
                nodes.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            if (nodes.none { label(it).contains("SeaArt", true) || label(it).contains("seaart.ai", true) }) { trace("SeaArt marker missing state=$state nodes=${nodes.size}"); return }

            fun find(pattern: String): AccessibilityNodeInfo? {
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                return nodes.firstOrNull { it.isVisibleToUser && regex.matches(label(it)) &&
                    (it.isClickable || it.parent?.isClickable == true) }
            }
            fun click(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                var parent = node.parent
                repeat(4) {
                    if (parent == null) return false
                    if (parent!!.isClickable && parent!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    parent = parent!!.parent
                }
                return false
            }
            fun type(node: AccessibilityNodeInfo?, value: String): Boolean {
                if (node == null || !node.isEditable || !node.isVisibleToUser || node.isPassword) return false
                return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                })
            }
            fun editable(): AccessibilityNodeInfo? = nodes.firstOrNull {
                it.isEditable && it.isVisibleToUser && !it.isPassword &&
                    !label(it).contains("検索または URL", true) &&
                    !label(it).contains("Search or type web address", true) &&
                    (it.text?.length ?: 0) < 3500
            }
            fun next(value: String) { trace("state $state -> $value"); settings.put("seaartPendingState", value) }

            val screenHeight = service.resources.displayMetrics.heightPixels
            fun inHeader(node: AccessibilityNodeInfo): Boolean {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                return node.isVisibleToUser && !bounds.isEmpty &&
                    bounds.centerY() in 0..(screenHeight * 0.26f).toInt()
            }
            // Chrome may truncate the address bar. The creation editor also exposes
            // a top model selector and image-generation controls; the home feed does not.
            val urlConfirmed = nodes.any { label(it).contains("/create/image", true) }
            val topModel = nodes.any { inHeader(it) &&
                Regex("(?i).*(GR-Illustrious|モデル|checkpoint|civedgemix|cityedgemix|model).*").matches(label(it)) }
            val editorControls = nodes.any { it.isVisibleToUser &&
                Regex("(?i).*(Img2Img|再編集|再生成|ライブラリ|アシスタント).*").matches(label(it)) }
            // In SeaArt's mobile editor, the model selector is in the top bar,
            // while creation controls appear below. Chrome's URL node may be
            // truncated, so require both independent visual landmarks.
            val editorConfirmed = topModel && editorControls
            val onCreatePage = urlConfirmed || editorConfirmed
            val onModelDetailPage = nodes.any { label(it).contains("/models/detail/", true) }
            if (onModelDetailPage && state in setOf("awaiting_model_menu", "awaiting_model_choice",
                    "awaiting_lora_menu", "awaiting_lora_choice", "awaiting_editor")) {
                trace("model detail page detected; returning to creation editor without selecting model")
                settings.put("seaartModelConfirmed", "")
                settings.put("seaartLastError", "モデル詳細ページへ移動しました。創作画面に戻ってモデルを確認します")
                val back = nodes.firstOrNull { node ->
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    node.isVisibleToUser && bounds.centerY() < service.resources.displayMetrics.heightPixels * 0.18f &&
                        Regex("(?i).*(戻る|back).*").matches(label(node))
                }
                if (!click(back)) service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                settings.put("seaartPendingState", "awaiting_editor_return")
                return
            }
            if (state == "awaiting_editor_return") {
                if (onCreatePage) {
                    trace("returned to editor; use existing model header, avoid model detail link")
                    settings.put("seaartModelConfirmed", settings.get("seaartPendingSince"))
                    settings.put("seaartLastError", "")
                    settings.put("seaartPendingState", "awaiting_lora_menu")
                } else trace("waiting for return to editor")
                return
            }
            if (state in setOf("awaiting_create", "awaiting_create_navigation")) {
                trace("page check url=$urlConfirmed topModel=$topModel editorControls=$editorControls nodes=${nodes.size}")
            }
            fun tapCreateTab(): Boolean {
                val height = service.resources.displayMetrics.heightPixels
                val width = service.resources.displayMetrics.widthPixels
                // Search all visible nodes, including nested text/icon labels. Limit
                // matches to the bottom navigation region so the home composer
                // Create button is never selected.
                val tabs = nodes.mapNotNull { node ->
                    if (!node.isVisibleToUser) return@mapNotNull null
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.isEmpty || rect.centerY() < height * 0.78f ||
                        rect.centerX() !in 0..width || rect.centerY() !in 0..height) return@mapNotNull null
                    val text = label(node)
                    val matches = Regex("(^|\\\\s)(創作|Create)(\\\\s|$)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(text) || text.trim() in setOf("創作", "AI創作")
                    if (!matches) return@mapNotNull null
                    Triple(node, rect, text)
                }.sortedWith(compareByDescending<Triple<AccessibilityNodeInfo, Rect, String>> {
                    it.second.centerY()
                }.thenBy { it.second.width() * it.second.height() })
                trace("bottom Create candidates=${tabs.size} nodes=${nodes.size} capped=${nodes.size >= 2500} state=$state " +
                    "labels=${tabs.take(3).joinToString { it.third.take(60) }}")
                val chosen = tabs.firstOrNull()
                if (chosen == null) {
                    nodes.lastOrNull { it.isScrollable && it.isVisibleToUser }
                        ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    trace("bottom Create not found; attempted scroll backward")
                    settings.put("seaartLastError", "画面下の創作タブを探索中")
                    return false
                }
                trace("bottom Create bounds=${chosen.second.flattenToString()} label=${chosen.third.take(80)} clickable=${chosen.first.isClickable}")
                if (click(chosen.first)) { trace("ACTION_CLICK returned true (navigation unverified)"); return true }
                // Some Chrome web navigation elements expose no ACTION_CLICK. Use a
                // bounded gesture at the actual accessibility node, not a hardcoded coordinate.
                val rect = chosen.second
                val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build()
                val dispatched = service.dispatchGesture(gesture, null, null)
                trace("fallback gesture dispatched=$dispatched (navigation unverified)")
                return dispatched
            }
            val onModelDetail = nodes.any { label(it).contains("/models/detail", true) }
            if (onModelDetail && state in setOf("awaiting_model_menu", "awaiting_model_choice", "awaiting_model_verification")) {
                trace("model detail opened, not model applied; returning to editor")
                settings.put("seaartModelConfirmed", "")
                next("awaiting_model_recovery")
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                return
            }
            if (state == "awaiting_model_recovery") {
                if (onCreatePage) {
                    trace("returned to creation editor")
                    next("awaiting_model_verification")
                } else trace("waiting for creation editor after model detail")
                return
            }
            if (state == "awaiting_model_verification") {
                if (!onCreatePage) { trace("model verification waiting for editor"); return }
                val target = settings.get("seaartPendingModel").ifBlank { "GR-Illustrious 3in1" }
                if (nodes.any { inHeader(it) && label(it).contains(target, true) }) {
                    trace("model verified in creation editor header: " + target.take(60))
                    settings.put("seaartModelConfirmed", settings.get("seaartPendingSince"))
                    next("awaiting_lora_menu")
                } else {
                    trace("model not verified in creation editor; stopped")
                    settings.put("seaartLastError", "画像創作画面で指定モデルを確認できません")
                    next("model_verification_failed")
                }
                return
            }
            // The user may choose the checkpoint manually. The editor's top
            // model label is authoritative for this opt-in workflow; do not
            // click model result cards, which open /models/detail instead.
            val requestedModel = settings.get("seaartPendingModel").ifBlank { "GR-Illustrious 3in1" }
            // Chrome may expose the model title as separate accessibility
            // text nodes (e.g. "GR-Illustrious" and "3in1").
            val headerLabels = nodes.filter(::inHeader).map(::label)
                .filter { it.isNotBlank() }
            fun normalizeModel(value: String): String =
                value.lowercase(java.util.Locale.ROOT)
                    .replace(Regex("""[\s_\-]+"""), "")
            val expected = normalizeModel(requestedModel)
            val combinedHeader = normalizeModel(headerLabels.joinToString(" "))
            val modelInEditorHeader = onCreatePage && (
                headerLabels.any { normalizeModel(it).contains(expected) } ||
                    combinedHeader.contains(expected)
                )
            if (state == "awaiting_model_menu") {
                trace("model checkpoint check onCreatePage=$onCreatePage headerMatch=$modelInEditorHeader target=${requestedModel.take(70)} header=${headerLabels.filter { it.contains("model", true) || it.contains("モデル") || it.contains("illustrious", true) || it.contains("3in1", true) }.take(8).joinToString(" | ").take(260)}")
            }
            if (modelInEditorHeader && state in setOf(
                    "awaiting_create", "awaiting_create_navigation",
                    "awaiting_model_menu", "awaiting_model_choice",
                    "awaiting_model_choice_review", "awaiting_model_verification")) {
                trace("active model confirmed: " + requestedModel.take(70) +
                    "; continuing without reopening model selector")
                settings.put("seaartModelConfirmed", settings.get("seaartPendingSince"))
                settings.put("seaartLastError", "")
                state = if (settings.get("seaartPendingLora").isBlank()) {
                    trace("LoRA unspecified; proceeding directly to prompt")
                    "awaiting_editor"
                } else "awaiting_lora_menu"
                settings.put("seaartPendingState", state)
                // Continue within the same Chrome accessibility snapshot.
                // Waiting for another accessibility event can strand the run.
            } else if (onCreatePage && state in setOf("awaiting_create", "awaiting_create_navigation")) {
                trace("editor recovered, model not yet confirmed: $state -> awaiting_model_menu")
                state = "awaiting_model_menu"
                settings.put("seaartPendingState", state)
            }
            when (state) {
                "awaiting_create" -> {
                    if (onCreatePage) {
                        trace("creation editor confirmed; proceeding to model selection")
                        settings.put("seaartLastError", "")
                        next("awaiting_model_menu")
                    } else if (tapCreateTab()) {
                        settings.put("seaartCreateTapAt", System.currentTimeMillis().toString())
                        next("awaiting_create_navigation")
                    } else {
                        settings.put("seaartLastError", "創作タブが画面下部で見つかりません")
                    }
                }
                "awaiting_create_navigation" -> {
                    if (onCreatePage) {
                        trace("creation editor confirmed after bottom tab; proceeding to model selection")
                        settings.put("seaartLastError", "")
                        next("awaiting_model_menu")
                    } else {
                        val tapAt = settings.get("seaartCreateTapAt").toLongOrNull() ?: 0L
                        if (System.currentTimeMillis() - tapAt > 6000L) {
                            trace("navigation timeout after bottom Create tap")
                            settings.put("seaartLastError", "創作クリック後も画面遷移を確認できません")
                            next("awaiting_create")
                        }
                    }
                }
                "awaiting_model_menu" -> {
                    if (!onCreatePage) { trace("model selection waiting for editor"); return }
                    if (modelInEditorHeader && settings.get("seaartPendingLora").isBlank()) {
                        trace("matching model already active; no selector click needed")
                        settings.put("seaartModelConfirmed", settings.get("seaartPendingSince"))
                        next("awaiting_editor")
                        return
                    }
                    val target = settings.get("seaartPendingModel").ifBlank { "GR-Illustrious 3in1" }
                    // The header text alone does not prove that this run explicitly
                    // selected the desired checkpoint. Open the selector once per run.
                    val confirmed = settings.get("seaartModelConfirmed") == settings.get("seaartPendingSince")
                    if (confirmed) {
                        trace("model selection confirmed for this run: ${target.take(60)}")
                        next("awaiting_lora_menu")
                    } else {
                        val selector = nodes.firstOrNull { inHeader(it) &&
                            label(it).contains(target, true) } ?: nodes.firstOrNull { inHeader(it) &&
                            Regex("(?i).*(モデル|model|checkpoint|civedgemix|cityedgemix|illustrious).*").matches(label(it))
                        }
                        if (click(selector)) {
                            trace("opened model selector label=${selector?.let { label(it).take(80) }}")
                            next("awaiting_model_choice")
                        } else {
                            trace("model selector not clickable; header candidates=" +
                                nodes.filter { inHeader(it) && Regex("(?i).*(モデル|model|illustrious).*").matches(label(it)) }
                                    .take(4).joinToString { label(it).take(60) })
                        }
                    }
                }
                "awaiting_model_choice" -> {
                    val model = settings.get("seaartPendingModel").ifBlank { "GR-Illustrious 3in1" }
                    // A text match may be a navigation link to /models/detail, not
                    // a selector. Never click arbitrary model-name cards.
                    val choices = nodes.filter { it.isVisibleToUser && label(it).contains(model, true) && !inHeader(it) }
                    trace("model menu candidates=${choices.size}; refusing ambiguous model-name link")
                    settings.put("seaartLastError", "モデル候補が詳細ページへのリンクの可能性があります。選択操作を保留")
                    next("awaiting_model_choice_review")
                }
                "awaiting_model_choice_review" -> {
                    // Keep checking the editor header for a user-selected model.
                    trace("waiting for manual model selection in creation editor")
                    return
                }
                "awaiting_lora_menu" -> {
                    val lora = settings.get("seaartPendingLora")
                    if (lora.isBlank()) {
                        trace("LoRA not requested; continue to prompt")
                        next("awaiting_editor")
                    } else {
                        val control = nodes.firstOrNull { it.isVisibleToUser &&
                            Regex("(?i).*(lora|ローラ).*").matches(label(it)) && !it.isEditable }
                        if (click(control)) {
                            trace("opened LoRA selector")
                            next("awaiting_lora_choice")
                        } else trace("LoRA selector not found")
                    }
                }
                "awaiting_lora_choice" -> {
                    val lora = settings.get("seaartPendingLora")
                    if (lora.isBlank()) { next("awaiting_editor"); return }
                    val option = nodes.firstOrNull { it.isVisibleToUser &&
                        label(it).contains(lora, true) && !it.isEditable }
                    if (click(option)) {
                        trace("selected LoRA option: ${lora.take(60)}")
                        next("awaiting_editor")
                    } else {
                        val search = nodes.firstOrNull { it.isEditable && it.isVisibleToUser &&
                            Regex("(?i).*(search lora|loraを検索|LoRA検索).*").matches(label(it)) }
                        if (search != null) {
                            type(search, lora)
                            trace("searched LoRA: ${lora.take(60)}")
                        } else trace("LoRA option/search not found")
                    }
                }
                "awaiting_editor" -> {
                    val prompt = settings.get("seaartPendingPrompt")
                    if (prompt.length !in 20..3500) { next("invalid_prompt"); return }
                    fun isPromptField(node: AccessibilityNodeInfo): Boolean {
                        if (!node.isVisibleToUser || node.isPassword) return false
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.top < 120 || rect.height() < 30 || rect.width() < 120) return false
                        val description = label(node)
                        if (Regex("(?i).*(search|検索|url|アドレス|address|lora|model|モデル).*").matches(description)) return false
                        return node.isEditable
                    }
                    // On SeaArt mobile, the compact bottom prompt bar is not
                    // editable until it is tapped once to open the composer.
                    val candidates = nodes.filter(::isPromptField)
                    val width = service.resources.displayMetrics.widthPixels
                    val composerBar = nodes.filter { node ->
                        if (!node.isVisibleToUser || node.isEditable) return@filter false
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        val text = label(node)
                        rect.centerY() > screenHeight * 0.72f &&
                            rect.centerX() > width * 0.25f &&
                            rect.width() > width * 0.25f &&
                            (text.contains("画像") || text.contains("プロンプト") ||
                                text.contains("prompt", true) || text.length > 25)
                    }.minByOrNull { node ->
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        // Prefer actual composer controls over zero-height
                        // accessibility text fragments.
                        (if (rect.height() >= 30) 0 else 100000000) +
                            rect.width() * rect.height()
                    }
                    val focused = candidates.any { it.isFocused }
                    val lastTap = settings.get("seaartComposerTapAt").toLongOrNull() ?: 0L
                    if (!focused && composerBar != null &&
                        System.currentTimeMillis() - lastTap > 1800L) {
                        val rect = Rect()
                        composerBar.getBoundsInScreen(rect)
                        // SeaArt sometimes exposes a text node with height=0
                        // at the very bottom of the viewport. Never tap that
                        // zero-height coordinate; use a valid clickable parent
                        // or a bounded point above the navigation bar.
                        var tapRect = Rect(rect)
                        var parent = composerBar.parent
                        repeat(4) {
                            if (tapRect.height() >= 30) return@repeat
                            val parentRect = Rect()
                            parent?.getBoundsInScreen(parentRect)
                            if (parentRect.height() >= 30 && parentRect.width() > width * 0.25f) {
                                tapRect = parentRect
                            }
                            parent = parent?.parent
                        }
                        val tapX = (tapRect.left + tapRect.width() * 0.55f)
                            .coerceIn(width * 0.25f, width * 0.85f)
                        val tapY = if (tapRect.height() >= 30 && tapRect.centerY() < screenHeight - 60)
                            tapRect.centerY().toFloat()
                        else (screenHeight * 0.90f).coerceAtMost(screenHeight - 100f)
                        val path = Path().apply { moveTo(tapX, tapY) }
                        val gesture = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                            .build()
                        val tapped = service.dispatchGesture(gesture, null, null)
                        trace("compact composer one-tap dispatched=$tapped original=${rect.flattenToString()} target=${tapRect.flattenToString()} point=$tapX,$tapY")
                        if (tapped) {
                            settings.put("seaartComposerTapAt", System.currentTimeMillis().toString())
                            return
                        }
                    }
                    val promptNode = candidates.maxByOrNull { node ->
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        val value = node.text?.toString().orEmpty()
                        (if (rect.centerY() > screenHeight * 0.52f) 100000 else 0) +
                            (if (label(node).contains("prompt", true) ||
                                label(node).contains("プロンプト") ||
                                label(node).contains("何を創造")) 100000 else 0) +
                            (if (value.isNotBlank()) 10000 else 0) + rect.width()
                    }
                    fun matchesPrompt(value: String): Boolean {
                        val normalized = value.trim()
                        return normalized == prompt.trim() ||
                            (prompt.length >= 80 && normalized.length >= 60 &&
                                normalized.startsWith(prompt.take(60)) &&
                                (normalized.length >= prompt.length || normalized.endsWith("…")))
                    }
                    if (promptNode == null) {
                        trace("prompt field missing; visible editable candidates=0")
                        return
                    }
                    val current = promptNode.text?.toString().orEmpty()
                    if (matchesPrompt(current)) {
                        trace("prompt already visible in composer; proceed")
                        next("awaiting_generate")
                        return
                    }
                    // Contenteditable fields in Chrome sometimes reject SET_TEXT
                    // until focused. Never type into the address/search bar.
                    if (!promptNode.isFocused) {
                        val tapped = click(promptNode)
                        trace("prompt editable tap=$tapped; waiting for focus")
                        if (tapped) return
                    }
                    promptNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val entered = type(promptNode, prompt)
                    trace("prompt SET_TEXT=$entered currentLength=${current.length} targetLength=${prompt.length}")
                    if (entered) next("awaiting_prompt_confirmation")
                    else {
                        click(promptNode)
                        trace("prompt field focused for retry")
                    }
                }
                "awaiting_prompt_confirmation" -> {
                    val prompt = settings.get("seaartPendingPrompt")
                    val verified = nodes.any { node ->
                        if (!node.isEditable || !node.isVisibleToUser || node.isPassword) false
                        else {
                            val value = node.text?.toString()?.trim().orEmpty()
                            value == prompt.trim() ||
                                (prompt.length >= 80 && value.length >= 60 &&
                                    value.startsWith(prompt.take(60)) &&
                                    (value.length >= prompt.length || value.endsWith("…")))
                        }
                    }
                    if (verified) {
                        trace("prompt confirmed in composer")
                        next("awaiting_generate")
                    } else {
                        trace("prompt not confirmed in accessibility tree; retry editor without generating")
                        next("awaiting_editor")
                    }
                }
                "awaiting_generate" -> {
                    // The purple SeaArt generate control is often rendered as
                    // "x 1 ⚡ 32.4" rather than a button named "生成".
                    // Restrict matching to the lower composer, not result cards.
                    val width = service.resources.displayMetrics.widthPixels
                    fun generateBounds(node: AccessibilityNodeInfo): Rect {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        return rect
                    }
                    fun inGenerateArea(node: AccessibilityNodeInfo): Boolean {
                        if (!node.isVisibleToUser || node.isEditable) return false
                        val rect = generateBounds(node)
                        return !rect.isEmpty && rect.centerY() > screenHeight * 0.40f &&
                            rect.centerX() > width * 0.45f && rect.left >= 0 &&
                            rect.top >= 0 && rect.width() >= 20 && rect.height() >= 20
                    }
                    // Credit cost varies with model, plan and settings.
                    // Prefer a visible xN + lightning/credit label without
                    // depending on any fixed number or decimal value.
                    val creditPattern = Regex("""(?is).*(?:[x×Ｘ]\s*\d+.*(?:⚡|クレジット|credits?|\d+[.,]\d+)|(?:⚡|クレジット|credits?)\s*\d+.*[x×Ｘ]\s*\d+).*""")
                    val namedPattern = Regex("(?i).*(生成する|今すぐ生成|generate|start generating).*")
                    val labeled = nodes.filter(::inGenerateArea).filter { node ->
                        val value = label(node)
                        creditPattern.matches(value) || namedPattern.matches(value)
                    }
                    // Accessibility does not expose the actual purple fill
                    // color. If the text is missing, choose only a unique
                    // compact clickable control in the lower-right composer.
                    val fallback = nodes.filter(::inGenerateArea).filter { node ->
                        val rect = generateBounds(node)
                        val value = label(node)
                        val isControl = node.isClickable || node.parent?.isClickable == true
                        val compact = rect.width() in (width * 0.12f).toInt()..(width * 0.62f).toInt() &&
                            rect.height() in 28..(screenHeight * 0.13f).toInt()
                        val unrelated = Regex("(?i).*(download|ダウンロード|戻る|back|share|共有|lora|モデル|設定|メニュー|閉じる|cancel|キャンセル).*").matches(value)
                        isControl && compact && !unrelated
                    }.distinctBy { generateBounds(it).flattenToString() }
                    // SeaArt can expose "x 1" and "⚡ 32.4" as
                    // separate accessibility nodes inside one purple button.
                    // Group adjacent text fragments rather than requiring one
                    // node to contain the entire credit label.
                    val fragments = nodes.filter(::inGenerateArea)
                    val multiplier = Regex("""(?i).*?[x×Ｘ]\\s*\\d+.*""")
                    val lightning = Regex(""".*(?:⚡|クレジット|credits?).*""", RegexOption.IGNORE_CASE)
                    val paired = fragments.filter { multiplier.matches(label(it)) }
                        .flatMap { countNode ->
                            val countRect = generateBounds(countNode)
                            fragments.filter { priceNode ->
                                val priceRect = generateBounds(priceNode)
                                lightning.matches(label(priceNode)) &&
                                    kotlin.math.abs(countRect.centerY() - priceRect.centerY()) <
                                        kotlin.math.max(countRect.height(), priceRect.height()) * 2 + 32 &&
                                    kotlin.math.abs(countRect.centerX() - priceRect.centerX()) < width * 0.5f
                            }.map { priceNode ->
                                val combined = Rect(generateBounds(countNode))
                                combined.union(generateBounds(priceNode))
                                combined
                            }
                        }.filter { it.width() < width * 0.65f }
                    val generate = labeled.minByOrNull { node ->
                        val rect = generateBounds(node)
                        rect.width() * rect.height()
                    } ?: fallback.singleOrNull()
                    val pairedBounds = if (generate == null) paired.minByOrNull { it.width() * it.height() } else null
                    if (generate == null && pairedBounds == null) {
                        // The compact bar can hide the purple generate button.
                        // Open the prompt composer before trying again, rather
                        // than mistaking the round up-arrow for Generate.
                        val now = System.currentTimeMillis()
                        val last = settings.get("seaartGenerateComposerTapAt").toLongOrNull() ?: 0L
                        val promptBar = nodes.filter { node ->
                            val rect = generateBounds(node)
                            node.isVisibleToUser && !node.isEditable &&
                                rect.centerY() > screenHeight * 0.72f &&
                                rect.width() > width * 0.25f &&
                                (label(node).contains("画像") ||
                                    label(node).contains("prompt", true) ||
                                    label(node).contains("プロンプト"))
                        }.firstOrNull()
                        if (promptBar != null && now - last > 2500L) {
                            val rect = generateBounds(promptBar)
                            val x = (rect.left + rect.width() * 0.65f).coerceIn(width * 0.3f, width * 0.85f)
                            val y = if (rect.height() >= 30 && rect.centerY() < screenHeight - 60)
                                rect.centerY().toFloat()
                            else (screenHeight * 0.90f).coerceAtMost(screenHeight - 100f)
                            val path = Path().apply { moveTo(x, y) }
                            val dispatched = service.dispatchGesture(
                                GestureDescription.Builder()
                                    .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                                    .build(), null, null)
                            trace("generate hidden; opening composer tap=$dispatched at=$x,$y")
                            if (dispatched) settings.put("seaartGenerateComposerTapAt", now.toString())
                        } else trace("generate not found: labeled=${labeled.size} fallback=${fallback.size} composer=${promptBar != null}")
                        return
                    }
                    if (pairedBounds != null) {
                        val x = pairedBounds.centerX().toFloat()
                        val y = pairedBounds.centerY().toFloat()
                        val path = Path().apply { moveTo(x, y) }
                        val dispatched = service.dispatchGesture(
                            GestureDescription.Builder()
                                .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
                                .build(), null, null)
                        trace("generate paired-credit tap=$dispatched at=$x,$y bounds=${pairedBounds.flattenToString()}")
                        if (dispatched) {
                            settings.put("seaartPendingSince", System.currentTimeMillis().toString())
                            next("awaiting_download")
                        }
                        return
                    }
                    val selected = generate ?: return
                    trace("generate match=" + (if (labeled.isNotEmpty()) "variable-credit-label" else "lower-right-control-fallback"))
                    val rect = generateBounds(selected)
                    trace("purple generate candidate label=${label(selected).take(90)} bounds=${rect.flattenToString()} clickable=${selected.isClickable}")
                    val clicked = click(selected)
                    if (clicked) {
                        trace("purple generate ACTION_CLICK accepted; awaiting generation")
                        settings.put("seaartPendingSince", System.currentTimeMillis().toString())
                        next("awaiting_download")
                    } else {
                        // Chromium sometimes exposes a text-only child without
                        // clickable ancestors. Tap the actual measured bounds,
                        // never a hard-coded screen coordinate.
                        val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
                        val gesture = GestureDescription.Builder()
                            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                            .build()
                        val dispatched = service.dispatchGesture(gesture, null, null)
                        trace("purple generate gesture dispatched=$dispatched")
                        if (dispatched) {
                            settings.put("seaartPendingSince", System.currentTimeMillis().toString())
                            next("awaiting_download")
                        } else next("generation_click_failed")
                    }
                }
                "awaiting_download" -> {
                    if (nodes.any { Regex("(?i).*(generating|生成中|作成中|処理中).*").matches(label(it)) }) return
                    val download = find("^(ダウンロード|画像をダウンロード|Download|Download image|Save image)$")
                    if (download != null) {
                        next("download_requested")
                        if (!click(download)) next("download_click_failed")
                    }
                }
            }
        } finally { root.recycle() }
    }
}
