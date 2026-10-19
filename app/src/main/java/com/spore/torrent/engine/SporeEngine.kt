package com.spore.torrent.engine

import android.util.Log
import com.spore.torrent.data.TorrentRepository
import com.spore.torrent.data.TorrentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.MetadataReceivedAlert
import org.libtorrent4j.alerts.TorrentFinishedAlert
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

object SporeEngine {
    private const val TAG = "SporeEngine"

    private val sessionDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + sessionDispatcher)

    private var session: SessionManager? = null
    private var alertListener: AlertListener? = null

    private val generation = AtomicInteger(0)

    private data class HandleEntry(val handle: TorrentHandle, val gen: Int)

    private val handles = ConcurrentHashMap<String, HandleEntry>()
    private val pollJobs = ConcurrentHashMap<String, Job>()
    private val finishedFlags = ConcurrentHashMap<String, Boolean>()

    private var saveDir: File? = null
    private val magnetByHash = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun mapTorrentState(state: TorrentStatus.State?): String {
        if (state == null) return "UNKNOWN"
        if (state == TorrentStatus.State.CHECKING_FILES) return "CHECKING_FILES"
        if (state == TorrentStatus.State.DOWNLOADING_METADATA) return "DOWNLOADING_METADATA"
        if (state == TorrentStatus.State.DOWNLOADING) return "DOWNLOADING"
        if (state == TorrentStatus.State.FINISHED) return "FINISHED"
        if (state == TorrentStatus.State.SEEDING) return "SEEDING"
        if (state == TorrentStatus.State.CHECKING_RESUME_DATA) return "CHECKING_RESUME_DATA"
        return "UNKNOWN"
    }

    fun start(saveDir: File) {
        this.saveDir = saveDir
        scope.launch {
            if (session != null) return@launch

            val myGen = generation.incrementAndGet()
            Log.i(TAG, "Starting session generation=$myGen")

            val settings = SettingsPack()
            settings.setEnableDht(true)
            settings.setEnableLsd(false)
            val params = SessionParams(settings)
            params.setPosixDiskIO()
            Log.i(TAG, "Session settings: DHT=on LSD=off posix_disk_io=on")

            val sm = SessionManager()

            val listener = object : AlertListener {
                override fun types(): IntArray? = null
                override fun alert(alert: Alert<*>) {
                    try {
                        when (alert.type()) {
                            AlertType.ADD_TORRENT -> {
                                val h = (alert as AddTorrentAlert).handle()
                                if (h != null && h.isValid) {
                                    val key: String = h.infoHash().toString()
                                    handles[key] = HandleEntry(h, myGen)
                                    Log.i(TAG, "ADD_TORRENT key=$key gen=$myGen")
                                }
                            }
                            AlertType.METADATA_RECEIVED -> {
                                val metaAlert = alert as MetadataReceivedAlert
                                val h = metaAlert.handle()
                                if (h != null) {
                                    val key: String = h.infoHash().toString()
                                    Log.i(TAG, "METADATA_RECEIVED key=$key gen=$myGen — starting poll (fresh-handle mode)")
                                    try {
                                        val name: String = safeName(h, key)
                                        val savePath = saveDir.absolutePath
                                        val magnetForDb: String = currentMagnetFor(key) ?: ""
                                        com.spore.torrent.data.TorrentDb.save(
                                            infoHash = key,
                                            name = name,
                                            savePath = savePath,
                                            magnetUri = magnetForDb,
                                            resumeData = null
                                        )
                                    } catch (e: Exception) {
                                        Log.e(TAG, "DB save on metadata failed", e)
                                    }
                                    startPoll(key, myGen)
                                }
                            }
                            AlertType.SAVE_RESUME_DATA -> {
                                try {
                                    val saveAlert = alert as org.libtorrent4j.alerts.SaveResumeDataAlert
                                    val h = saveAlert.handle()
                                    if (h != null && h.isValid) {
                                        val key = h.infoHash().toString()
                                        val params = saveAlert.params()
                                        if (params != null) {
                                            val data = org.libtorrent4j.AddTorrentParams.writeResumeDataBuf(params)
                                            if (data != null && data.size > 0) {
                                                com.spore.torrent.data.TorrentDb.updateResumeData(key, data)
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "SAVE_RESUME_DATA handler failed", e)
                                }
                            }
                            AlertType.TORRENT_FINISHED -> {
                                val fin = alert as TorrentFinishedAlert
                                val h = fin.handle()
                                if (h != null && h.isValid) {
                                    val key: String = h.infoHash().toString()
                                    finishedFlags[key] = true
                                    Log.i(TAG, "TORRENT_FINISHED key=$key — stopping poll")
                                }
                            }
                            AlertType.SESSION_ERROR -> Log.e(TAG, "SESSION_ERROR: ${alert.what()}")
                            AlertType.LISTEN_FAILED -> Log.e(TAG, "LISTEN_FAILED: ${alert.what()}")
                            AlertType.TORRENT_ERROR -> Log.e(TAG, "TORRENT_ERROR: ${alert.what()}")
                            else -> {}
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Alert error", e)
                    }
                }
            }

            sm.addListener(listener)
            alertListener = listener
            sm.start(params)
            restoreTorrents(saveDir)
            session = sm
            try { com.spore.torrent.stream.StreamServer.start() } catch (e: Exception) { Log.e(TAG, "StreamServer start failed", e); e.printStackTrace() }
            Log.i(TAG, "DIAG: isDhtRunning=${sm.isDhtRunning()} dhtNodes=${sm.dhtNodes()} listenEndpoints=${sm.listenEndpoints()}")
            Log.i(TAG, "Session started gen=$myGen")
        }
    }

    fun findHandle(infoHash: org.libtorrent4j.Sha1Hash): TorrentHandle? {
        val sm = session ?: return null
        val h = sm.find(infoHash) ?: return null
        if (!h.isValid()) return null
        return h
    }

    fun getSaveDir(): File? = saveDir


    data class TorrentFileInfo(val index: Int, val name: String, val size: Long)

    fun listFiles(infoHashHex: String): List<TorrentFileInfo> {
        val h = try { findHandle(org.libtorrent4j.Sha1Hash.parseHex(infoHashHex)) } catch (e: Exception) { null } ?: return emptyList()
        if (!h.isValid()) return emptyList()
        val tinfo = h.torrentFile() ?: return emptyList()
        val files = tinfo.files()
        val out = ArrayList<TorrentFileInfo>()
        for (i in 0 until tinfo.numFiles()) {
            out.add(TorrentFileInfo(i, files.filePath(i), files.fileSize(i)))
        }
        return out
    }


    private fun currentMagnetFor(key: String): String? = magnetByHash[key]

    private fun rememberMagnet(magnetUri: String) {
        try {
            val match = Regex("btih:([0-9a-fA-F]+)").find(magnetUri) ?: return
            val hash = match.groupValues[1].lowercase()
            magnetByHash[hash] = magnetUri
        } catch (_: Exception) {}
    }

    fun addMagnet(magnetUri: String, saveDir: File) {
        scope.launch {
            val sm = session ?: return@launch
            saveDir.mkdirs()
            rememberMagnet(magnetUri)
            sm.download(magnetUri, saveDir, torrent_flags_t())
            Log.i(TAG, "Magnet added: $magnetUri")
        }
    }

    fun pause(key: String) {
        scope.launch {
            try {
                val infoHash = Sha1Hash.parseHex(key)
                val fresh = session?.find(infoHash)
                if (fresh != null && fresh.isValid()) {
                    fresh.pause()
                    Log.i(TAG, "Paused $key")
                } else {
                    Log.w(TAG, "Pause $key: handle not found or invalid")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pause error for $key", e)
            }
        }
    }

    fun resume(key: String) {
        scope.launch {
            try {
                val infoHash = Sha1Hash.parseHex(key)
                val fresh = session?.find(infoHash)
                if (fresh != null && fresh.isValid()) {
                    fresh.resume()
                    Log.i(TAG, "Resumed $key")
                } else {
                    Log.w(TAG, "Resume $key: handle not found or invalid")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Resume error for $key", e)
            }
        }
    }

    fun remove(key: String) {
        scope.launch {
            pollJobs.remove(key)?.cancel()
            finishedFlags.remove(key)
            handles.remove(key)
            try {
                val infoHash = Sha1Hash.parseHex(key)
                val fresh = session?.find(infoHash)
                if (fresh != null && fresh.isValid()) {
                    session?.remove(fresh)
                    Log.i(TAG, "Removed $key")
                } else {
                    Log.w(TAG, "Remove $key: handle already invalid or not found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Remove error for $key", e)
            }
            TorrentRepository.remove(key)
            try {
                com.spore.torrent.data.TorrentDb.delete(key)
            } catch (e: Exception) {
                Log.e(TAG, "DB delete failed", e)
            }
        }
    }

    private fun startPoll(key: String, gen: Int) {
        pollJobs[key]?.cancel()
        pollJobs[key] = scope.launch { pollStatus(key, gen) }
    }

    private suspend fun pollStatus(key: String, gen: Int) {
        val infoHash = Sha1Hash.parseHex(key)
        var lastStatusLog = 0L
        while (true) {
            if (gen != generation.get()) {
                Log.i(TAG, "Poll exiting for $key — session generation changed")
                return
            }

            if (finishedFlags[key] == true) {
                Log.i(TAG, "Poll loop exiting for $key — torrent finished")
                TorrentRepository.updateProgress(key, 1.0f, "FINISHED")
                return
            }

            try {
                val sm = session ?: return
                // CRITICAL FIX: fetch fresh handle from session every iteration.
                // The handle from AddTorrentAlert/MetadataReceivedAlert becomes dangling
                // when libtorrent re-registers the torrent after metadata resolution.
                val fresh = sm.find(infoHash)
                if (fresh == null || !fresh.isValid()) {
                    Log.w(TAG, "Poll $key: handle invalid or torrent dropped, exiting")
                    return
                }

                val st: TorrentStatus = fresh.status()
                val stateStr: String = mapTorrentState(st.state())
                val rawName: String? = st.name()
                val torrentName: String = if (rawName != null) rawName else key
                val prog: Float = st.progress()

                TorrentRepository.upsert(
                    key,
                    TorrentSnapshot(
                        name = torrentName,
                        progress = prog,
                        downloadRate = st.downloadRate(),
                        uploadRate = st.uploadRate(),
                        numPeers = st.numPeers(),
                        numSeeds = st.numSeeds(),
                        stateName = stateStr,
                        infoHash = key,
                        totalDone = st.totalDone(),
                        totalWanted = st.totalWanted()
                    )
                )

                val now = System.currentTimeMillis()
                if (now - lastStatusLog > 3000) {
                    Log.i(TAG, "Repo update: key=$key progress=$prog state=$stateStr peers=${st.numPeers()} seeds=${st.numSeeds()}")
                    lastStatusLog = now
                }
            } catch (e: Exception) {
                Log.e(TAG, "Poll error for $key", e)
            }
            delay(1000)
        }
    }


    private fun restoreTorrents(saveDir: File) {
        val records = com.spore.torrent.data.TorrentDb.getAll()
        if (records.isEmpty()) {
            Log.i(TAG, "No torrents to restore")
            return
        }
        Log.i(TAG, "Restoring ${records.size} torrents from DB")
        for (rec in records) {
            try {
                if (rec.magnetUri.isNotBlank()) {
                    rememberMagnet(rec.magnetUri)
                    session?.download(rec.magnetUri, File(rec.savePath), torrent_flags_t())
                    Log.i(TAG, "Restored magnet: ${rec.name} (${rec.infoHash})")
                } else {
                    Log.w(TAG, "No magnet URI for ${rec.infoHash}, skipping")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed for ${rec.infoHash}", e)
            }
        }
    }


    private fun safeName(h: TorrentHandle, fallback: String): String {
        return h.status().name() ?: fallback
    }


    fun applySettings(downloadLimitKb: Int, uploadLimitKb: Int, listenPort: Int) {
        scope.launch {
            try {
                val sm = session ?: return@launch
                val settings = SettingsPack()
                if (downloadLimitKb > 0) settings.downloadRateLimit(downloadLimitKb * 1024)
                if (uploadLimitKb > 0) settings.uploadRateLimit(uploadLimitKb * 1024)
                if (listenPort > 0) settings.listenInterfaces("0.0.0.0:$listenPort")
                sm.applySettings(settings)
                Log.i(TAG, "Settings applied: dl=${downloadLimitKb}KB ul=${uploadLimitKb}KB port=$listenPort")
            } catch (e: Exception) {
                Log.e(TAG, "applySettings failed", e)
            }
        }
    }


    fun pauseAll() {
        scope.launch {
            try {
                val keys = handles.keys.toList()
                for (k in keys) {
                    try {
                        val infoHash = Sha1Hash.parseHex(k)
                        val h = session?.find(infoHash)
                        if (h != null && h.isValid()) {
                            h.pause()
                        }
                    } catch (_: Exception) {}
                }
                Log.i(TAG, "Paused all torrents (${keys.size})")
            } catch (e: Exception) {
                Log.e(TAG, "pauseAll failed", e)
            }
        }
    }

    fun resumeAll() {
        scope.launch {
            try {
                val keys = handles.keys.toList()
                for (k in keys) {
                    try {
                        val infoHash = Sha1Hash.parseHex(k)
                        val h = session?.find(infoHash)
                        if (h != null && h.isValid()) {
                            h.resume()
                        }
                    } catch (_: Exception) {}
                }
                Log.i(TAG, "Resumed all torrents (${keys.size})")
            } catch (e: Exception) {
                Log.e(TAG, "resumeAll failed", e)
            }
        }
    }

    fun stop() {
        scope.launch {
            pollJobs.values.forEach { it.cancel() }
            pollJobs.clear()
            finishedFlags.clear()
            handles.clear()

            try { com.spore.torrent.stream.StreamServer.stop() } catch (_: Exception) {}
            val sm = session
            val listener = alertListener
            session = null
            alertListener = null
            TorrentRepository.clear()

            try {
                if (sm != null && listener != null) {
                    sm.removeListener(listener)
                    Log.i(TAG, "Listener removed")
                }
                sm?.stop()
                Log.i(TAG, "Session stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Session stop error", e)
            }
        }
    }
}
