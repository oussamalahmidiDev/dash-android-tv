package tv.afterglow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import java.util.Locale

private fun JsonElement?.obj() = this as? JsonObject
private fun JsonElement?.value() = (this as? JsonPrimitive)?.content
private fun JsonObject.str(name: String) = this[name].value().orEmpty()
private fun JsonObject.items(name: String) = (this[name] as? JsonArray).orEmpty()
private fun JsonObject.strings(name: String) = items(name).mapNotNull { it.value() }
private fun JsonObject.number(name: String) = (this[name] as? JsonPrimitive)?.intOrNull

internal const val CINEMETA_MANIFEST = "https://v3-cinemeta.strem.io/manifest.json"

internal val cinemetaDefault = Addon(
    url = CINEMETA_MANIFEST,
    name = "Cinemeta",
    allowHttp = false,
    types = listOf("movie", "series"),
    prefixes = listOf("tt"),
    resources = listOf(Resource("catalog"), Resource("meta"), Resource("addon_catalog")),
    catalogs = listOf("movie", "series").flatMap { type ->
        listOf(
            Catalog("top", type, "Popular", search = true, skip = true, required = emptyList()),
            Catalog("year", type, "New", search = false, skip = true, required = listOf("genre")),
            Catalog("imdbRating", type, "Featured", search = false, skip = true, required = emptyList())
        )
    } + listOf(
        Catalog("last-videos", "series", "Last videos", false, false, listOf("lastVideosIds")),
        Catalog("calendar-videos", "series", "Calendar videos", false, false, listOf("calendarVideosIds"))
    )
)

internal val DEFAULT_TRACKERS = listOf(
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://opentracker.i2p.rocks:6969/announce",
    "udp://explodie.org:6969/announce",
    "http://tracker.openbittorrent.com:80/announce"
)

internal class Stremio {
    private fun read(url: HttpUrl, http: Boolean): Pair<HttpUrl, JsonObject> = Network.fetch(url, http) { response ->
        val body = response.body ?: throw FeedError("The add-on returned an empty response.")
        val text = openFeed(body.byteStream(), 16L * 1024 * 1024).bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(text).obj() ?: throw FeedError("The add-on returned invalid data.")
        response.request.url to root
    }

    fun install(input: String, http: Boolean): Addon {
        val url = Network.checked(input.trim().replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://"), http)
        val (resolved, root) = read(url, http)
        if (resolved.pathSegments.lastOrNull() != "manifest.json" || root.str("id").isBlank() || root.str("name").isBlank()) {
            throw FeedError("Enter the add-on’s manifest.json URL.")
        }
        val resources = root.items("resources").mapNotNull {
            it.obj()?.let { obj -> Resource(obj.str("name"), obj.strings("types"), obj.strings("idPrefixes")) }
                ?: it.value()?.let(::Resource)
        }
        val catalogs = root.items("catalogs").mapNotNull { raw ->
            val obj = raw.obj() ?: return@mapNotNull null
            val id = obj.str("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val type = obj.str("type")
            if (kindFor(type) == null) return@mapNotNull null
            val extras = obj.items("extra").mapNotNull { it.obj() }
            val supported = extras.map { it.str("name") } + obj.strings("extraSupported")
            val required = extras.filter { it.str("isRequired") == "true" }.map { it.str("name") } +
                obj.strings("extraRequired")
            Catalog(id, type, obj.str("name").ifBlank { root.str("name") },
                "search" in supported, "skip" in supported, required)
        }
        return Addon(resolved.toString(), root.str("name"), http, root.strings("types"),
            root.strings("idPrefixes"), resources, catalogs)
    }

    private fun endpoint(addon: Addon, resource: String, type: String, id: String,
                         extra: Pair<String, String>? = null): HttpUrl {
        val base = Network.checked(addon.url, addon.allowHttp)
        val builder = base.newBuilder().removePathSegment(base.pathSize - 1)
        // Removing the sole path segment leaves a root placeholder.
        builder.addPathSegment(resource).addPathSegment(type)
        if (extra == null) builder.addPathSegment("$id.json") else {
            builder.addPathSegment(id)
            builder.addPathSegment("${extra.first}=${extra.second}.json")
        }
        return builder.build()
    }

    fun catalog(addon: Addon, spec: Catalog, offset: Int = 0, query: String? = null): Shelf {
        val extra = when {
            query != null -> "search" to query
            offset > 0 -> "skip" to offset.toString()
            else -> null
        }
        val (base, response) = read(endpoint(addon, "catalog", spec.type, spec.id, extra), addon.allowHttp)
        val kind = checkNotNull(kindFor(spec.type))
        val raw = response.items("metas")
        if (response["metas"] !is JsonArray) throw FeedError("The add-on returned an invalid catalog.")
        val titles = raw.mapNotNull { it.obj()?.toTitle(kind, addon, base) }.distinctBy(Title::key)
        return Shelf("${addon.key}/${spec.type}/${spec.id}", spec.name, kind, titles,
            (offset + raw.size).takeIf { spec.skip && query == null && raw.isNotEmpty() })
    }

    fun metadata(addon: Addon, title: Title): Title? {
        val (base, response) = read(endpoint(addon, "meta", title.kind.stremioType(), title.id), addon.allowHttp)
        return response["meta"].obj()?.toTitle(title.kind, addon, base)
    }

    fun streams(addon: Addon, type: String, id: String): List<Source> {
        val (base, response) = read(endpoint(addon, "stream", type, id), addon.allowHttp)
        return response.items("streams").mapNotNull { element ->
            val item = element.obj() ?: return@mapNotNull null
            val rawUrl = item.str("url")
            val url = rawUrl.takeIf(String::isNotBlank)?.httpUrl()
            val hash = item.str("infoHash").takeIf { it.matches(Regex("[0-9a-fA-F]{40}")) }
            val hashMagnet = hash?.let { value ->
                val existingTrackers = item.strings("sources").filter { it.startsWith("tracker:") }
                    .map { it.removePrefix("tracker:") }
                val trackers = (existingTrackers + DEFAULT_TRACKERS).distinct()
                buildString {
                    append("magnet:?xt=urn:btih:").append(value)
                    trackers.forEach { tracker ->
                        append("&tr=").append(java.net.URLEncoder.encode(tracker, "UTF-8"))
                    }
                }
            }
            val rawMagnet = rawUrl.takeIf { it.startsWith("magnet:?", true) }
            val magnet = rawMagnet?.let { raw ->
                if (!raw.contains("&tr=")) {
                    buildString {
                        append(raw)
                        DEFAULT_TRACKERS.forEach { tracker ->
                            append("&tr=").append(java.net.URLEncoder.encode(tracker, "UTF-8"))
                        }
                    }
                } else raw
            } ?: hashMagnet
            if (url == null && magnet == null) return@mapNotNull null
            val subtitles = item.items("subtitles").mapNotNull { raw ->
                val sub = raw.obj() ?: return@mapNotNull null
                val subUrl = sub.str("url").takeIf(String::isNotBlank)?.let(base::resolve) ?: return@mapNotNull null
                if (!subUrl.isHttps && !addon.allowHttp) return@mapNotNull null
                Subtitle(subUrl.toString(), sub.str("lang"))
            }
            val requestHeaders = item["behaviorHints"].obj()?.get("proxyHeaders").obj()?.get("request").obj()
            val headers = requestHeaders.orEmpty().mapNotNull { (name, raw) ->
                val value = raw.value() ?: return@mapNotNull null
                if (name.lowercase(Locale.ROOT) in setOf("user-agent", "referer", "origin", "authorization", "cookie") &&
                    value.none { it == '\r' || it == '\n' }) name to value else null
            }.toMap()
            val label = item.str("name").lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" · ")
            Source(addon.name, label.ifBlank { if (url != null) "Direct stream" else "Torrent" },
                url?.toString(), magnet, item.number("fileIdx"), url?.let(::guessMime), subtitles,
                addon.allowHttp, headers, description = item.str("description").ifBlank { item.str("title") })
        }.distinctBy { Triple(it.url, it.magnet, it.fileIndex) }
    }

    private fun JsonObject.toTitle(kind: Kind, addon: Addon, base: HttpUrl): Title? {
        val id = str("id").takeIf(String::isNotBlank) ?: return null
        fun JsonObject.image(field: String) = str(field).takeIf(String::isNotBlank)
            ?.let(base::resolve)?.toString().orEmpty()
        val episodes = items("videos").mapNotNull { raw ->
            val item = raw.obj() ?: return@mapNotNull null
            val episodeId = item.str("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val number = item.number("episode")
            Episode(episodeId, item.str("title").ifBlank { item.str("name") }
                .ifBlank { number?.let { "Episode $it" } ?: "Episode" }, item.number("season"), number,
                item.image("thumbnail"))
        }
        return Title(id, kind, str("name").ifBlank { id }, image("poster"), image("background"),
            str("description"), str("releaseInfo").ifBlank { str("year") }, addon.name, episodes = episodes,
            allowHttp = addon.allowHttp,
            genre = (strings("genres") + strings("genre") + str("genre")).firstOrNull(String::isNotBlank).orEmpty(),
            logo = image("logo"), runtime = str("runtime"), cast = strings("cast").filter(String::isNotBlank).take(5))
    }
}
