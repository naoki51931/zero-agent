package com.zeroagent.app

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Conservative collector for public X posts visible in the accessibility tree.
 * Only accepts a post when an explicit status URL and a like-count label coexist.
 * It never reads editable fields, credentials, cookies, or hidden screen content.
 */
object XAccessibilityCollector {
    private val statusPattern = Regex("https?://(?:www\\.)?(?:x\\.com|twitter\\.com)/([^/\\s]+)/status/(\\d+)", RegexOption.IGNORE_CASE)
    private val likesPattern = Regex("(?:^|\\s)([\\d,.]+\\s*[KMB万億]?)\\s*(?:likes?|いいね)(?:\\s|$)", RegexOption.IGNORE_CASE)
    private val likesPrefix = Regex("(?:likes?|いいね)\\s*[:：]?\\s*([\\d,.]+\\s*[KMB万億]?)", RegexOption.IGNORE_CASE)

    fun collect(root: AccessibilityNodeInfo): List<PopularPostMemory.Post> {
        val output = linkedMapOf<String, PopularPostMemory.Post>()
        var examined = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 24 || examined++ > 900 || !node.isVisibleToUser || node.isPassword) return
            val text = if (node.isEditable) "" else node.text?.toString().orEmpty()
            val description = if (node.isEditable) "" else node.contentDescription?.toString().orEmpty()
            val own = (text + " " + description).take(3000)
            // Do not combine labels from unrelated cards: require URL and count in the same node.
            val status = statusPattern.find(own)
            val count = likesPattern.find(own)?.groupValues?.get(1)
                ?: likesPrefix.find(own)?.groupValues?.get(1)
            if (status != null && count != null) {
                val likes = XPostExtractor.parseLikes(count)
                if (likes != null) {
                    val id = status.groupValues[2]
                    val url = "https://x.com/" + status.groupValues[1] + "/status/" + id
                    output[id] = PopularPostMemory.Post(id, url, status.groupValues[1], "", likes)
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        walk(root, 0)
        return output.values.toList()
    }
}
