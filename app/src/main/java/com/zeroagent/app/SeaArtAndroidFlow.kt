package com.zeroagent.app

import android.accessibilityservice.AccessibilityService
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
                val parent = node.parent
                return parent?.isClickable == true && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
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

            when (state) {
                "awaiting_create" -> {
                    // A distinct Create/創作 entry; don't confuse it with Generate.
                    if (click(find("^(創作|Create|AI創作|AI Creation)$"))) next("awaiting_model_menu")
                }
                "awaiting_model_menu" -> {
                    // Only open an explicitly labelled model selector.
                    if (click(find("^(モデル|Model|モデルを選択|Select model|Checkpoint)$"))) next("awaiting_model_choice")
                }
                "awaiting_model_choice" -> {
                    val model = settings.get("seaartPendingModel").ifBlank { "GR-Illustrious 3in1" }
                    if (click(find("^" + Regex.escape(model) + "$"))) {
                        next(if (settings.get("seaartPendingLora").isBlank()) "awaiting_editor" else "awaiting_lora_menu")
                    } else {
                        // Search inside a clearly identified model-search field only.
                        val search = nodes.firstOrNull { it.isEditable && it.isVisibleToUser &&
                            Regex("(?i).*(search model|モデルを検索).*").matches(label(it)) }
                        if (search != null) type(search, model)
                    }
                }
                "awaiting_lora_menu" -> {
                    if (click(find("^(LoRA|LoRAを追加|Add LoRA|Select LoRA|LoRAを選択)$"))) next("awaiting_lora_choice")
                }
                "awaiting_lora_choice" -> {
                    val lora = settings.get("seaartPendingLora")
                    if (lora.isBlank()) { next("awaiting_editor"); return }
                    if (click(find("^" + Regex.escape(lora) + "$"))) {
                        next("awaiting_editor")
                    } else {
                        val search = nodes.firstOrNull { it.isEditable && it.isVisibleToUser &&
                            Regex("(?i).*(search lora|loraを検索|LoRA検索).*").matches(label(it)) }
                        if (search != null) type(search, lora)
                    }
                }
                "awaiting_editor" -> {
                    val prompt = settings.get("seaartPendingPrompt")
                    if (prompt.length !in 20..3500) { next("invalid_prompt"); return }
                    // Avoid typing into Chrome's address bar or an unrelated search field.
                    val promptNode = nodes.firstOrNull { it.isEditable && it.isVisibleToUser &&
                        !it.isPassword && Regex("(?i).*(prompt|プロンプト|入力して|describe your image).*")
                            .matches(label(it)) }
                    if (type(promptNode, prompt)) next("awaiting_generate")
                }
                "awaiting_generate" -> {
                    val generate = find("^(生成|生成する|Generate|今すぐ生成|Start generating)$")
                    if (generate != null) {
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
