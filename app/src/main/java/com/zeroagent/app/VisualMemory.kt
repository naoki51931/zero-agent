package com.zeroagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Stores textual observations, not raw screenshots. Screenshots retain their separate 24h policy.
 * Short-term observations expire after one day; long-term notes require explicit promotion.
 */
class VisualMemory(context: Context) {
    private val file = File(context.filesDir, "visual_memory.json")
    private val maxShort = 60
    private val maxLong = 100

    data class Note(val id: String, val url: String, val stage: String, val observation: String, val time: Long, val longTerm: Boolean)

    @Synchronized
    private fun load(): MutableList<Note> {
        if (!file.exists()) return mutableListOf()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                Note(o.optString("id"), o.optString("url"), o.optString("stage"),
                    o.optString("observation"), o.optLong("time"), o.optBoolean("longTerm"))
            }.toMutableList()
        } catch (_: Exception) { mutableListOf() }
    }

    @Synchronized
    private fun persist(notes: List<Note>) {
        val array = JSONArray()
        notes.forEach { n ->
            array.put(JSONObject().put("id", n.id).put("url", n.url).put("stage", n.stage)
                .put("observation", n.observation).put("time", n.time).put("longTerm", n.longTerm))
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            file.writeText(array.toString())
            temp.delete()
        }
    }

    @Synchronized
    fun remember(url: String, stage: String, observation: String): Note {
        val now = System.currentTimeMillis()
        val notes = load().filter { it.longTerm || now - it.time < 86_400_000L }.toMutableList()
        val note = Note(UUID.randomUUID().toString(), url.take(500), stage.take(250),
            observation.take(1800), now, false)
        notes.add(note)
        val trimmed = notes.filter { it.longTerm }.takeLast(maxLong) +
            notes.filterNot { it.longTerm }.takeLast(maxShort)
        persist(trimmed)
        return note
    }

    @Synchronized
    fun list(longTerm: Boolean? = null): List<Note> {
        val now = System.currentTimeMillis()
        val all = load()
        val kept = all.filter { it.longTerm || now - it.time < 86_400_000L }
        if (kept.size != all.size) persist(kept)
        return kept.filter { longTerm == null || it.longTerm == longTerm }.sortedByDescending { it.time }
    }

    @Synchronized
    fun promote(id: String): Boolean {
        val notes = list().toMutableList()
        val index = notes.indexOfFirst { it.id == id }
        if (index < 0) return false
        notes[index] = notes[index].copy(longTerm = true)
        persist(notes.filter { it.longTerm }.takeLast(maxLong) + notes.filterNot { it.longTerm })
        return true
    }

    @Synchronized
    fun delete(id: String) { persist(list().filterNot { it.id == id }) }

    fun recall(query: String, limit: Int = 5): List<Note> {
        val terms = query.lowercase().split(Regex("[\\s、。・]+")).filter { it.length > 1 }.take(15)
        return list().sortedWith(compareByDescending<Note> { n ->
            terms.count { term -> (n.url + " " + n.stage + " " + n.observation).lowercase().contains(term) }
        }.thenByDescending { it.time }).take(limit)
    }
}
