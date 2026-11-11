package tv.afterglow

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable
internal data class LocalLibrary(
    val playlist: Playlist? = null, val entries: List<FeedEntry> = emptyList(),
    val guide: List<Program> = emptyList()
) {
    val titles by lazy { playlist?.let { playlistTitles(it, entries) }.orEmpty() }
    val entryIndex by lazy { entries.associateBy(FeedEntry::id) }
    val schedule by lazy { guide.groupBy(Program::channel) }
    val channelIndex by lazy { titles.asSequence().filter { it.kind == Kind.LIVE }.associateBy(Title::id) }
}

internal data class SavedLibrary(
    val local: LocalLibrary, val addons: List<Addon>, val shelves: List<Shelf>,
    val favorites: List<Title>, val progress: List<Resume>, val p2p: Boolean, val recentChannels: List<RecentChannel>
)

internal class Library(context: Context) {
    private val store = Store(context)
    private val stremio = Stremio()
    private val slots = Semaphore(6)

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) { action() }

    suspend fun needsSetup(): Boolean = io {
        val completed = store.load<Boolean>("setup-completed")
        if (completed != null) return@io !completed
        val existingInstall = store.load<Boolean>("cinemeta-default-added") == true
        store.change("setup-completed" to json.encodeToString(existingInstall))
        !existingInstall
    }

    suspend fun completeSetup() = io { store.change("setup-completed" to json.encodeToString(true)) }

    suspend fun restore(): SavedLibrary = io {
        val local = LocalLibrary(store.load("playlist"), store.load("entries") ?: emptyList(),
            store.load("guide") ?: emptyList())
        // Build large indexes on the IO dispatcher before publishing the library.
        local.titles
        local.entryIndex
        local.schedule
        local.channelIndex
        val recents = store.load<List<RecentChannel>>("recent-channel-history")
            ?: store.load<List<String>>("recent-channels").orEmpty().mapIndexed { index, id ->
                RecentChannel(id, -index.toLong())
            }
        SavedLibrary(local, restoreAddons(), store.load("shelves") ?: emptyList(),
            store.list("favorite/").map { json.decodeFromString<Title>(it) },
            store.list("progress/").map { json.decodeFromString<Resume>(it) }.sortedByDescending(Resume::updated),
            store.load("p2p") ?: false, recents)
    }

    private suspend fun restoreAddons(): List<Addon> {
        val saved = store.load<List<Addon>>("addons").orEmpty()
        if (store.load<Boolean>("cinemeta-default-added") == true) return saved

        val addons = if (saved.any { it.url == CINEMETA_MANIFEST }) saved else saved + cinemetaDefault
        store.change("addons" to json.encodeToString(addons), "cinemeta-default-added" to json.encodeToString(true))
        return addons
    }

    suspend fun import(source: Playlist): LocalLibrary = io {
        val url = Network.checked(source.url, source.allowHttp)
        val entries = Network.fetch(url, source.allowHttp) { response ->
            val body = response.body ?: throw FeedError("The playlist is empty.")
            openFeed(body.byteStream(), 48L * 1024 * 1024).reader().use { parseM3u(it, response.request.url) }
        }
        val guide = if (source.guide.isBlank()) emptyList() else {
            val guideUrl = Network.checked(source.guide, source.allowHttp)
            val channels = entries.mapNotNullTo(hashSetOf()) { it.channelId.takeIf(String::isNotBlank) }
            Network.fetch(guideUrl, source.allowHttp) { response ->
                val body = response.body ?: throw FeedError("The guide is empty.")
                openFeed(body.byteStream(), 128L * 1024 * 1024).use { parseXmltv(it, channels) }
            }
        }
        val actual = source.copy(url = url.toString())
        val local = LocalLibrary(actual, entries, guide)
        local.titles
        local.entryIndex
        local.schedule
        store.change("playlist" to json.encodeToString(actual), "entries" to json.encodeToString(entries),
            "guide" to json.encodeToString(guide))
        local
    }

    suspend fun classify(local: LocalLibrary, group: String, kind: Kind): LocalLibrary = io {
        val previous = local.playlist ?: return@io local
        savePlaylist(local, previous.copy(groups = previous.groups + (group to kind)))
    }

    suspend fun setPlaylistHttp(local: LocalLibrary, enabled: Boolean): LocalLibrary = io {
        val previous = local.playlist ?: return@io local
        savePlaylist(local, previous.copy(allowHttp = enabled))
    }

    private suspend fun savePlaylist(local: LocalLibrary, updated: Playlist): LocalLibrary {
        val result = local.copy(playlist = updated)
        result.titles
        result.entryIndex
        result.schedule
        store.change("playlist" to json.encodeToString(updated))
        return result
    }

    suspend fun removePlaylist() = io { store.change("playlist" to null, "entries" to null, "guide" to null) }
    suspend fun install(url: String, http: Boolean) = io { stremio.install(url, http) }

    suspend fun saveAddons(addons: List<Addon>, shelves: List<Shelf>) = io {
        store.change("addons" to json.encodeToString(addons), "shelves" to json.encodeToString(shelves))
    }

    suspend fun shelves(addons: List<Addon>, previous: List<Shelf>): Pair<List<Shelf>, Int> = io {
        val old = previous.associateBy(Shelf::id)
        val requests = addons.flatMap { addon -> addon.catalogs.filter { it.required.isEmpty() }.map { addon to it } }
        val results = parallel(requests) { (addon, catalog) ->
            val result = attempt { stremio.catalog(addon, catalog) }
            (result ?: old["${addon.key}/${catalog.type}/${catalog.id}"]) to (result == null)
        }
        results.mapNotNull { it.first } to results.count { it.second }
    }

    suspend fun next(addons: List<Addon>, shelf: Shelf): Shelf = io {
        val offset = shelf.nextOffset ?: return@io shelf
        val addon = addons.firstOrNull { shelf.id.startsWith("${it.key}/") } ?: return@io shelf
        val spec = addon.catalogs.firstOrNull { shelf.id == "${addon.key}/${it.type}/${it.id}" } ?: return@io shelf
        val page = stremio.catalog(addon, spec, offset)
        val combined = (shelf.titles + page.titles).distinctBy(Title::key)
        shelf.copy(titles = combined, nextOffset = page.nextOffset.takeIf { combined.size > shelf.titles.size })
    }

    suspend fun searchCached(local: LocalLibrary, shelves: List<Shelf>, query: String): List<Title> = io {
        (local.titles.asSequence() + shelves.asSequence().flatMap { it.titles.asSequence() })
            .filter { it.name.contains(query, ignoreCase = true) }.distinctBy(Title::key).take(300).toList()
    }

    suspend fun searchAddons(addons: List<Addon>, query: String): List<Title> = io {
        val requests = addons.flatMap { addon ->
            addon.catalogs.filter { it.search && it.required.all { key -> key == "search" } }.map { addon to it }
        }
        parallel(requests) { (addon, spec) ->
            attempt { stremio.catalog(addon, spec, query = query).titles }.orEmpty()
        }.flatten()
    }

    suspend fun detail(addons: List<Addon>, title: Title): Title = io {
        if (title.iptv) return@io title
        val type = title.kind.stremioType()
        val matches = addons.filter { it.supports("meta", type, title.id) }
            .sortedByDescending { it.name == title.provider }
        val metas = parallel(matches) { addon -> attempt { stremio.metadata(addon, title) } }.filterNotNull()
        val preferred = metas.firstOrNull() ?: return@io title
        title.copy(
            name = preferred.name, poster = preferred.poster.ifBlank { title.poster },
            backdrop = preferred.backdrop.ifBlank { title.backdrop },
            description = preferred.description.ifBlank { title.description },
            genre = preferred.genre.ifBlank { title.genre },
            logo = preferred.logo.ifBlank { title.logo },
            runtime = preferred.runtime.ifBlank { title.runtime },
            cast = preferred.cast.ifEmpty { title.cast },
            year = preferred.year.ifBlank { title.year }, allowHttp = preferred.allowHttp || title.allowHttp,
            episodes = (title.episodes + metas.flatMap(Title::episodes)).groupBy(Episode::id).values
                .map { versions ->
                    val original = versions.first()
                    original.copy(thumbnail = versions.firstNotNullOfOrNull { it.thumbnail.takeIf(String::isNotBlank) }.orEmpty())
                }
                .sortedWith(compareBy({ it.season ?: Int.MAX_VALUE }, { it.number ?: Int.MAX_VALUE }))
        )
    }

    suspend fun sources(
        addons: List<Addon>, local: LocalLibrary, title: Title, episode: Episode?,
        onBatch: (suspend (List<Source>, Int) -> Unit)? = null
    ): List<Source> = io {
        val id = episode?.id ?: title.id
        if (title.iptv) {
            val entry = local.entryIndex[id.removePrefix("iptv:")] ?: return@io emptyList()
            val sources = listOf(Source("IPTV", entry.title, url = entry.url, mime = entry.mime,
                allowHttp = local.playlist?.allowHttp == true))
            onBatch?.invoke(sources, 1)
            return@io sources
        }
        val type = title.kind.stremioType()
        val matches = addons.filter { it.supports("stream", type, id) }
        val responses = parallel(matches) { addon ->
            val streams = attempt { stremio.streams(addon, type, id) }
            onBatch?.invoke(streams.orEmpty(), matches.size)
            streams
        }
        if (responses.isNotEmpty() && responses.all { it == null }) {
            throw FeedError("The add-ons couldn’t load streams. Try again.")
        }
        responses.filterNotNull().flatten()
    }

    suspend fun fetchSubtitles(
        addons: List<Addon>, title: Title, episode: Episode?
    ): List<Subtitle> = io {
        if (title.iptv) return@io emptyList()
        val id = episode?.id ?: title.id
        val type = title.kind.stremioType()
        val allAddons = (addons + openSubtitlesDefault).distinctBy(Addon::url)
        val matches = allAddons.filter { it.supports("subtitles", type, id) }
        val responses = parallel(matches) { addon ->
            attempt { stremio.subtitles(addon, type, id) }
        }
        responses.filterNotNull().flatten().distinctBy { it.url }
    }

    suspend fun favorite(title: Title, added: Boolean) = io {
        store.change("favorite/${digest(title.key)}" to if (added) json.encodeToString(title) else null)
    }

    suspend fun progress(key: String, resume: Resume?) = io {
        store.change("progress/${digest(key)}" to resume?.let { json.encodeToString(it) })
    }

    suspend fun clearProgress(keys: List<String>) = io {
        store.change(*keys.map { "progress/${digest(it)}" to null }.toTypedArray())
    }

    suspend fun recentChannels(channels: List<RecentChannel>) = io {
        store.change("recent-channel-history" to json.encodeToString(channels), "recent-channels" to null)
    }

    suspend fun p2p(enabled: Boolean) = io { store.change("p2p" to json.encodeToString(enabled)) }

    private suspend fun <T, R> parallel(values: List<T>, action: suspend (T) -> R): List<R> =
        coroutineScope { values.map { value -> async { slots.withPermit { action(value) } } }.awaitAll() }
}

internal inline fun <T> attempt(action: () -> T): T? = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
