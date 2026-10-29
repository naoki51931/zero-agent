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

    fun find(objective: String): List<String> {
        val rows = mutableListOf<String>()
        readableDatabase.query("queries", arrayOf("phrase"), "objective = ?",
            arrayOf(objective.take(500)), null, null, "id ASC", "12").use { cursor ->
            while (cursor.moveToNext()) rows.add(cursor.getString(0))
        }
        return rows
    }
}
