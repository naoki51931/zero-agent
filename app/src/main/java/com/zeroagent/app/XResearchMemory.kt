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
    fun exportJson(): JSONObject = JSONObject()
        .put("format", "zero-agent-x-memory-v1")
        .put("entries", read())

    /** Validate before merging; these are always unverified UI observations. */
    @Synchronized
    fun importJson(data: JSONObject): Int {
        require(data.optString("format") == "zero-agent-x-memory-v1") { "Invalid memory backup" }
        val input = data.optJSONArray("entries") ?: error("Missing memory entries")
        require(input.length() <= 2000) { "Too many memory entries" }
        val merged = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        val original = read()
        for (i in 0 until original.length()) {
            val row = original.optJSONObject(i) ?: continue
            if (seen.add(row.optString("term") + "|" + row.optString("text"))) merged.add(row)
        }
        var added = 0
        for (i in 0 until input.length()) {
            val row = input.optJSONObject(i) ?: error("Invalid memory row")
            val term = row.optString("term")
            val value = row.optString("text")
            val likes = row.optLong("likes", -1)
            require(term.isNotBlank() && term.length <= 60 && value.length in 12..280 &&
                likes in 0..1000000000L) { "Invalid memory value" }
            if (seen.add(term + "|" + value)) {
                merged.add(JSONObject().put("term", term).put("text", value)
                    .put("likes", likes).put("at", row.optLong("at", System.currentTimeMillis())))
                added++
            }
        }
        val result = JSONArray()
        merged.takeLast(200).forEach { result.put(it) }
        check(prefs.edit().putString("entries", result.toString()).commit()) { "Memory save failed" }
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
    /** Display persisted observations regardless of the currently edited objective. */
    @Synchronized
    fun summarySaved(): String {
        val rows = read()
        val items = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .sortedByDescending { it.optLong("likes") }
        if (items.isEmpty()) return "いいね数付きの候補はまだ保存されていません。\\n" +
            "スクリーンショット証拠は別に保存されている場合があります。"
        return "保存済み候補 " + items.size + " 件（推定値・未検証）\\n\\n" +
            items.take(30).joinToString("\\n\\n") {
                "[" + it.optString("term") + "] ♡" + it.optLong("likes") +
                    "\\n" + it.optString("text").take(220)
            }
    }

    @Synchronized
    fun summaryAll(terms: List<String>): String {
        val rows = read()
        val matches = (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
            .filter { it.optString("term") in terms }
            .sortedByDescending { it.optLong("likes") }
        if (matches.isEmpty()) return "検索したキーワード: " + terms.joinToString(" / ") +
            "\nいいね数付き候補はまだ取得できていません"
        return "検索: " + terms.joinToString(" / ") +
            "\n候補 " + matches.size + " 件（画面表示からの推定・未検証）\n" +
            matches.take(8).joinToString("\n") {
                "[" + it.optString("term") + "] ♡" + it.optLong("likes") +
                    " " + it.optString("text").take(100)
            }
    }

}
