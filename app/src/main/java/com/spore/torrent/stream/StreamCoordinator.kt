package com.spore.torrent.stream

import android.util.Log
import com.spore.torrent.engine.SporeEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.RandomAccessFile

object StreamCoordinator {
    private const val TAG = "StreamCoordinator"
    private const val WINDOW_URGENT = 4
    private const val WINDOW_NORMAL = 12
    private const val DEADLINE_URGENT_MS = 2000
    private const val DEADLINE_NORMAL_MS = 10000
    private const val READ_TIMEOUT_MS = 30000L

    suspend fun readFileRange(
        infoHashHex: String,
        fileIndex: Int,
        start: Long,
        end: Long
    ): ByteArray? {
        val infoHash = Sha1Hash.parseHex(infoHashHex)
        val handle: TorrentHandle = SporeEngine.findHandle(infoHash) ?: return null
        if (!handle.isValid()) return null
        val tinfo: TorrentInfo = handle.torrentFile() ?: return null

        val pieceLength = tinfo.pieceLength()
        if (pieceLength <= 0) return null

        val firstPiece = (start / pieceLength).toInt()
        val lastPiece = ((end - 1) / pieceLength).toInt()
        if (lastPiece < firstPiece) return null
        val pieces = (firstPiece..lastPiece).toList()

        for ((i, piece) in pieces.withIndex()) {
            val deadline = when {
                i < WINDOW_URGENT -> DEADLINE_URGENT_MS
                i < WINDOW_NORMAL -> DEADLINE_NORMAL_MS
                else -> continue
            }
            try { handle.setPieceDeadline(piece, deadline) } catch (_: Exception) {}
        }

        for (piece in pieces) {
            val available = withTimeoutOrNull(READ_TIMEOUT_MS) {
                while (true) {
                    if (!handle.isValid()) return@withTimeoutOrNull false
                    if (handle.havePiece(piece)) return@withTimeoutOrNull true
                    delay(100)
                }
                @Suppress("UNREACHABLE_CODE")
                false
            } ?: false
            if (!available) {
                Log.w(TAG, "timeout waiting for piece=$piece")
                return null
            }
        }

        for (piece in pieces) {
            try { handle.resetPieceDeadline(piece) } catch (_: Exception) {}
        }

        return readFromDisk(tinfo, fileIndex, start, end)
    }

    private fun readFromDisk(
        tinfo: TorrentInfo,
        fileIndex: Int,
        start: Long,
        end: Long
    ): ByteArray? {
        val saveDir = SporeEngine.getSaveDir() ?: return null
        val torrentName = tinfo.name() ?: return null
        val files = tinfo.files()
        val filePath = files.filePath(fileIndex)

        // libtorrent's filePath may or may not include the torrent top folder.
        // Try the nested layout, the direct layout, and a flattened filename.
        val nested = File(File(saveDir, torrentName), filePath)
        val direct = File(saveDir, filePath)
        val flatName = filePath.substringAfterLast('/')
        val flat = File(File(saveDir, torrentName), flatName)
        val file = listOf(nested, direct, flat).firstOrNull { it.exists() }
        if (file == null) {
            Log.w(TAG, "file not on disk; tried: ${nested.absolutePath} | ${direct.absolutePath} | ${flat.absolutePath}")
            return null
        }
        val length = (end - start).toInt()
        if (length <= 0 || length > 64 * 1024 * 1024) return null
        try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(length)
                var total = 0
                while (total < length) {
                    val r = raf.read(buf, total, length - total)
                    if (r < 0) return null
                    total += r
                }
                return buf
            }
        } catch (e: Exception) {
            Log.e(TAG, "readFromDisk error", e)
            return null
        }
    }
}
