package com.zeroagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Stores observable action/outcome pairs, not credentials, cookies, or screenshots. */
class EpisodeMemory(context: Context) {
    data class Episode(
        val id: String, val time: Long, val host: String, val goal: String,
        val action: String, val before: String, val after: String, val outcome: String
    )
    private val file = File(context.filesDir, "agent_episodes.json")
    private val allowed = setOf("success", "failed", "unknown")
    @Synchronized
    private fun readAll(): List<Episode> = try {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            Episode(o.optString("id"), o.optLong("time"), o.optString("host"),
                o.optString("goal"), o.optString("action"), o.optString("before"),
                o.optString("after"), o.optString("outcome"))
        }
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun record(host: String, goal: String, action: String, before: String, after: String, outcome: String) {
        require(outcome in allowed)
        val entry = Episode(UUID.randomUUID().toString(), System.currentTimeMillis(),
            host.take(120), goal.take(250), action.take(250),
            before.take(160), after.take(160), outcome)
        val data = (readAll() + entry).takeLast(200)
        val json = JSONArray()
        data.forEach { e ->
            json.put(JSONObject().put("id", e.id).put("time", e.time)
                .put("host", e.host).put("goal", e.goal).put("action", e.action)
                .put("before", e.before).put("after", e.after).put("outcome", e.outcome))
        }
        file.writeText(json.toString())
    }

    @Synchronized
    fun recall(host: String, goal: String, limit: Int = 5): List<Episode> =
        readAll().filter { it.host == host || it.goal.contains(goal.take(30), ignoreCase = true) }
            .sortedWith(compareByDescending<Episode> { it.host == host }
                .thenByDescending { it.time }).take(limit)

    @Synchronized
    fun list(): List<Episode> = readAll().sortedByDescending { it.time }

    @Synchronized
    fun clear() { file.delete() }
}
