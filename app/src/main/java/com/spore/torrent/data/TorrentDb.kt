package com.spore.torrent.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class TorrentRecord(
    val infoHash: String,
    val name: String,
    val savePath: String,
    val addedAt: Long,
    val magnetUri: String,
    val resumeData: ByteArray?
)

class TorrentDbHelper(context: Context) : SQLiteOpenHelper(context, "spore.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE torrents (
                info_hash TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                save_path TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                magnet_uri TEXT NOT NULL,
                resume_data BLOB
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE rss_feeds (
                url TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                filter TEXT NOT NULL,
                last_checked INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS rss_feeds (
                    url TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    filter TEXT NOT NULL,
                    last_checked INTEGER NOT NULL
                )
            """.trimIndent())
        }
    }
}

object TorrentDb {
    private const val TAG = "TorrentDb"
    private var helper: TorrentDbHelper? = null

    fun init(context: Context) {
        if (helper == null) {
            helper = TorrentDbHelper(context.applicationContext)
            TorrentDbHelperHolder.set(helper!!)
            Log.i(TAG, "SQLite initialized")
        }
    }

    fun save(
        infoHash: String,
        name: String,
        savePath: String,
        magnetUri: String,
        resumeData: ByteArray?
    ) {
        val db = helper?.writableDatabase ?: return
        try {
            val existing = getByHash(infoHash)
            val values = ContentValues().apply {
                put("info_hash", infoHash)
                put("name", name)
                put("save_path", savePath)
                put("added_at", existing?.addedAt ?: System.currentTimeMillis())
                put("magnet_uri", magnetUri)
                if (resumeData != null) put("resume_data", resumeData)
            }
            db.insertWithOnConflict("torrents", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            Log.d(TAG, "Saved $infoHash ($name)")
        } catch (e: Exception) {
            Log.e(TAG, "save error", e)
        }
    }

    fun updateResumeData(infoHash: String, data: ByteArray) {
        val db = helper?.writableDatabase ?: return
        try {
            val values = ContentValues().apply { put("resume_data", data) }
            db.update("torrents", values, "info_hash = ?", arrayOf(infoHash))
            Log.d(TAG, "Resume data saved for $infoHash (${data.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "updateResumeData error", e)
        }
    }

    fun delete(infoHash: String) {
        val db = helper?.writableDatabase ?: return
        try {
            db.delete("torrents", "info_hash = ?", arrayOf(infoHash))
            Log.d(TAG, "Deleted $infoHash")
        } catch (e: Exception) {
            Log.e(TAG, "delete error", e)
        }
    }

    fun getByHash(infoHash: String): TorrentRecord? {
        val db = helper?.readableDatabase ?: return null
        return try {
            db.query(
                "torrents", null, "info_hash = ?", arrayOf(infoHash),
                null, null, null
            ).use { c ->
                if (c.moveToFirst()) {
                    TorrentRecord(
                        infoHash = c.getString(c.getColumnIndexOrThrow("info_hash")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        savePath = c.getString(c.getColumnIndexOrThrow("save_path")),
                        addedAt = c.getLong(c.getColumnIndexOrThrow("added_at")),
                        magnetUri = c.getString(c.getColumnIndexOrThrow("magnet_uri")),
                        resumeData = c.getBlob(c.getColumnIndexOrThrow("resume_data"))
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getByHash error", e); null
        }
    }

    fun getAll(): List<TorrentRecord> {
        val db = helper?.readableDatabase ?: return emptyList()
        val out = ArrayList<TorrentRecord>()
        try {
            db.query("torrents", null, null, null, null, null, "added_at ASC").use { c ->
                while (c.moveToNext()) {
                    out.add(
                        TorrentRecord(
                            infoHash = c.getString(c.getColumnIndexOrThrow("info_hash")),
                            name = c.getString(c.getColumnIndexOrThrow("name")),
                            savePath = c.getString(c.getColumnIndexOrThrow("save_path")),
                            addedAt = c.getLong(c.getColumnIndexOrThrow("added_at")),
                            magnetUri = c.getString(c.getColumnIndexOrThrow("magnet_uri")),
                            resumeData = c.getBlob(c.getColumnIndexOrThrow("resume_data"))
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAll error", e)
        }
        return out
    }

    fun clear() {
        helper?.writableDatabase?.delete("torrents", null, null)
    }
}

data class RssFeed(
    val url: String,
    val name: String,
    val filter: String,
    val lastChecked: Long
)

object RssDb {
    private const val TAG = "RssDb"

    fun saveFeed(feed: RssFeed): Boolean {
        val helper = TorrentDbHelperHolder.get() ?: return false
        return try {
            val values = ContentValues().apply {
                put("url", feed.url)
                put("name", feed.name)
                put("filter", feed.filter)
                put("last_checked", feed.lastChecked)
            }
            helper.writableDatabase.insertWithOnConflict(
                "rss_feeds", null, values, SQLiteDatabase.CONFLICT_REPLACE
            ) >= 0
        } catch (e: Exception) {
            Log.e(TAG, "saveFeed error", e); false
        }
    }

    fun getAllFeeds(): List<RssFeed> {
        val helper = TorrentDbHelperHolder.get() ?: return emptyList()
        val out = ArrayList<RssFeed>()
        try {
            helper.readableDatabase.query("rss_feeds", null, null, null, null, null, "name ASC").use { c ->
                while (c.moveToNext()) {
                    out.add(
                        RssFeed(
                            url = c.getString(c.getColumnIndexOrThrow("url")),
                            name = c.getString(c.getColumnIndexOrThrow("name")),
                            filter = c.getString(c.getColumnIndexOrThrow("filter")),
                            lastChecked = c.getLong(c.getColumnIndexOrThrow("last_checked"))
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getAllFeeds error", e)
        }
        return out
    }

    fun deleteFeed(url: String) {
        val helper = TorrentDbHelperHolder.get() ?: return
        try {
            helper.writableDatabase.delete("rss_feeds", "url = ?", arrayOf(url))
        } catch (e: Exception) {
            Log.e(TAG, "deleteFeed error", e)
        }
    }

    fun updateLastChecked(url: String, ts: Long) {
        val helper = TorrentDbHelperHolder.get() ?: return
        try {
            val values = ContentValues().apply { put("last_checked", ts) }
            helper.writableDatabase.update("rss_feeds", values, "url = ?", arrayOf(url))
        } catch (e: Exception) {
            Log.e(TAG, "updateLastChecked error", e)
        }
    }
}

object TorrentDbHelperHolder {
    @Volatile private var helper: TorrentDbHelper? = null
    fun set(h: TorrentDbHelper) { helper = h }
    fun get(): TorrentDbHelper? = helper
}
