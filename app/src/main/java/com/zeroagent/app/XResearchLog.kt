package com.zeroagent.app

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded, diagnostic-only event log in Downloads/ZeroAgent; never stores credentials or screen text. */
class XResearchLog(private val context: Context) {
    private val entries = mutableListOf<String>()
    private val fallback = java.io.File(context.filesDir, "x_research_latest.jsonl")
    private val session = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val name = "zero_agent_x_research_" + session + ".jsonl"
    private val uri by lazy {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ZeroAgent")
        }
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create diagnostic log")
    }

    init {
        // Create a visible file immediately, even if validation stops the research before launch.
        record("log_initialized")
    }

    @Synchronized
    fun record(event: String, detail: String = "") {
        val row = JSONObject().put("at", System.currentTimeMillis())
            .put("event", event.take(80)).put("detail", detail.take(240)).toString()
        entries.add(row)
        if (entries.size > 600) entries.removeAt(0)
        runCatching { fallback.writeText(entries.joinToString("\n") + "\n") }
        flush()
    }

    @Synchronized
    private fun flush() {
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write((entries.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
        } ?: error("Cannot write diagnostic log")
    }

    fun location(): String = "Downloads/ZeroAgent/" + name
}
