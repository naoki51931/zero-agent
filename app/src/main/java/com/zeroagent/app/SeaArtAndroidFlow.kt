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

    suspend fun advance() {
        val state = settings.get("seaartPendingState")
        if (state.isBlank()) return
        val since = settings.get("seaartPendingSince").toLongOrNull() ?: 0L
        if (since <= 0 || System.currentTimeMillis() - since > 15 * 60_000L) {
            settings.put("seaartPendingState", ""); return
        }
        val root = service.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != "com.android.chrome") return
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 24 || nodes.size >= 300) return
                nodes.add(node)
                for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
            if (nodes.none { label(it).contains("SeaArt", true) || label(it).contains("seaart.ai", true) }) return

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
            fun next(value: String) { settings.put("seaartPendingState", value) }

            val onCreatePage = nodes.any { label(it).contains("/create/image", true) }
            fun tapCreateTab(): Boolean {
                val height = service.resources.displayMetrics.heightPixels
                val width = service.resources.displayMetrics.widthPixels
                val tabs = nodes.filter {
                    it.isVisibleToUser &&
                        Regex("^(創作|Create|AI創作|AI Creation)$", RegexOption.IGNORE_CASE).matches(label(it))
                }.mapNotNull { node ->
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.isEmpty || rect.centerY() < height * 0.65f ||
                        rect.centerX() !in 0..width || rect.centerY() !in 0..height) null
                    else node to rect
                }.sortedByDescending { it.second.centerY() }
                val chosen = tabs.firstOrNull() ?: return false
                if (click(chosen.first)) return true
                // Some Chrome web navigation elements expose no ACTION_CLICK. Use a
                // bounded gesture at the actual accessibility node, not a hardcoded coordinate.
                val rect = chosen.second
                val path = Path().apply { moveTo(rect.centerX().toFloat(), rect.centerY().toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build()
                return service.dispatchGesture(gesture, null, null)
            }
            when (state) {
                "awaiting_create" -> {
                    if (onCreatePage) {
                        next("awaiting_editor")
                    } else if (tapCreateTab()) {
                        settings.put("seaartCreateTapAt", System.currentTimeMillis().toString())
                        next("awaiting_create_navigation")
                    } else {
                        settings.put("seaartLastError", "創作タブが画面下部で見つかりません")
                    }
                }
                "awaiting_create_navigation" -> {
                    if (onCreatePage) {
                        settings.put("seaartLastError", "")
                        next("awaiting_editor")
                    } else {
                        val tapAt = settings.get("seaartCreateTapAt").toLongOrNull() ?: 0L
                        if (System.currentTimeMillis() - tapAt > 6000L) {
                            settings.put("seaartLastError", "創作クリック後も画面遷移を確認できません")
                            next("awaiting_create")
                        }
                    }
                }
                "awaiting_model_menu" -> {
                    // Only open an explicitly labelled model selector.
                    val target = settings.get("seaartPendingModel").ifBlank { "GR-Illustrious 3in1" }
                    if (nodes.any { it.isVisibleToUser && label(it).contains(target, true) }) {
                        next(if (settings.get("seaartPendingLora").isBlank()) "awaiting_generate" else "awaiting_lora_menu")
                    } else {
                        val selected = nodes.firstOrNull {
                            it.isVisibleToUser && Regex("(?i).*(civedgemix|checkpoint|base model|使用モデル).*").matches(label(it))
                        }
                        if (click(selected ?: find("^(モデル|Model|モデルを選択|Select model|Checkpoint)$"))) next("awaiting_model_choice")
                    }
                }
                "awaiting_model_choice" -> {
                    val model = settings.get("seaartPendingModel").ifBlank { "GR-Illustrious 3in1" }
                    if (click(find("^" + Regex.escape(model) + ".*$"))) {
                        next(if (settings.get("seaartPendingLora").isBlank()) "awaiting_generate" else "awaiting_lora_menu")
                    } else {
                        // Search inside a clearly identified model-search field only.
                        val search = nodes.firstOrNull { it.isEditable && it.isVisibleToUser &&
                            Regex("(?i).*(search model|モデルを検索).*").matches(label(it)) }
                        if (search != null) type(search, model)
                    }
                }
                "awaiting_lora_menu" -> {
                    val control = nodes.firstOrNull {
                        it.isVisibleToUser && Regex("(?i).*(lora|ローラ).*").matches(label(it)) && !it.isEditable
                    }
                    if (click(control)) next("awaiting_lora_choice")
                }
                "awaiting_lora_choice" -> {
                    val lora = settings.get("seaartPendingLora")
                    if (lora.isBlank()) { next("awaiting_generate"); return }
                    if (click(find("^" + Regex.escape(lora) + ".*$"))) {
                        next("awaiting_generate")
                    } else {
                        val search = nodes.firstOrNull { it.isEditable && it.isVisibleToUser &&
                            Regex("(?i).*(search lora|loraを検索|LoRA検索).*").matches(label(it)) }
                        if (search != null) type(search, lora)
                    }
                }
                "awaiting_editor" -> {
                    val prompt = settings.get("seaartPendingPrompt")
                    if (prompt.length !in 20..3500) { next("invalid_prompt"); return }
                    // The mobile SeaArt prompt field may expose only its existing text, not a label.
                    val candidates = nodes.filter { node ->
                        if (!node.isEditable || !node.isVisibleToUser || node.isPassword) return@filter false
                        val bounds = android.graphics.Rect()
                        node.getBoundsInScreen(bounds)
                        bounds.top > 120 && bounds.height() > 35 &&
                            !Regex("(?i).*(search|検索|url|アドレス|address|lora|model|モデル).*")
                                .matches(label(node))
                    }
                    val promptNode = candidates.firstOrNull {
                        Regex("(?i).*(prompt|プロンプト|入力して|describe your image).*")
                            .matches(label(it))
                    } ?: candidates.singleOrNull()
                    if (type(promptNode, prompt)) next("awaiting_model_menu")
                }
                "awaiting_generate" -> {
                    val generate = find("^(生成|生成する|Generate|今すぐ生成|Start generating)$")
                        ?: nodes.firstOrNull {
                            it.isVisibleToUser && Regex("(?is).*x\\s*1\\s*.*(⚡|\\d+[.]\\d+).*").matches(label(it)) &&
                                (it.isClickable || it.parent?.isClickable == true)
                        }
                    if (generate != null) {
                        settings.put("seaartPendingSince", System.currentTimeMillis().toString())
                        next("awaiting_download")
                        if (!click(generate)) next("generation_click_failed")
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
