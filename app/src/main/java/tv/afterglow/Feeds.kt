package tv.afterglow

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.GZIPInputStream

internal class FeedError(message: String) : IOException(message)

internal fun String.httpUrl(): HttpUrl? =
    trim().toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() }

internal class BoundedInput(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
    override fun read(): Int {
        val byte = super.read()
        if (byte >= 0 && --remaining < 0) throw FeedError("This feed exceeds the import limit.")
        return byte
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, minOf(length.toLong(), remaining + 1).toInt())
        if (count > 0) remaining -= count
        if (remaining < 0) throw FeedError("This feed exceeds the import limit.")
        return count
    }
}

internal fun openFeed(input: InputStream, limit: Long): InputStream {
    val buffered = BufferedInputStream(input)
    buffered.mark(2)
    val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
    buffered.reset()
    return BoundedInput(if (gzip) GZIPInputStream(buffered) else buffered, limit)
}

private val attributes = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")
private val episodePattern = Regex("""^(.+?)[\s._-]+S(\d{1,3})E(\d{1,4})(?!\d)[\s._:-]*(.*)$""", RegexOption.IGNORE_CASE)
internal const val OTHER_GROUP = "Other channels"

internal fun parseM3u(reader: Reader, base: HttpUrl): List<FeedEntry> {
    data class Pending(val name: String, val attrs: Map<String, String>, val group: String = "")
    var pending: Pending? = null
    var header = false
    val found = linkedMapOf<String, FeedEntry>()
    reader.buffered().lineSequence().forEach { raw ->
        val line = raw.trim().removePrefix("\uFEFF")
        if (line.isEmpty()) return@forEach
        if (!header) {
            if (!line.startsWith("#EXTM3U")) throw FeedError("The playlist is missing its M3U header.")
            header = true
            return@forEach
        }
        when {
            line.startsWith("#EXTINF:", true) -> {
                var quote: Char? = null
                val comma = line.indexOfFirst { ch ->
                    if (ch == quote) quote = null
                    else if (quote == null && (ch == '"' || ch == '\'')) quote = ch
                    ch == ',' && quote == null
                }
                val attrs = attributes.findAll(line.substringBeforeIndex(comma)).associate {
                    it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues.drop(2).firstOrNull(String::isNotEmpty).orEmpty()
                }
                val name = (if (comma >= 0) line.substring(comma + 1).trim() else "")
                    .ifEmpty { attrs["tvg-name"].orEmpty() }
                pending = Pending(name, attrs)
            }
            line.startsWith("#EXTGRP:", true) -> pending = pending?.copy(group = line.substringAfter(':').trim())
            line.startsWith("#") -> Unit
            else -> {
                val entry = pending
                pending = null
                if (entry == null || entry.name.isBlank()) return@forEach
                val url = base.resolve(line)?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
                    ?: return@forEach
                val group = entry.attrs["group-title"].orEmpty().ifBlank { entry.group.ifBlank { OTHER_GROUP } }
                val channel = entry.attrs["tvg-id"].orEmpty().trim()
                val id = digest("$group\u0000${entry.name}\u0000$channel\u0000${url.host}${url.encodedPath.substringAfterLast('/')}")
                val logo = entry.attrs["tvg-logo"].orEmpty().takeIf(String::isNotBlank)
                    ?.let(base::resolve)?.toString().orEmpty()
                found[id] = FeedEntry(id, entry.name, url.toString(), group, channel, logo, guessMime(url))
                if (found.size > 100_000) throw FeedError("The playlist exceeds 100,000 entries.")
            }
        }
    }
    if (found.isEmpty()) throw FeedError("The playlist has no playable entries.")
    return found.values.toList()
}

private fun String.substringBeforeIndex(index: Int): String = if (index < 0) this else substring(0, index)

internal fun guessMime(url: HttpUrl): String? = when (url.encodedPath.substringAfterLast('.').lowercase(Locale.ROOT)) {
    "m3u8" -> "application/x-mpegURL"
    "mpd" -> "application/dash+xml"
    "ts", "mts", "m2ts" -> "video/mp2t"
    "mp4", "m4v" -> "video/mp4"
    "mkv" -> "video/x-matroska"
    "webm" -> "video/webm"
    else -> when (url.queryParameter("output")?.lowercase(Locale.ROOT)) {
        "m3u8", "hls" -> "application/x-mpegURL"
        "ts", "mpegts" -> "video/mp2t"
        else -> null
    }
}

internal fun playlistTitles(source: Playlist, entries: List<FeedEntry>): List<Title> {
    val episodes = linkedMapOf<Pair<String, String>, MutableList<Pair<FeedEntry, Episode>>>()
    val showNames = mutableMapOf<Pair<String, String>, String>()
    val singles = mutableListOf<Title>()
    for (entry in entries) {
        val kind = source.groups[entry.group] ?: Kind.LIVE
        if (kind == Kind.HIDDEN) continue
        val match = episodePattern.matchEntire(entry.title).takeIf { kind == Kind.SERIES }
        if (match == null) {
            val standalone = if (kind == Kind.SERIES) listOf(Episode(entry.id, entry.title)) else emptyList()
            singles += Title("iptv:${entry.id}", kind, entry.title, entry.image, provider = "IPTV",
                group = entry.group, episodes = standalone, allowHttp = source.allowHttp, iptv = true)
        } else {
            val (show, season, number, label) = match.destructured
            val displayName = show.replace(Regex("[._]+"), " ").trim()
            val key = entry.group to displayName.lowercase(Locale.ROOT)
            showNames.putIfAbsent(key, displayName)
            val episode = Episode(entry.id, label.ifBlank { "Episode ${number.toInt()}" }, season.toInt(), number.toInt())
            episodes.getOrPut(key) { mutableListOf() }.add(entry to episode)
        }
    }
    return singles + episodes.map { (key, items) ->
        val (group, normalized) = key
        val name = showNames.getValue(key)
        Title("iptv-show:${digest("$group/$normalized")}", Kind.SERIES, name,
            items.firstNotNullOfOrNull { it.first.image.takeIf(String::isNotEmpty) }.orEmpty(),
            provider = "IPTV", group = group, allowHttp = source.allowHttp,
            episodes = items.map { it.second }.sortedWith(compareBy({ it.season }, { it.number })), iptv = true)
    }
}

private val xmlTime = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
private val xmlZone = Regex("[+-]\\d{4}")

internal fun xmlTimestamp(value: String?): Long? {
    val parts = value?.trim()?.split(Regex("\\s+")) ?: return null
    val clock = parts.firstOrNull() ?: return null
    if (clock.length !in setOf(12, 14)) return null
    val zone = parts.getOrNull(1)
    if (zone != null && !xmlZone.matches(zone) && zone !in listOf("Z", "UTC", "GMT")) return null
    return runCatching {
        val offset = if (zone != null && xmlZone.matches(zone)) ZoneOffset.of(zone) else ZoneOffset.UTC
        LocalDateTime.parse(clock.padEnd(14, '0'), xmlTime).toInstant(offset).toEpochMilli()
    }.getOrNull()
}

internal fun parseXmltv(
    input: InputStream, channels: Set<String>, now: Long = Instant.now().toEpochMilli()
): List<Program> {
    val parser = XmlPullParserFactory.newInstance().newPullParser()
    if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    }
    parser.setInput(input, null)
    var event = parser.eventType
    var root = false
    var depth = -1
    var channel = ""
    var title = ""
    var start = 0L
    var end = 0L
    val programs = mutableListOf<Program>()
    val lower = now - 12 * 3_600_000L
    val upper = now + 7 * 86_400_000L
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            if (parser.depth == 1) {
                if (parser.name != "tv") throw FeedError("The guide is not an XMLTV document.")
                root = true
            }
            if (parser.name == "programme" && parser.depth == 2) {
                val id = parser.getAttributeValue(null, "channel").orEmpty()
                val from = xmlTimestamp(parser.getAttributeValue(null, "start"))
                val to = xmlTimestamp(parser.getAttributeValue(null, "stop"))
                if (id in channels && from != null && to != null && from < to && to > lower && from < upper) {
                    depth = parser.depth
                    channel = id
                    start = from
                    end = to
                    title = ""
                }
            }
            if (depth > 0 && parser.depth == depth + 1 && parser.name == "title" && title.isEmpty()) {
                title = parser.nextText().trim().take(400)
            }
        }
        if (event == XmlPullParser.END_TAG && parser.depth == depth && parser.name == "programme") {
            if (title.isNotBlank()) programs += Program(channel, start, end, title)
            depth = -1
            if (programs.size > 180_000) throw FeedError("The guide exceeds the programme limit.")
        }
        event = parser.next()
    }
    if (!root) throw FeedError("The guide is not an XMLTV document.")
    return programs.distinct().sortedBy(Program::start)
}
