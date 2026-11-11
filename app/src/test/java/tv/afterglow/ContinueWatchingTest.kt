package tv.afterglow

import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueWatchingTest {
    private val show = Title("show", Kind.SERIES, "Show")
    private val episodeOne = Episode("one", "Episode one")
    private val episodeTwo = Episode("two", "Episode two")

    @Test
    fun showsOnlyTheMostRecentlyWatchedEpisode() {
        val old = Resume(show, episodeOne, 120_000, 1, 300_000)
        val recent = Resume(show, episodeTwo, 180_000, 2, 300_000)
        val sourceSelection = Resume(show, Episode("three", "Episode three"), 0, 3)

        assertEquals(listOf(recent), latestResumes(listOf(old, sourceSelection, recent)))
    }

    @Test
    fun completionDoesNotBringBackAnOlderEpisode() {
        val old = Resume(show, episodeOne, 120_000, 1, 300_000)
        val recent = Resume(show, episodeTwo, 240_000, 2, 300_000)

        assertEquals(listOf(recent), latestResumes(listOf(old, recent)))
        assertEquals(emptyList<Resume>(), latestResumes(listOf(old, recent.copy(position = 240_001))))
        assertEquals(emptyList<Resume>(), latestResumes(listOf(old, recent.copy(ended = true))))
    }
}
