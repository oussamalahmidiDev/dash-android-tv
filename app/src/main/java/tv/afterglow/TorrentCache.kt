package tv.afterglow

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/** Stores verified byte ranges and metadata independently of the active torrent's sparse files. */
internal class TorrentCache(private val directory: File) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, Long>()
    private var usedBytes: Long = 0L

    init {
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
        synchronized(lock) {
            val files = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }
                ?.sortedBy(File::lastModified) ?: emptyList()
            for (file in files) {
                val len = file.length()
                entries[file.name] = len
                usedBytes += len
            }
        }
    }

    fun entry(key: String, length: Long? = null): File? {
        val file = File(directory, key)
        return synchronized(lock) {
            val recordedLen = entries[key] ?: (if (file.isFile) file.length().also { entries[key] = it; usedBytes += it } else null)
            if (recordedLen != null && (length == null || recordedLen == length)) {
                entries.remove(key)
                entries[key] = recordedLen
                file.setLastModified(System.currentTimeMillis())
                file
            } else null
        }
    }

    fun put(key: String, length: Long, write: (OutputStream) -> Unit): File? {
        if (length !in 1..MaxBytes) return null
        entry(key, length)?.let { return it }
        val destination = File(directory, key)
        return try {
            synchronized(lock) {
                val currentFiles = directory.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: emptyList()
                if (currentFiles.size != entries.size || currentFiles.any { it.name !in entries }) {
                    entries.clear()
                    usedBytes = 0L
                    for (file in currentFiles.sortedBy(File::lastModified)) {
                        val len = file.length()
                        entries[file.name] = len
                        usedBytes += len
                    }
                }
                if (destination.exists()) {
                    val prevLen = entries.remove(key) ?: destination.length()
                    if (destination.delete()) usedBytes -= prevLen
                }
                val iterator = entries.entries.iterator()
                while (iterator.hasNext() && (usedBytes + length > MaxBytes || directory.usableSpace < length)) {
                    val entry = iterator.next()
                    val oldFile = File(directory, entry.key)
                    if (oldFile.delete()) {
                        iterator.remove()
                        usedBytes -= entry.value
                    }
                }
                if (usedBytes + length > MaxBytes || directory.usableSpace < length) return null
            }
            val temporary = File.createTempFile("piece-", ".tmp", directory)
            try {
                temporary.outputStream().buffered().use(write)
                if (temporary.length() == length && temporary.renameTo(destination)) {
                    synchronized(lock) {
                        entries[key] = length
                        usedBytes += length
                    }
                    destination
                } else null
            } finally {
                temporary.delete()
            }
        } catch (_: IOException) {
            null
        }
    }

    fun putRange(key: String, source: RandomAccessFile, offset: Long, length: Long): File? =
        put(key, length) { output ->
            source.seek(offset)
            val buffer = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                val bytes = minOf(left, buffer.size.toLong()).toInt()
                source.readFully(buffer, 0, bytes)
                output.write(buffer, 0, bytes)
                left -= bytes
            }
        }

    companion object {
        const val MaxBytes = 400L * 1024 * 1024
    }
}
