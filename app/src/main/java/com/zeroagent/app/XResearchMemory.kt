package com.zeroagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Device-local, bounded candidate observations; not verified X metrics. */
class XResearchMemory(context: Context) {
    private val prefs = context.getSharedPreferences("x_research_v1", Context.MODE_PRIVATE)
    private val likes = Regex("""(?i)(?:いいね|likes?|♡|♥)\s*[:：]?\s*([\d,.]+)\s*([kKmM万]?)|([\d,.]+)\s*([kKmM万]?)\s*(?:いいね|likes?)""")
    private fun number(raw: String, suffix: String): Long? {
        val n = raw.replace(",", "").toDoubleOrNull() ?: return null
        val factor = when (suffix.lowercase()) { "k" -> 1000.0; "m" -> 1000000.0; "万" -> 10000.0; else -> 1.0 }
        return (n * factor).toLong().takeIf { it in 0..1000000000L }
    }
    private fun read(): JSONArray = try { JSONArray(prefs.getString("entries", "[]")) } catch (_: Exception) { JSONArray() }

    @Synchronized
    fun collect(term: String, labels: List<String>): Int {
        val old = read()
        val seen = mutableSetOf<String>()
        val rows = mutableListOf<JSONObject>()
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (seen.add(item.optString("term") + "|" + item.optString("text"))) rows.add(item)
        }
        var added = 0
        for (label in labels) {
            val clean = label.replace(Regex("""\s+"""), " ").take(280)
            if (clean.length < 12 || Regex("(?i)password|パスワード|認証コード|ログイン|メールアドレス").containsMatchIn(clean)) continue
            val match = likes.find(clean) ?: continue
            val raw = match.groups[1]?.value ?: match.groups[3]?.value.orEmpty()
            val suffix = match.groups[2]?.value ?: match.groups[4]?.value.orEmpty()
            val count = number(raw, suffix) ?: continue
            if (seen.add(term + "|" + clean)) {
                rows.add(JSONObject().put("term", term.take(60)).put("text", clean)
                    .put("likes", count).put("at", System.currentTimeMillis()))
                added++
            }
        }
        val json = JSONArray()
        rows.takeLast(200).forEach { json.put(it) }
        prefs.edit().putString("entries", json.toString()).apply()
        return added
    }

    @Synchronized
    fun count(term: String): Int {
        val a = read()
        return (0 until a.length()).count { a.optJSONObject(it)?.optString("term") == term }
    }

    @Synchronized
    fun summary(term: String): String {
        val a = read()
        val top = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { it.optString("term") == term }
            .sortedByDescending { it.optLong("likes") }.take(3)
        return if (top.isEmpty()) "いいね数付きの候補は未収集です" else
            "候補 " + count(term) + " 件（画面表示の推定値）\n" +
                top.joinToString("\n") { "♡" + it.optLong("likes") + ": " + it.optString("text").take(95) }
    }
}
