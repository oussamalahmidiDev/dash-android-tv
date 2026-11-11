package tv.afterglow

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

/** Stores verified byte ranges and metadata independently of the active torrent's sparse files. */
internal class TorrentCache(private val directory: File) {
    init {
        directory.mkdirs()
        directory.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    fun entry(key: String, length: Long? = null): File? = File(directory, key).takeIf {
        it.isFile && (length == null || it.length() == length)
    }?.also { it.setLastModified(System.currentTimeMillis()) }

    fun put(key: String, length: Long, write: (OutputStream) -> Unit): File? {
        if (length !in 1..MaxBytes) return null
        entry(key, length)?.let { return it }
        val destination = File(directory, key)
        return try {
            if (destination.exists() && !destination.delete()) return null
            val oldestFirst = directory.listFiles()?.sortedBy(File::lastModified) ?: return null
            var used = oldestFirst.sumOf(File::length)
            for (file in oldestFirst) {
                if (used + length <= MaxBytes && directory.usableSpace >= length) break
                val bytes = file.length()
                if (file.delete()) used -= bytes
            }
            if (used + length > MaxBytes || directory.usableSpace < length) return null
            val temporary = File.createTempFile("piece-", ".tmp", directory)
            try {
                temporary.outputStream().buffered().use(write)
                if (temporary.length() == length && temporary.renameTo(destination)) destination else null
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

    private companion object {
        const val MaxBytes = 256L * 1024 * 1024
    }
}
