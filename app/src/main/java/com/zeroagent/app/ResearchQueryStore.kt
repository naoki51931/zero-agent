package com.zeroagent.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Stores research phrases and their provenance on-device, never in source-code constants. */
class ResearchQueryStore(context: Context) : SQLiteOpenHelper(context, "research_queries.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE queries (id INTEGER PRIMARY KEY AUTOINCREMENT, objective TEXT NOT NULL, phrase TEXT NOT NULL, created_at INTEGER NOT NULL, UNIQUE(objective, phrase))")
        db.execSQL("CREATE INDEX queries_objective ON queries(objective)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun remember(objective: String, phrases: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (phrase in phrases.distinct().take(12)) {
                val values = android.content.ContentValues().apply {
                    put("objective", objective.take(500))
                    put("phrase", phrase.take(80))
                    put("created_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("queries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun replace(objective: String, phrases: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("queries", "objective = ?", arrayOf(objective.take(500)))
            for (phrase in phrases.distinct().take(12)) {
                val values = android.content.ContentValues().apply {
                    put("objective", objective.take(500))
                    put("phrase", phrase.take(80))
                    put("created_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("queries", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun find(objective: String): List<String> {
        val rows = mutableListOf<String>()
        readableDatabase.query("queries", arrayOf("phrase"), "objective = ?",
            arrayOf(objective.take(500)), null, null, "id ASC", "12").use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.getString(0))
        }
        return rows
    }
    fun exportJson(): org.json.JSONObject {
        val entries = org.json.JSONArray()
        readableDatabase.query("queries", arrayOf("objective", "phrase", "created_at"),
            null, null, null, null, "id ASC").use { cursor ->
            while (cursor.moveToNext()) {
                entries.put(org.json.JSONObject()
                    .put("objective", cursor.getString(0))
                    .put("phrase", cursor.getString(1))
                    .put("created_at", cursor.getLong(2)))
            }
        }
        return org.json.JSONObject().put("format", "zero-agent-research-v1")
            .put("version", 1).put("queries", entries)
    }

    /** Validate the entire document before starting a transaction; merge without deleting local data. */
    fun importJson(data: org.json.JSONObject): Int {
        require(data.optString("format") == "zero-agent-research-v1" && data.optInt("version") == 1) {
            "Unsupported backup format"
        }
        val array = data.optJSONArray("queries") ?: throw IllegalArgumentException("Missing queries")
        require(array.length() <= 10000) { "Too many entries" }
        val rows = (0 until array.length()).map { index ->
            val row = array.optJSONObject(index) ?: throw IllegalArgumentException("Invalid row")
            val objective = row.optString("objective")
            val phrase = row.optString("phrase")
            require(objective.isNotBlank() && objective.length <= 500 &&
                phrase.isNotBlank() && phrase.length <= 80) { "Invalid query" }
            Triple(objective, phrase, row.optLong("created_at", System.currentTimeMillis()))
        }
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            for ((objective, phrase, createdAt) in rows) {
                val values = android.content.ContentValues().apply {
                    put("objective", objective)
                    put("phrase", phrase)
                    put("created_at", createdAt)
                }
                if (db.insertWithOnConflict("queries", null, values,
                        SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }

}
