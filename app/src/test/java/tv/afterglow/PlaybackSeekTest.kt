package tv.afterglow

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSeekTest {
    @Test
    fun liveRewindClampsToStartOfWindow() {
        assertEquals(0L, liveRewindTarget(10_000L, 30_000L))
        assertEquals(0L, liveRewindTarget(0L, 30_000L))
        assertEquals(0L, liveRewindTarget(30_000L, 30_000L))
    }

    @Test
    fun liveRewindJumpsBackUpTo30Seconds() {
        assertEquals(15_000L, liveRewindTarget(45_000L, 30_000L))
        assertEquals(70_000L, liveRewindTarget(100_000L, 30_000L))
    }
}
