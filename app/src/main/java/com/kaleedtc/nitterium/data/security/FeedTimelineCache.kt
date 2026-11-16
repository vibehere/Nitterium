package com.kaleedtc.nitterium.data.security

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/**
 * Local SQLite cache for multi-user timeline HTML chunks.
 * Mirrors NewPipe-style feed freshness: reuse until [ttlMs] elapses.
 */
class FeedTimelineCache(
    context: Context,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private val dbHelper = Helper(context.applicationContext)

    fun get(cacheKey: String, nowMs: Long = System.currentTimeMillis()): String? {
        val db = dbHelper.readableDatabase
        db.query(
            TABLE,
            arrayOf(COL_HTML, COL_FETCHED_AT),
            "$COL_KEY=?",
            arrayOf(cacheKey),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val fetchedAt = cursor.getLong(1)
            if (nowMs - fetchedAt > ttlMs) {
                evict(cacheKey)
                return null
            }
            return cursor.getString(0)
        }
    }

    fun put(
        cacheKey: String,
        html: String,
        instanceUrl: String,
        usernames: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (html.isBlank() || cacheKey.isBlank()) return
        val values = ContentValues().apply {
            put(COL_KEY, cacheKey)
            put(COL_INSTANCE, instanceUrl.trimEnd('/'))
            put(COL_USERNAMES, usernames.joinToString(","))
            put(COL_HTML, html)
            put(COL_FETCHED_AT, nowMs)
        }
        dbHelper.writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        prune(nowMs)
    }

    fun evict(cacheKey: String) {
        dbHelper.writableDatabase.delete(TABLE, "$COL_KEY=?", arrayOf(cacheKey))
    }

    fun evictAll() {
        dbHelper.writableDatabase.delete(TABLE, null, null)
    }

    fun prune(nowMs: Long = System.currentTimeMillis()) {
        val cutoff = nowMs - ttlMs
        dbHelper.writableDatabase.delete(TABLE, "$COL_FETCHED_AT<?", arrayOf(cutoff.toString()))
    }

    companion object {
        /** Default freshness window similar to NewPipe feed update thresholds. */
        const val DEFAULT_TTL_MS = 15 * 60 * 1000L
        private const val DB_NAME = "feed_timeline_cache.db"
        private const val DB_VERSION = 1
        private const val TABLE = "feed_timeline_chunks"
        private const val COL_KEY = "cache_key"
        private const val COL_INSTANCE = "instance_url"
        private const val COL_USERNAMES = "usernames"
        private const val COL_HTML = "html"
        private const val COL_FETCHED_AT = "fetched_at"

        fun key(instanceUrl: String, usernames: List<String>): String {
            val normalizedUsers = usernames
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .sorted()
                .joinToString(",")
            val raw = instanceUrl.trim().trimEnd('/').lowercase() + "\u0000" + normalizedUsers
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                  $COL_KEY TEXT PRIMARY KEY NOT NULL,
                  $COL_INSTANCE TEXT NOT NULL,
                  $COL_USERNAMES TEXT NOT NULL,
                  $COL_HTML TEXT NOT NULL,
                  $COL_FETCHED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX idx_feed_chunks_fetched ON $TABLE($COL_FETCHED_AT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
}
