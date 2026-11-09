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
import com.frostwire.jlibtorrent.AlertListener
import com.frostwire.jlibtorrent.alerts.Alert
import com.frostwire.jlibtorrent.alerts.AlertType
import com.frostwire.jlibtorrent.alerts.PeerDisconnectedAlert
import com.frostwire.jlibtorrent.alerts.TorrentErrorAlert
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

internal data class TorrentVideo(val index: Int, val name: String, val bytes: Long)

internal class TorrentEngine(context: Context, magnet: String) {
    companion object { val exclusive = Mutex() }

    private val lock = Any()
    @Volatile private var canceled = false
    private val manager = SessionManager()
    private val params = AddTorrentParams.parseMagnetUri(magnet)
    private val hash: InfoHash = params.getInfoHashes()
    private val root = File(context.cacheDir, "torrent-playback")
    private val cache: File
    private lateinit var handle: TorrentHandle
    private lateinit var info: TorrentInfo
    private var priorityPiece = -1
    private val debug = Log.isLoggable("AfterglowTorrent", Log.DEBUG)

    init {
        root.listFiles()?.forEach { it.deleteRecursively() }
        cache = File(root, System.nanoTime().toString())
        if (!cache.mkdirs()) throw FeedError("There’s no space to prepare torrent playback.")
        try {
            val settings = SettingsPack().apply {
                setEnableLsd(false)
                setBoolean(bool_types.enable_upnp.swigValue(), false)
                setBoolean(bool_types.enable_natpmp.swigValue(), false)
                setMaxMetadataSize(4 * 1024 * 1024)
                uploadRateLimit(128 * 1024)
            }
            if (debug) manager.addListener(object : AlertListener {
                override fun types() = intArrayOf(AlertType.PEER_DISCONNECTED.swig(), AlertType.TORRENT_ERROR.swig())
                override fun alert(alert: Alert<*>) {
                    val code = when (alert) {
                        is PeerDisconnectedAlert -> alert.error().value()
                        is TorrentErrorAlert -> alert.error().value()
                        else -> return
                    }
                    Log.d("AfterglowTorrent", "${alert.type()} code=$code")
                }
            })
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
        val limit = SystemClock.elapsedRealtime() + 45_000L
        while (SystemClock.elapsedRealtime() < limit) {
            val candidate = if (hash.hasV1()) manager.find(hash.v1) else manager.find(hash.v2)
            if (candidate != null && candidate.isValid && candidate.status().hasMetadata()) {
                handle = candidate
                info = candidate.torrentFile()
                if (info.numFiles() !in 1..12_000 || info.pieceLength() <= 0) {
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
                if (videos.isEmpty()) throw FeedError("This torrent contains no supported video files.")
                return videos
            }
            delay(250)
        }
        throw FeedError("Torrent metadata timed out. Try a source with more peers.")
    }

    fun select(video: TorrentVideo): DataSource.Factory {
        val pieceLength = info.pieceLength()
        val allowance = 512L * 1024 * 1024 + 2L * pieceLength
        if (cache.usableSpace - allowance < video.bytes) {
            throw FeedError("There isn’t enough free storage for this torrent file.")
        }
        val files = info.files()
        val file = safeFile(files.filePath(video.index))
        val fileOffset = files.fileOffset(video.index)
        val lastPiece = ((fileOffset + video.bytes - 1) / pieceLength).toInt()
        synchronized(lock) {
            checkOpen()
            handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
            handle.prioritizeFiles(Array(files.numFiles()) { if (it == video.index) Priority.NORMAL else Priority.IGNORE })
            handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
            handle.resume()
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
            if (seek || priorityPiece != piece) {
                if (seek || priorityPiece < 0 || piece < priorityPiece || piece > priorityPiece + 1) {
                    handle.clearPieceDeadlines()
                }
                for (index in piece..minOf(piece + 11, last)) {
                    if (!handle.havePiece(index)) handle.setPieceDeadline(index, (index - piece) * 175)
                }
                priorityPiece = piece
            }
        }
        waitUntilAvailable(piece, SystemClock.elapsedRealtime() + 40_000L)
    }

    private fun waitUntilAvailable(piece: Int, until: Long) {
        var nextStatus = SystemClock.elapsedRealtime() + 1_000
        while (true) {
            synchronized(lock) {
                checkOpen()
                if (handle.havePiece(piece)) return
                if (SystemClock.elapsedRealtime() >= nextStatus) {
                    val status = handle.status()
                    val error = status.errorCode().value()
                    if (debug) Log.d("AfterglowTorrent",
                        "Waiting for piece $piece, peers=${status.numPeers()}, bytes=${status.totalDone()}, code=$error")
                    if (error != 0) throw IOException("Torrent storage error $error.")
                    nextStatus = SystemClock.elapsedRealtime() + 3_000
                }
            }
            if (SystemClock.elapsedRealtime() >= until) throw IOException("Torrent pieces timed out.")
            try {
                Thread.sleep(120)
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
