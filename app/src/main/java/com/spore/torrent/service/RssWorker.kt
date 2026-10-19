package com.spore.torrent.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.prof18.rssparser.RssParser
import com.spore.torrent.data.RssDb
import com.spore.torrent.engine.SporeEngine
import java.io.File

class RssWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val TAG = "RssWorker"

    override suspend fun doWork(): Result {
        Log.i(TAG, "RSS check started")
        val parser = RssParser()
        val feeds = RssDb.getAllFeeds()
        if (feeds.isEmpty()) {
            Log.i(TAG, "No feeds configured")
            return Result.success()
        }
        val saveDir = File(applicationContext.getExternalFilesDir(null), "Downloads")
        for (feed in feeds) {
            try {
                Log.i(TAG, "Fetching ${feed.name} (${feed.url})")
                val channel = parser.getRssChannel(feed.url)
                val items: List<com.prof18.rssparser.model.RssItem> = channel.items
                val regex = if (feed.filter.isNotBlank()) {
                    try { Regex(feed.filter, RegexOption.IGNORE_CASE) } catch (_: Exception) { null }
                } else null

                for (item in items) {
                    val title = item.title ?: continue
                    val rawDate: String? = item.pubDate
                    val pubDateMs: Long = if (rawDate != null) parseDate(rawDate) else 0L
                    if (pubDateMs <= feed.lastChecked) continue
                    if (regex != null && !regex.containsMatchIn(title)) continue

                    val magnet = extractMagnet(item)
                    if (magnet != null) {
                        Log.i(TAG, "Auto-adding: $title")
                        SporeEngine.addMagnet(magnet, saveDir)
                    }
                }
                RssDb.updateLastChecked(feed.url, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e(TAG, "Feed ${feed.name} failed", e)
            }
        }
        Log.i(TAG, "RSS check finished")
        return Result.success()
    }

    private fun parseDate(s: String): Long {
        return try {
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            fmt.parse(s)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    private fun extractMagnet(item: com.prof18.rssparser.model.RssItem): String? {
        val link = item.link
        if (link != null && link.startsWith("magnet:?")) return link
        val desc = item.description ?: ""
        val match = Regex("magnet:\\?[^\"<>\\s]+").find(desc)
        return match?.value
    }
}
