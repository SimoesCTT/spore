package com.spore.torrent.stream

import android.util.Log
import com.spore.torrent.engine.SporeEngine
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo

object StreamServer {
    private const val TAG = "StreamServer"
    private const val PORT = 8421
    private const val CHUNK_SIZE = 256 * 1024  // 256 KB per chunk
    private var server: ApplicationEngine? = null

    fun start() {
        if (server != null) return
        Log.i(TAG, "Starting HTTP server on 0.0.0.0:$PORT")
        try {
            server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
                Log.i(TAG, "MODULE: executing")
                routing {
                    Log.i(TAG, "Inside routing block")

                    get("/") {
                        Log.i(TAG, "ROOT HIT")
                        call.respondText("alive")
                    }

                    route("stream") {
                        route("{infoHash}") {
                            get("{fileIndex}") {
                                Log.i(TAG, "STREAM ROUTE HIT")
                                val infoHash = call.parameters["infoHash"] ?: run {
                                    call.respond(HttpStatusCode.BadRequest, "missing infoHash")
                                    return@get
                                }
                                val fileIndex = call.parameters["fileIndex"]?.toIntOrNull() ?: run {
                                    call.respond(HttpStatusCode.BadRequest, "invalid fileIndex")
                                    return@get
                                }

                                val handle: TorrentHandle? = try {
                                    SporeEngine.findHandle(Sha1Hash.parseHex(infoHash))
                                } catch (e: Exception) {
                                    Log.e(TAG, "parseHex failed", e)
                                    null
                                }
                                if (handle == null || !handle.isValid()) {
                                    Log.w(TAG, "handle null or invalid")
                                    call.respond(HttpStatusCode.NotFound, "torrent not found")
                                    return@get
                                }
                                val tinfo: TorrentInfo? = handle.torrentFile()
                                if (tinfo == null || fileIndex < 0 || fileIndex >= tinfo.numFiles()) {
                                    Log.w(TAG, "tinfo null or fileIndex out of range")
                                    call.respond(HttpStatusCode.NotFound, "invalid file")
                                    return@get
                                }

                                val files = tinfo.files()
                                val fileSize = files.fileSize(fileIndex)
                                val fileName = files.filePath(fileIndex)

                                val rangeHeader = call.request.headers[HttpHeaders.Range]
                                val (start, endInclusive) = parseRange(rangeHeader, fileSize)
                                val contentLength = endInclusive - start + 1

                                Log.i(TAG, "Serving $fileName range=$start-$endInclusive ($contentLength bytes) of $fileSize")

                                val ext = fileName.substringAfterLast('.', "").lowercase()
                                val mime = android.webkit.MimeTypeMap.getSingleton()
                                    .getMimeTypeFromExtension(ext) ?: "application/octet-stream"

                                call.response.header(HttpHeaders.AcceptRanges, "bytes")
                                if (rangeHeader != null) {
                                    call.response.header(HttpHeaders.ContentRange, "bytes $start-$endInclusive/$fileSize")
                                }

                                call.respondOutputStream(
                                    status = if (rangeHeader != null) HttpStatusCode.PartialContent else HttpStatusCode.OK,
                                    contentType = ContentType.parse(mime)
                                ) {
                                    var currentPos = start
                                    while (currentPos <= endInclusive) {
                                        val toRead = minOf(CHUNK_SIZE.toLong(), endInclusive - currentPos + 1).toInt()
                                        val chunk = StreamCoordinator.readFileRange(
                                            infoHash, fileIndex, currentPos, currentPos + toRead
                                        )
                                        if (chunk == null) {
                                            Log.w(TAG, "readFileRange null at pos=$currentPos, retrying")
                                            var retries = 0
                                            var retryChunk: ByteArray? = null
                                            while (retries < 30 && retryChunk == null) {
                                                kotlinx.coroutines.delay(1000)
                                                retryChunk = StreamCoordinator.readFileRange(
                                                    infoHash, fileIndex, currentPos, currentPos + toRead
                                                )
                                                retries++
                                            }
                                            if (retryChunk == null) {
                                                Log.e(TAG, "Giving up at pos=$currentPos after 30s")
                                                break
                                            }
                                            write(retryChunk)
                                            flush()
                                            currentPos += retryChunk.size
                                            continue
                                        }
                                        write(chunk)
                                        flush()
                                        currentPos += chunk.size
                                        Log.d(TAG, "Wrote ${chunk.size} bytes, pos=$currentPos/$endInclusive")
                                    }
                                }
                            }
                        }
                    }
                }
                Log.i(TAG, "MODULE: finished")
            }.also {
                it.start(wait = false)
                Log.i(TAG, "Server started successfully")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Server start FAILED", e)
        }
    }

    fun stop() {
        server?.stop(500, 1500)
        server = null
    }

    fun urlFor(infoHash: String, fileIndex: Int): String =
        "http://127.0.0.1:$PORT/stream/$infoHash/$fileIndex"

    private fun parseRange(header: String?, fileSize: Long): Pair<Long, Long> {
        if (header == null || !header.startsWith("bytes=")) return 0L to (fileSize - 1L)
        return try {
            val parts = header.substring(6).split("-")
            val start = parts[0].toLongOrNull() ?: 0L
            val end = if (parts.size > 1 && parts[1].isNotBlank()) {
                parts[1].toLongOrNull() ?: (fileSize - 1L)
            } else {
                fileSize - 1L
            }
            start to minOf(end, fileSize - 1L)
        } catch (e: Exception) {
            0L to (fileSize - 1L)
        }
    }
}
