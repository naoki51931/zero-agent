package com.zeroagent.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Unverified evidence observations. Do not treat records as validated posts. */
class ResearchEvidenceStore(context: Context) :
    SQLiteOpenHelper(context, "research_evidence.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE evidence (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                query TEXT NOT NULL,
                candidate_text TEXT NOT NULL,
                screenshot_uri TEXT NOT NULL,
                post_url TEXT,
                likes_estimate INTEGER,
                state TEXT NOT NULL DEFAULT 'captured',
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(query, candidate_text, screenshot_uri)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX evidence_query ON evidence(query)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun saveCaptured(query: String, screenshotUri: String, candidates: List<String>): Int {
        require(query.isNotBlank() && screenshotUri.startsWith("content://"))
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for (candidate in candidates.distinct().take(30)) {
                if (candidate.isBlank()) continue
                val values = ContentValues().apply {
                    put("query", query.take(80))
                    put("candidate_text", candidate.take(1000))
                    put("screenshot_uri", screenshotUri)
                    put("state", "captured")
                    put("reason", "Screen capture only; post URL and image relevance unverified")
                    put("created_at", System.currentTimeMillis())
                }
                if (db.insertWithOnConflict("evidence", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }

    fun countCaptured(query: String): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM evidence WHERE query=? AND state='captured'",
            arrayOf(query)).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }
    /** Read-only review report; candidates remain unverified. */
    fun exportJson(): org.json.JSONObject {
        val rows = org.json.JSONArray()
        readableDatabase.query("evidence",
            arrayOf("query", "candidate_text", "screenshot_uri", "post_url",
                "likes_estimate", "state", "reason", "created_at"),
            null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val row = org.json.JSONObject()
                val columns = arrayOf("query", "candidate_text", "screenshot_uri", "post_url",
                    "likes_estimate", "state", "reason", "created_at")
                for (i in columns.indices) {
                    if (cursor.isNull(i)) row.put(columns[i], org.json.JSONObject.NULL)
                    else if (i == 4 || i == 7) row.put(columns[i], cursor.getLong(i))
                    else row.put(columns[i], cursor.getString(i))
                }
                rows.put(row)
            }
        }
        return org.json.JSONObject()
            .put("format", "zero-agent-evidence-review-v1")
            .put("note", "Review only. Screenshot URIs may not survive app reinstall.")
            .put("records", rows)
    }

}
