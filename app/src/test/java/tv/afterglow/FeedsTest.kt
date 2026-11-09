package tv.afterglow

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class FeedsTest {
    private val base = "https://example.test/a/lineup.m3u".toHttpUrl()

    @Test fun importsQuotedAttributesAndRelativeStreams() {
        val entries = parseM3u(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="news-1" tvg-logo="logo.png" group-title="News, Local",Channel, One
            /live/one.m3u8
            #EXTINF:-1 tvg-name="Second channel"
            #EXTGRP:Other
            second?output=ts
            #EXTINF:-1,Invalid
            ftp://example.test/unsupported
            """.trimIndent().reader(), base
        )
        assertEquals(2, entries.size)
        assertEquals("Channel, One", entries[0].title)
        assertEquals("News, Local", entries[0].group)
        assertEquals("news-1", entries[0].channelId)
        assertEquals("https://example.test/a/logo.png", entries[0].image)
        assertEquals("https://example.test/live/one.m3u8", entries[0].url)
        assertEquals("application/x-mpegURL", entries[0].mime)
        assertEquals("Other", entries[1].group)
        assertEquals("video/mp2t", entries[1].mime)
    }

    @Test fun classifiesEpisodesAndKeepsUnnumberedSeriesPlayable() {
        val entries = listOf(
            FeedEntry("ep2", "North Star S02E02 Drift", "", "TV"),
            FeedEntry("ep1", "North Star S01E01", "", "TV"),
            FeedEntry("extra", "Behind the scenes", "", "TV"),
            FeedEntry("movie", "A film", "", "Movies"),
            FeedEntry("live", "News", "", "General"),
            FeedEntry("hidden", "Hidden", "", "Hidden")
        )
        val titles = playlistTitles(Playlist("https://example.test",
            groups = mapOf("TV" to Kind.SERIES, "Movies" to Kind.MOVIE, "Hidden" to Kind.HIDDEN)), entries)
        assertEquals(setOf(Kind.LIVE, Kind.MOVIE, Kind.SERIES), titles.map(Title::kind).toSet())
        assertEquals(listOf("ep1", "ep2"), titles.first { it.name == "North Star" }.episodes.map(Episode::id))
        assertEquals("extra", titles.first { it.name == "Behind the scenes" }.episodes.single().id)
        assertEquals(4, titles.size)
    }

    @Test fun readsGzippedXmltvWithOffsetsAndFiltersToMatchedChannels() {
        val now = Instant.parse("2026-11-09T12:30:00Z").toEpochMilli()
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE tv SYSTEM "https://example.test/xmltv.dtd">
            <tv>
              <programme channel="known" start="20261109130000 +0100" stop="20261109140000 +0100">
                <title>News &amp; weather</title><desc>Summary</desc>
              </programme>
              <programme channel="unknown" start="20261109130000 +0100" stop="20261109140000 +0100">
                <title>Do not match</title>
              </programme>
              <programme channel="known" start="bad" stop="20261109140000 +0100"><title>Invalid</title></programme>
            </tv>
        """.trimIndent()
        val compressed = ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(xml.toByteArray()) }
        }.toByteArray()
        val guide = openFeed(compressed.inputStream(), 4096).use { parseXmltv(it, setOf("known"), now) }
        assertEquals("News & weather", guide.single().title)
        assertEquals(Instant.parse("2026-11-09T12:00:00Z").toEpochMilli(), guide.single().start)
    }

    @Test fun rejectsInvalidFeedsAndOversizedInput() {
        assertThrows(FeedError::class.java) { parseM3u("<html>".reader(), base) }
        assertThrows(FeedError::class.java) { parseM3u("#EXTM3U\n#EXT-X-ENDLIST".reader(), base) }
        assertThrows(FeedError::class.java) { parseXmltv("<broken/>".byteInputStream(), emptySet()) }
        assertThrows(FeedError::class.java) { openFeed("too long".byteInputStream(), 4).readBytes() }
    }

    @Test fun matchesOnlyAdvertisedResourceTypesAndIdPrefixes() {
        val addon = Addon("https://example.test/manifest.json", "Fixture", false, listOf("movie"),
            listOf("tt"), listOf(Resource("meta"), Resource("stream", listOf("series"), listOf("test:"))),
            emptyList())
        assertTrue(addon.supports("meta", "movie", "tt123"))
        assertFalse(addon.supports("meta", "movie", "other123"))
        assertTrue(addon.supports("stream", "series", "test:1:2"))
        assertFalse(addon.supports("stream", "movie", "tt123"))
    }
}
