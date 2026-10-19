package com.spore.torrent.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TorrentRepository {
    private val _torrents = MutableStateFlow<Map<String, TorrentSnapshot>>(emptyMap())
    val torrents: StateFlow<Map<String, TorrentSnapshot>> = _torrents

    fun upsert(key: String, snapshot: TorrentSnapshot) {
        _torrents.value = _torrents.value.toMutableMap().apply { put(key, snapshot) }
    }

    fun updateProgress(key: String, progress: Float, stateName: String) {
        val existing = _torrents.value[key] ?: return
        _torrents.value = _torrents.value.toMutableMap().apply {
            put(key, existing.copy(progress = progress, stateName = stateName))
        }
    }

    fun remove(key: String) {
        _torrents.value = _torrents.value.toMutableMap().apply { remove(key) }
    }

    fun clear() {
        _torrents.value = emptyMap()
    }
}
