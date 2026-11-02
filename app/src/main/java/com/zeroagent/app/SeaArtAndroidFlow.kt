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
        val state = settings.get("seaartPendingState")
        if (state.isBlank()) return
        val since = settings.get("seaartPendingSince").toLongOrNull() ?: 0L
        if (since <= 0 || System.currentTimeMillis() - since > 15 * 60_000L) {
            settings.put("seaartPendingState", ""); return
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
            val modelInEditorHeader = onCreatePage && nodes.any { node ->
                inHeader(node) && label(node).contains(requestedModel, ignoreCase = true)
            }
            if (modelInEditorHeader && state in setOf(
                    "awaiting_model_menu", "awaiting_model_choice",
                    "awaiting_model_choice_review", "awaiting_model_verification")) {
                trace("manual model recognized in creation editor: " + requestedModel.take(70) +
                    "; skipping model menu")
                settings.put("seaartModelConfirmed", settings.get("seaartPendingSince"))
                settings.put("seaartLastError", "")
                next("awaiting_lora_menu")
                return
            }
            // Recovery: if the editor has loaded while the previous tab-click
            // state is still pending, jump directly to model selection.
            if (onCreatePage && state in setOf("awaiting_create", "awaiting_create_navigation")) {
                trace("editor recovery: $state -> awaiting_model_menu")
                settings.put("seaartLastError", "")
                next("awaiting_model_menu")
                return
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
                    val candidates = nodes.filter { node ->
                        if (!node.isEditable || !node.isVisibleToUser || node.isPassword) return@filter false
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        bounds.top > 120 && bounds.height() > 35 &&
                            !Regex("(?i).*(search|検索|url|アドレス|address|lora|model|モデル).*")
                                .matches(label(node))
                    }
                    // SeaArt's mobile composer is a bottom sheet. Two editable
                    // nodes are commonly exposed; choose the large visible
                    // composer field in the lower half, never the search box.
                    val lowerFields = candidates.filter { node ->
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        rect.centerY() > screenHeight * 0.55f &&
                            rect.width() > service.resources.displayMetrics.widthPixels * 0.40f
                    }
                    val promptNode = lowerFields.maxByOrNull { node ->
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        val content = node.text?.toString().orEmpty()
                        (if (content.isNotBlank()) 100000 else 0) +
                            rect.width() + rect.height() +
                            (if (label(node).contains("prompt", true) ||
                                label(node).contains("プロンプト")) 100000 else 0)
                    } ?: candidates.firstOrNull {
                        Regex("(?i).*(prompt|プロンプト|入力して|describe your image).*")
                            .matches(label(it))
                    } ?: candidates.singleOrNull()
                    trace("prompt candidate chosen=" + (promptNode?.let { node ->
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        "text=" + label(node).take(70) + " bounds=" + rect.flattenToString()
                    } ?: "none") + " lowerFields=" + lowerFields.size)
                    if (promptNode?.text?.toString() == prompt) {
                        trace("prompt verified in editor")
                        next("awaiting_generate")
                    } else if (type(promptNode, prompt)) {
                        trace("prompt input sent; awaiting confirmation")
                        next("awaiting_prompt_confirmation")
                    } else trace("prompt editable field not found candidates=${candidates.size} details=" +
                        candidates.take(5).joinToString { node ->
                            val rect = Rect()
                            node.getBoundsInScreen(rect)
                            "text=${label(node).take(60)} bounds=${rect.flattenToString()}"
                        })
                }
                "awaiting_prompt_confirmation" -> {
                    val prompt = settings.get("seaartPendingPrompt")
                    if (nodes.any { it.isEditable && it.isVisibleToUser &&
                            it.text?.toString() == prompt }) {
                        trace("prompt confirmed")
                        next("awaiting_generate")
                    } else {
                        trace("prompt not confirmed; retry editor")
                        next("awaiting_editor")
                    }
                }
                "awaiting_generate" -> {
                    val generate = find("^(生成|生成する|Generate|今すぐ生成|Start generating)$")
                        ?: nodes.firstOrNull {
                            it.isVisibleToUser && Regex("(?is).*x\\s*1\\s*.*(⚡|\\d+[.]\\d+).*").matches(label(it)) &&
                                (it.isClickable || it.parent?.isClickable == true)
                        }
                    if (generate != null) {
                        settings.put("seaartPendingSince", System.currentTimeMillis().toString())
                        if (click(generate)) {
                            trace("generation button clicked")
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
