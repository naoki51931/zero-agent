package com.zeroagent.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Unverified evidence observations. Do not treat records as validated posts. */
class ResearchEvidenceStore(context: Context) :
    SQLiteOpenHelper(context, "research_evidence.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE evidence (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                query TEXT NOT NULL,
                candidate_text TEXT NOT NULL,
                screenshot_uri TEXT NOT NULL,
                post_url TEXT,
                likes_estimate INTEGER,
                image_review TEXT,
                image_review_reason TEXT,
                state TEXT NOT NULL DEFAULT 'captured',
                reason TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(query, candidate_text, screenshot_uri)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX evidence_query ON evidence(query)")
        createImageLikes(db)
    }
    private fun createImageLikes(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS image_likes (id INTEGER PRIMARY KEY AUTOINCREMENT, screenshot_uri TEXT NOT NULL, query TEXT NOT NULL, excerpt TEXT NOT NULL, likes INTEGER NOT NULL, UNIQUE(screenshot_uri, excerpt, likes))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 3) createImageLikes(db)
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE evidence ADD COLUMN image_review TEXT")
            db.execSQL("ALTER TABLE evidence ADD COLUMN image_review_reason TEXT")
        }
    }

    fun saveImageLikes(query: String, uri: String, items: org.json.JSONArray): Int {
        val db = writableDatabase
        var count = 0
        for (i in 0 until minOf(items.length(), 8)) {
            val item = items.optJSONObject(i) ?: continue
            val excerpt = item.optString("text").trim().take(180)
            val likes = item.optLong("likes", -1)
            if (excerpt.length < 3 || likes < 0) continue
            val values = ContentValues().apply {
                put("query", query.take(80)); put("screenshot_uri", uri)
                put("excerpt", excerpt); put("likes", likes)
            }
            if (db.insertWithOnConflict("image_likes", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) count++
        }
        return count
    }
    fun imageLikes(): org.json.JSONArray {
        val rows = org.json.JSONArray()
        readableDatabase.query("image_likes", arrayOf("query", "excerpt", "likes", "screenshot_uri"),
            null, null, null, null, "likes DESC", "300").use { c ->
            while (c.moveToNext()) rows.put(org.json.JSONObject().put("query", c.getString(0))
                .put("text", c.getString(1)).put("likes", c.getLong(2)).put("screenshot_uri", c.getString(3)))
        }
        return rows
    }

    fun saveScreenReview(screenshotUri: String, verdict: String, reason: String): Int {
        require(verdict in setOf("screen_relevant", "screen_unrelated", "screen_uncertain", "screen_error"))
        val values = ContentValues().apply {
            put("image_review", verdict)
            put("image_review_reason", reason.take(500))
        }
        return writableDatabase.update("evidence", values,
            "screenshot_uri=?", arrayOf(screenshotUri))
    }

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
                "likes_estimate", "state", "reason", "created_at", "image_review", "image_review_reason"),
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
                if (cursor.isNull(8)) row.put("image_review", org.json.JSONObject.NULL)
                else row.put("image_review", cursor.getString(8))
                if (cursor.isNull(9)) row.put("image_review_reason", org.json.JSONObject.NULL)
                else row.put("image_review_reason", cursor.getString(9))
                rows.put(row)
            }
        }
        return org.json.JSONObject()
            .put("format", "zero-agent-evidence-review-v1")
            .put("note", "Review only. Screenshot URIs may not survive app reinstall.")
            .put("records", rows)
    }

    /** Merge restored evidence with newly-created image URIs, never mark it verified. */
    fun importCaptured(records: org.json.JSONArray, imageUris: Map<String, String>): Int {
        require(records.length() <= 10000) { "Too many evidence records" }
        val validated = (0 until records.length()).map { i ->
            val row = records.optJSONObject(i) ?: error("Invalid evidence record")
            val query = row.optString("query")
            val candidate = row.optString("candidate_text")
            val oldUri = row.optString("screenshot_uri")
            require(query.isNotBlank() && query.length <= 80 &&
                candidate.isNotBlank() && candidate.length <= 1000 &&
                oldUri.startsWith("content://")) { "Invalid evidence values" }
            Triple(query, candidate, oldUri)
        }
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for ((query, candidate, oldUri) in validated) {
                val uri = imageUris[oldUri] ?: continue
                val values = ContentValues().apply {
                    put("query", query)
                    put("candidate_text", candidate)
                    put("screenshot_uri", uri)
                    put("state", "captured")
                    put("reason", "Restored screenshot and candidate; post relevance not verified")
                    put("created_at", System.currentTimeMillis())
                }
                if (db.insertWithOnConflict("evidence", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }

}
