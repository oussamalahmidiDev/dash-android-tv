package tv.afterglow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class TorrentCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun reopensCommittedRangeAndIgnoresPartialEntries() {
        val directory = temporary.newFolder()
        val source = temporary.newFile().apply { writeBytes(byteArrayOf(9, 8, 7, 6, 5)) }
        val cache = TorrentCache(directory)
        RandomAccessFile(source, "r").use { cache.putRange("movie.0", it, 1, 3) }
        assertNull(cache.put("short", 4) { it.write(byteArrayOf(1, 2)) })
        directory.resolve("interrupted.tmp").writeBytes(byteArrayOf(0))

        val reopened = TorrentCache(directory)
        assertArrayEquals(byteArrayOf(8, 7, 6), reopened.entry("movie.0", 3)?.readBytes())
        assertNull(reopened.entry("short"))
        assertFalse(directory.resolve("interrupted.tmp").exists())
    }

    @Test
    fun evictsOldestEntryBeforeExceedingTheLimit() {
        val directory = temporary.newFolder()
        val cache = TorrentCache(directory)
        val oldest = directory.resolve("older").apply {
            RandomAccessFile(this, "rw").use { it.setLength(399L * 1024 * 1024) }
            setLastModified(1_000)
        }
        val newer = directory.resolve("newer").apply {
            RandomAccessFile(this, "rw").use { it.setLength(1024L * 1024) }
            setLastModified(2_000)
        }
        val newest = cache.put("newest", 1) { it.write(42) }

        assertFalse(oldest.exists())
        assertArrayEquals(byteArrayOf(42), newest?.readBytes())
        assertFalse(newer.length() == 0L)
    }
}
