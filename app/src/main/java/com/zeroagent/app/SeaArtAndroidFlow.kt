package com.zeroagent.app

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Conservative SeaArt browser workflow. Only acts on a user-started draft,
 * inside Chrome and on a screen containing SeaArt branding.
 * UI labels vary; stop rather than clicking an unknown control.
 */
class SeaArtAndroidFlow(private val service: AccessibilityService) {
    private val settings get() = LocalSettings(service)

    suspend fun advance() {
        val state = settings.get("seaartPendingState")
        if (state.isBlank()) return
        val since = settings.get("seaartPendingSince").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - since > 5 * 60_000L) {
            settings.put("seaartPendingState", ""); return
        }
        val root = service.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != "com.android.chrome") return
            val nodes = mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 24 || nodes.size >= 300) return
                nodes.add(node)
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { walk(it, depth + 1) }
                }
            }
            walk(root, 0)
            val labels = nodes.map { listOfNotNull(it.text?.toString(), it.contentDescription?.toString()).joinToString(" ") }
            if (labels.none { it.contains("SeaArt", true) || it.contains("seaart.ai", true) }) return
            if (state == "awaiting_editor") {
                val edit = nodes.firstOrNull { it.isEditable && !it.isPassword && it.isVisibleToUser &&
                    (it.text?.length ?: 0) < 3000 } ?: return
                val prompt = settings.get("seaartPendingPrompt")
                if (prompt.length !in 20..3500) { settings.put("seaartPendingState", ""); return }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, prompt)
                }
                if (edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                    settings.put("seaartPendingState", "awaiting_generate")
                }
                return
            }
            if (state == "awaiting_generate") {
                val button = nodes.firstOrNull { node ->
                    val name = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                        .joinToString(" ").trim()
                    node.isVisibleToUser && node.isClickable &&
                        Regex("^(生成|生成する|Generate|Create|今すぐ生成)$", RegexOption.IGNORE_CASE).matches(name)
                } ?: return
                // Only a single generation request. A failure never retries automatically.
                settings.put("seaartPendingState", "generation_requested")
                if (!button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    settings.put("seaartPendingState", "generation_click_failed")
                }
            }
        } finally { root.recycle() }
    }
}
