@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.afterglow

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.frostwire.jlibtorrent.AddTorrentParams
import com.frostwire.jlibtorrent.FileStorage
import com.frostwire.jlibtorrent.InfoHash
import com.frostwire.jlibtorrent.Priority
import com.frostwire.jlibtorrent.SessionManager
import com.frostwire.jlibtorrent.SessionParams
import com.frostwire.jlibtorrent.SettingsPack
import com.frostwire.jlibtorrent.TorrentFlags
import com.frostwire.jlibtorrent.TorrentHandle
import com.frostwire.jlibtorrent.TorrentInfo
import com.frostwire.jlibtorrent.swig.settings_pack.bool_types
import com.frostwire.jlibtorrent.swig.settings_pack.int_types
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

internal data class TorrentVideo(val index: Int, val name: String, val bytes: Long)

internal class TorrentEngine(context: Context, magnet: String) {
    companion object {
        private const val TAG = "AfterglowTorrent"
        val exclusive = Mutex()
    }

    private val lock = Any()
    @Volatile private var canceled = false
    private val manager = SessionManager(true)
    private val preparedMagnet = if (!magnet.contains("&tr=")) {
        buildString {
            append(magnet)
            DEFAULT_TRACKERS.forEach {
                append("&tr=").append(Uri.encode(it))
            }
        }
    } else magnet
    private val params = AddTorrentParams.parseMagnetUri(preparedMagnet)
    private val hash: InfoHash = params.getInfoHashes()
    private val root = File(context.cacheDir, "torrent-playback")
    private val cache: File
    private lateinit var handle: TorrentHandle
    private lateinit var info: TorrentInfo
    private var priorityPiece = -1
    private var windowEnd = -1
    private var windowPieces = 0

    init {
        root.listFiles()?.forEach { it.deleteRecursively() }
        cache = File(root, System.nanoTime().toString())
        if (!cache.mkdirs()) throw FeedError("There’s no space to prepare torrent playback.")
        try {
            val settings = SettingsPack().apply {
                setEnableDht(true)
                setEnableLsd(true)
                setBoolean(bool_types.enable_upnp.swigValue(), true)
                setBoolean(bool_types.enable_natpmp.swigValue(), true)
                // Finishing a playback window does not mean we're done with the peer.
                setBoolean(bool_types.close_redundant_connections.swigValue(), false)
                setBoolean(bool_types.strict_end_game_mode.swigValue(), false)
                setBoolean(bool_types.smooth_connects.swigValue(), false)
                setMaxMetadataSize(4 * 1024 * 1024)
                uploadRateLimit(128 * 1024)
                listenInterfaces("0.0.0.0:0,[::]:0")
                connectionsLimit(200)
                setInteger(int_types.connection_speed.swigValue(), 50)
                setInteger(int_types.max_out_request_queue.swigValue(), 1500)
                setInteger(int_types.request_queue_time.swigValue(), 3)
            }
            manager.start(SessionParams(settings).apply { setPosixDiskIO() })
            val native = params.swig()
            native.setSave_path(cache.absolutePath)
            native.setFlags(native.getFlags().and_(TorrentFlags.AUTO_MANAGED.inv())
                .and_(TorrentFlags.PAUSED.inv()).or_(TorrentFlags.UPLOAD_MODE))
            manager.swig().async_add_torrent(native)
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    suspend fun metadata(): List<TorrentVideo> {
        val started = SystemClock.elapsedRealtime()
        var limit = started + 45_000L
        var reportedPeers = -1
        var foundPeer = false
        var lastStatusLog = 0L
        while (SystemClock.elapsedRealtime() < limit) {
            val candidate = if (hash.hasV1()) manager.find(hash.v1) else manager.find(hash.v2)
            val status = candidate?.takeIf { it.isValid }?.status()
            val peers = status?.numPeers() ?: 0
            val seeds = status?.numSeeds() ?: 0
            val now = SystemClock.elapsedRealtime()
            if (now - lastStatusLog >= 5_000L) {
                lastStatusLog = now
                val elapsed = (now - started) / 1000
                Log.i(TAG, "[METADATA_POLL] ${elapsed}s elapsed: candidateValid=${candidate?.isValid == true}, peers=$peers, seeds=$seeds, state=${status?.state()}, hasMetadata=${status?.hasMetadata() == true}")
            }
            if (peers != reportedPeers) {
                reportedPeers = peers
                Log.i(TAG, "[PEERS_CHANGED] Connected peers: $peers (seeds: $seeds)")
            }
            if (peers > 0 && !foundPeer) {
                foundPeer = true
                limit = started + 75_000L
                Log.i(TAG, "[PEER_FOUND] First peer discovered. Extended metadata timeout to 75s.")
            }
            if (candidate != null && status?.hasMetadata() == true) {
                // Upload mode holds payload requests while keeping the metadata peers connected.
                val elapsedMs = SystemClock.elapsedRealtime() - started
                Log.i(TAG, "[METADATA_FETCHED] Metadata acquired successfully in ${elapsedMs}ms.")
                handle = candidate
                info = candidate.torrentFile()
                if (info.numFiles() !in 1..12_000 || info.pieceLength() <= 0) {
                    Log.e(TAG, "[METADATA_UNSUPPORTED] Unsupported file list: numFiles=${info.numFiles()}, pieceLength=${info.pieceLength()}")
                    throw FeedError("This torrent’s file list is unsupported.")
                }
                val files = info.files()
                val videos = (0 until files.numFiles()).mapNotNull { index ->
                    if (files.fileFlags(index).and_(FileStorage.FLAG_SYMLINK).nonZero()) {
                        throw FeedError("This torrent contains an unsupported file link.")
                    }
                    val file = safeFile(files.filePath(index))
                    val size = files.fileSize(index)
                    if (size <= 0 || files.padFileAt(index) || !supportedVideo(file.name)) null
                    else TorrentVideo(index, file.name, size)
                }
                if (videos.isEmpty()) {
                    Log.e(TAG, "[METADATA_NO_VIDEO] Torrent has ${files.numFiles()} files, but none match supported video extensions.")
                    throw FeedError("This torrent contains no supported video files.")
                }
                Log.i(TAG, "[METADATA_FILES] Total files: ${info.numFiles()}, supported video files: ${videos.size}")
                return videos
            }
            delay(250)
        }
        val elapsedSec = (SystemClock.elapsedRealtime() - started) / 1000
        if (foundPeer) {
            Log.e(TAG, "[METADATA_TIMEOUT] Timed out after ${elapsedSec}s. Peers were found ($reportedPeers), but failed to retrieve metadata.")
            throw FeedError("This source didn’t respond in time. Try another source.")
        } else {
            Log.e(TAG, "[ZERO_PEERS_TIMEOUT] Timed out after ${elapsedSec}s with ZERO peers discovered. Trackers or DHT failed to discover/connect to peers.")
            throw FeedError("Nobody is sharing this source right now. Try another source.")
        }
    }

    fun select(video: TorrentVideo): DataSource.Factory {
        val pieceLength = info.pieceLength()
        val numPieces = info.numPieces()
        val requiredBuffer = minOf(video.bytes, 350L * 1024 * 1024 + 2L * pieceLength)
        if (cache.usableSpace < requiredBuffer) {
            Log.e(TAG, "[STORAGE_INSUFFICIENT] Usable space ${cache.usableSpace} is insufficient for required buffer $requiredBuffer (video=${video.bytes})")
            throw FeedError("There isn’t enough free storage to buffer this stream.")
        }
        val files = info.files()
        val file = safeFile(files.filePath(video.index))
        val fileOffset = files.fileOffset(video.index)
        val firstPiece = (fileOffset / pieceLength).toInt().coerceIn(0, numPieces - 1)
        val lastPiece = ((fileOffset + video.bytes - 1) / pieceLength).toInt().coerceIn(0, numPieces - 1)
        synchronized(lock) {
            checkOpen()
            windowPieces = ((8L * 1024 * 1024) / pieceLength).toInt().coerceIn(1, 16)
            priorityPiece = firstPiece
            windowEnd = minOf(firstPiece + windowPieces - 1, lastPiece)
            // Set piece priorities once. Changing file priorities here would overwrite them asynchronously.
            handle.prioritizePieces(Array(numPieces) {
                if (it in firstPiece..windowEnd) Priority.SEVEN else Priority.IGNORE
            })
            handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
            scheduleDeadlines(firstPiece, firstPiece, windowEnd)
        }
        return DataSource.Factory { TorrentDataSource(this, file, video.bytes, fileOffset, pieceLength, lastPiece) }
    }

    private fun safeFile(path: String): File {
        val candidate = File(cache, path).canonicalFile
        if (File(path).isAbsolute || !candidate.path.startsWith(cache.canonicalPath + File.separator)) {
            throw FeedError("This torrent contains an unsupported file path.")
        }
        return candidate
    }

    internal fun waitFor(piece: Int, last: Int, seek: Boolean) {
        synchronized(lock) {
            checkOpen()
            if (priorityPiece != piece) {
                val reset = seek || piece < priorityPiece || piece > priorityPiece + 1
                val end = minOf(piece + windowPieces - 1, last)
                if (reset) handle.clearPieceDeadlines()
                val first = if (reset) piece else windowEnd + 1
                for (next in first..end) {
                    if (!handle.havePiece(next)) handle.piecePriority(next, Priority.SEVEN)
                }
                for (old in priorityPiece..windowEnd) {
                    if (old !in piece..end && !handle.havePiece(old)) handle.piecePriority(old, Priority.IGNORE)
                }
                scheduleDeadlines(piece, first, end)
                priorityPiece = piece
                windowEnd = end
            }
        }
        waitUntilAvailable(piece, SystemClock.elapsedRealtime() + 45_000L)
    }

    private fun scheduleDeadlines(piece: Int, first: Int, last: Int) {
        for (next in first..last) {
            if (!handle.havePiece(next)) handle.setPieceDeadline(next, (next - piece) * 200)
        }
    }

    private fun waitUntilAvailable(piece: Int, until: Long) {
        val started = SystemClock.elapsedRealtime()
        var nextStatus = started + 1_000
        while (true) {
            synchronized(lock) {
                checkOpen()
                if (handle.havePiece(piece)) {
                    val waitTime = SystemClock.elapsedRealtime() - started
                    if (waitTime > 300) {
                        Log.d(TAG, "[BUFFER_PIECE_READY] Piece $piece became available after ${waitTime}ms")
                    }
                    return
                }
                if (SystemClock.elapsedRealtime() >= nextStatus) {
                    val status = handle.status()
                    val error = status.errorCode().value()
                    val downRateKb = status.downloadRate() / 1024
                    Log.d(TAG,
                        "[BUFFER_WAIT] Waiting for piece $piece: peers=${status.numPeers()}, seeds=${status.numSeeds()}, downRate=${downRateKb}KB/s, bytesDone=${status.totalDone()}, errorCode=$error")
                    if (error != 0) {
                        Log.e(TAG, "[BUFFER_STORAGE_ERROR] Torrent storage error $error while waiting for piece $piece")
                        throw IOException("Torrent storage error $error.")
                    }
                    nextStatus = SystemClock.elapsedRealtime() + 3_000
                }
            }
            if (SystemClock.elapsedRealtime() >= until) {
                val status = handle.status()
                Log.e(TAG, "[BUFFER_TIMEOUT] Timed out waiting for piece $piece after 45s (peers=${status.numPeers()}, downRate=${status.downloadRate() / 1024}KB/s, bytesDone=${status.totalDone()})")
                throw IOException("Torrent pieces timed out.")
            }
            try {
                Thread.sleep(50)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Torrent playback stopped.", interrupted)
            }
        }
    }

    private fun checkOpen() {
        if (canceled) throw IOException("Torrent playback stopped.")
    }

    fun cancel() { canceled = true }

    fun close() {
        cancel()
        synchronized(lock) {
            try { if (manager.isRunning) manager.stop() }
            finally { cache.deleteRecursively() }
        }
    }

    private fun supportedVideo(name: String) = name.substringAfterLast('.').lowercase(Locale.ROOT) in
        setOf("mp4", "m4v", "mkv", "webm", "ts", "m2ts", "mov", "avi")
}

private class TorrentDataSource(
    private val engine: TorrentEngine, private val file: File, private val size: Long,
    private val offset: Long, private val pieceLength: Int, private val lastPiece: Int
) : BaseDataSource(true) {
    private var position = 0L
    private var remaining = 0L
    private var source: RandomAccessFile? = null
    private var uri: Uri? = null
    private var newRange = true

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        if (dataSpec.position > size) throw EOFException()
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position
        else minOf(dataSpec.length, size - position)
        uri = dataSpec.uri
        newRange = true

        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val absolute = this.offset + position
        val piece = (absolute / pieceLength).toInt()
        val withinPiece = (absolute % pieceLength).toInt()
        val count = minOf(length.toLong(), remaining, (pieceLength - withinPiece).toLong()).toInt()
        engine.waitFor(piece, lastPiece, newRange)
        newRange = false
        val input = source ?: RandomAccessFile(file, "r").also { source = it }
        input.seek(position)
        input.readFully(buffer, offset, count)
        position += count
        remaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        try { source?.close() }
        finally {
            source = null
            if (uri != null) {
                uri = null
                transferEnded()
            }
        }
    }
}
