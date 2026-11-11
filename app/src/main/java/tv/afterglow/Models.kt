package tv.afterglow

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

internal val json = Json { ignoreUnknownKeys = true }

@Serializable
internal enum class Kind(val label: String) {
    LIVE("Live TV"), MOVIE("Movies"), SERIES("Series"), HIDDEN("Hidden")
}

@Serializable
internal data class Playlist(
    val url: String, val guide: String = "", val allowHttp: Boolean = false,
    val groups: Map<String, Kind> = emptyMap()
)

@Serializable
internal data class FeedEntry(
    val id: String, val title: String, val url: String, val group: String,
    val channelId: String = "", val image: String = "", val mime: String? = null
)

@Serializable
internal data class Program(val channel: String, val start: Long, val end: Long, val title: String)

@Serializable
internal data class Title(
    val id: String, val kind: Kind, val name: String,
    val poster: String = "", val backdrop: String = "", val description: String = "",
    val year: String = "", val provider: String = "", val group: String = "",
    val episodes: List<Episode> = emptyList(), val allowHttp: Boolean = false, val iptv: Boolean = false,
    val genre: String = "", val logo: String = "", val runtime: String = "",
    val cast: List<String> = emptyList()
) {
    val key get() = "${if (iptv) "playlist/" else ""}${kind.name}:$id"
}

@Serializable
internal data class Episode(
    val id: String, val name: String, val season: Int? = null, val number: Int? = null,
    val thumbnail: String = ""
)

@Serializable
internal data class Catalog(
    val id: String, val type: String, val name: String, val search: Boolean,
    val skip: Boolean, val required: List<String>
)

@Serializable
internal data class Resource(
    val name: String, val types: List<String> = emptyList(),
    val prefixes: List<String> = emptyList()
)

@Serializable
internal data class Addon(
    val url: String, val name: String, val allowHttp: Boolean,
    val types: List<String>, val prefixes: List<String>,
    val resources: List<Resource>, val catalogs: List<Catalog>
) {
    val key get() = digest(url)
    fun supports(resource: String, type: String, id: String): Boolean =
        resources.any {
            if (it.name != resource) return@any false
            val supportedTypes = it.types.ifEmpty { types }
            val supportedPrefixes = it.prefixes.ifEmpty { prefixes }
            (supportedTypes.isEmpty() || type in supportedTypes) &&
                (supportedPrefixes.isEmpty() || supportedPrefixes.any(id::startsWith))
        }
}

@Serializable
internal data class Subtitle(
    val url: String, val language: String, val label: String = "", val source: String = ""
)

@Serializable
internal data class Source(
    val provider: String, val label: String, val url: String? = null,
    val magnet: String? = null, val fileIndex: Int? = null,
    val mime: String? = null, val subtitles: List<Subtitle> = emptyList(),
    val allowHttp: Boolean = false, val headers: Map<String, String> = emptyMap(),
    val description: String = "", val seeds: Int? = null
)

@Serializable
internal data class Shelf(val id: String, val name: String, val kind: Kind, val titles: List<Title>,
                          val nextOffset: Int? = null)

@Serializable
internal data class Resume(
    val title: Title, val episode: Episode?, val position: Long, val updated: Long,
    val duration: Long? = null, val source: Source? = null,
    val subtitleUrl: String? = null, val subtitleLanguage: String? = null, val subtitleDelayMs: Long = 0L,
    val ended: Boolean = false
) {
    val key get() = playbackKey(title, episode)
    val canResume get() = !ended && position >= 1_000L &&
        (duration == null || duration <= 0 || duration - position >= 60_000L)
}

@Serializable
internal data class RecentChannel(val id: String, val updated: Long)

internal fun latestResumes(progress: List<Resume>): List<Resume> = progress.asSequence()
    .filter { it.ended || it.position >= 1_000L }
    .sortedByDescending(Resume::updated)
    .distinctBy { it.title.key }
    .filter(Resume::canResume)
    .toList()

internal data class PlayRequest(val title: Title, val episode: Episode?, val source: Source,
                                val resume: Long, val token: Long = System.nanoTime())

internal fun playbackKey(title: Title, episode: Episode?) = "${title.key}/${episode?.id.orEmpty()}"
internal fun Kind.stremioType() = if (this == Kind.SERIES) "series" else "movie"
internal fun kindFor(type: String) = when (type) { "movie" -> Kind.MOVIE; "series" -> Kind.SERIES; else -> null }

private val HEX_CHARS = "0123456789abcdef".toCharArray()

internal fun digest(value: String): String {
    val hash = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val chars = CharArray(32)
    for (i in 0 until 16) {
        val b = hash[i].toInt() and 0xFF
        chars[i * 2] = HEX_CHARS[b ushr 4]
        chars[i * 2 + 1] = HEX_CHARS[b and 0x0F]
    }
    return String(chars)
}
