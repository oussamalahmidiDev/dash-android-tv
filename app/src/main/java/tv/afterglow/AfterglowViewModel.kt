package tv.afterglow

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class Tab(val title: String) {
    HOME("Home"), SEARCH("Search"), MOVIES("Movies"), SERIES("Series"), LIVE("Live TV"), SETTINGS("Settings")
}

internal enum class SettingsSection(val label: String) {
    IPTV("IPTV"), ADDONS("Stremio add-ons"), PLAYBACK("Playback")
}
internal enum class StatusPhase { WORKING, SUCCESS, ERROR }
internal data class SettingsStatus(val section: SettingsSection, val message: String, val phase: StatusPhase)

internal data class Details(
    val title: Title, val loading: Boolean = true, val episode: Episode? = null,
    val showSources: Boolean = false, val findingSources: Boolean = false,
    val sources: List<Source> = emptyList(), val error: String? = null
)

internal class AfterglowViewModel(application: Application) : AndroidViewModel(application) {
    private val library = Library(application)
    private val progressWrites = Mutex()
    private val recentWrites = Mutex()
    private var detailsJob: Job? = null
    private var sourceJob: Job? = null
    private var searchJob: Job? = null

    var ready by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null); private set
    var settingsStatus by mutableStateOf<SettingsStatus?>(null); private set
    var local by mutableStateOf(LocalLibrary()); private set
    var addons by mutableStateOf(emptyList<Addon>()); private set
    var loadingCinemeta by mutableStateOf(false); private set
    var shelves by mutableStateOf(emptyList<Shelf>()); private set
    var favorites by mutableStateOf(emptyList<Title>()); private set
    var progress by mutableStateOf(emptyList<Resume>()); private set
    var recentChannels by mutableStateOf(emptyList<String>()); private set
    var p2p by mutableStateOf(false); private set
    var tab by mutableStateOf(Tab.HOME); private set
    var details by mutableStateOf<Details?>(null); private set
    var playback by mutableStateOf<PlayRequest?>(null); private set
    var liveMinimized by mutableStateOf(false); private set
    var sourceSetup by mutableStateOf<SettingsSection?>(null); private set
    var query by mutableStateOf(""); private set
    var searching by mutableStateOf(false); private set
    var results by mutableStateOf(emptyList<Title>()); private set
    var liveGroup by mutableStateOf<String?>(null); private set
    var catalog by mutableStateOf<String?>(null); private set
    var classify by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            try {
                val saved = library.restore()
                local = saved.local
                addons = saved.addons
                shelves = saved.shelves
                favorites = saved.favorites
                progress = saved.progress
                recentChannels = saved.recentChannels
                p2p = saved.p2p
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = "Saved settings couldn’t be opened."
            } finally {
                ready = true
            }
            loadCinemetaCatalogs()
        }
    }

    private suspend fun loadCinemetaCatalogs() {
        val addon = addons.firstOrNull { it.url == CINEMETA_MANIFEST } ?: return
        val prefix = "${addon.key}/"
        val cached = shelves.filter { it.id.startsWith(prefix) }
        if (cached.size >= addon.catalogs.count { it.required.isEmpty() }) return

        loadingCinemeta = true
        try {
            val (loaded, _) = library.shelves(listOf(addon), cached)
            if (loaded.isEmpty()) return

            // Download without blocking Settings, then merge against the latest saved sources.
            snapshotFlow { busy }.first { !it }
            if (addon !in addons) return
            val existing = shelves.mapTo(hashSetOf(), Shelf::id)
            val newShelves = loaded.filterNot { it.id in existing }
            if (newShelves.isEmpty()) return

            busy = true
            try {
                val updated = shelves + newShelves
                library.saveAddons(addons, updated)
                shelves = updated
            } finally {
                busy = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Saved catalogs remain available and the user can refresh in Settings.
        } finally {
            loadingCinemeta = false
        }
    }

    fun go(next: Tab) {
        detailsJob?.cancel()
        sourceJob?.cancel()
        tab = next
        catalog = null
        details = null
        sourceSetup = null
        classify = false
        notice = null
    }

    fun openCatalog(id: String?) { catalog = id; notice = null }
    fun group(value: String?) { liveGroup = value }
    fun showGroups(show: Boolean) { classify = show; notice = null }

    private fun change(fallback: String, action: suspend () -> Unit) {
        if (busy || !ready) return
        busy = true
        notice = null
        viewModelScope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { notice = failure.display(fallback) }
            finally { busy = false }
        }
    }

    private fun settingsChange(
        section: SettingsSection, pending: String, fallback: String, action: suspend () -> String
    ) = change(fallback) {
        settingsStatus = SettingsStatus(section, pending, StatusPhase.WORKING)
        try {
            settingsStatus = SettingsStatus(section, action(), StatusPhase.SUCCESS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            settingsStatus = SettingsStatus(section, failure.display(fallback), StatusPhase.ERROR)
            throw failure
        }
    }

    fun installAddon(url: String, http: Boolean, success: () -> Unit) =
        settingsChange(SettingsSection.ADDONS, "Installing add-on…", "Couldn’t install this add-on. Check its URL and try again.") {
            val addon = library.install(url, http)
            val next = addons.filterNot { it.key == addon.key } + addon
            val hasCatalogs = addon.catalogs.any { it.required.isEmpty() }
            val retained = if (hasCatalogs) shelves else shelves.filterNot { it.id.startsWith("${addon.key}/") }
            library.saveAddons(next, retained)
            addons = next
            shelves = retained
            success()
            val catalogFailed = hasCatalogs && refreshCatalogsInternal() > 0
            if (catalogFailed) "Added ${addon.name}. Some catalogs couldn’t refresh."
            else "Added ${addon.name}."
        }

    fun removeAddon(addon: Addon) = change("Couldn’t remove the add-on.") {
        val next = addons.filterNot { it.key == addon.key }
        val nextShelves = shelves.filterNot { it.id.startsWith("${addon.key}/") }
        library.saveAddons(next, nextShelves)
        addons = next
        shelves = nextShelves
        notice = "Removed ${addon.name}."
    }

    fun refreshCatalogs() =
        settingsChange(SettingsSection.ADDONS, "Refreshing catalogs…", "Couldn’t refresh add-on catalogs.") {
            if (refreshCatalogsInternal() > 0) "Some catalogs couldn’t refresh. Saved titles are still available."
            else "Catalogs refreshed."
        }

    private suspend fun refreshCatalogsInternal(): Int {
        val (updated, failed) = library.shelves(addons, shelves)
        library.saveAddons(addons, updated)
        shelves = updated
        return failed
    }

    fun nextPage(shelf: Shelf) = change("Couldn’t load more titles. Try again.") {
        val updated = library.next(addons, shelf)
        val next = shelves.map { if (it.id == updated.id) updated else it }
        library.saveAddons(addons, next)
        shelves = next
    }

    fun importPlaylist(url: String, guide: String, http: Boolean, showGroups: Boolean) =
        settingsChange(SettingsSection.IPTV, if (showGroups) "Importing IPTV feeds…" else "Refreshing IPTV feeds…",
            "Couldn’t import the feeds. Check the URLs and try again.") {
            val prior = local.playlist
            val groups = prior?.groups?.takeIf { prior.url == url.httpUrl()?.toString() }.orEmpty()
            val hadPlaylist = prior != null
            local = library.import(Playlist(url.trim(), guide.trim(), http, groups))
            liveGroup = null
            classify = showGroups
            if (hadPlaylist) "Playlist refreshed. ${local.entries.size} entries available."
            else "Playlist imported. ${local.entries.size} entries available."
        }

    fun setPlaylistHttp(enabled: Boolean) =
        settingsChange(SettingsSection.IPTV, "Saving IPTV connection setting…", "Couldn’t save the IPTV connection setting.") {
            local = library.setPlaylistHttp(local, enabled)
            if (enabled) "HTTP allowed for this playlist." else "HTTPS required for this playlist."
        }

    fun refreshPlaylist() {
        val playlist = local.playlist ?: return
        importPlaylist(playlist.url, playlist.guide, playlist.allowHttp, false)
    }

    fun classify(group: String, kind: Kind) = change("Couldn’t save the playlist group.") {
        local = library.classify(local, group, kind)
        if (liveGroup == group && kind != Kind.LIVE) liveGroup = null
    }

    fun removePlaylist() = change("Couldn’t remove the playlist.") {
        library.removePlaylist()
        local = LocalLibrary()
        liveGroup = null
        classify = false
        notice = "Playlist removed."
    }

    fun setPeerPlayback(enabled: Boolean) =
        settingsChange(SettingsSection.PLAYBACK, "Saving playback setting…", "Couldn’t save this setting.") {
            library.p2p(enabled)
            p2p = enabled
            if (enabled) "Torrent playback enabled." else "Torrent playback disabled."
        }

    fun search(text: String) {
        val previousTerm = query.trim()
        query = text
        val term = text.trim()
        if (term == previousTerm) return

        searchJob?.cancel()
        results = emptyList()
        searching = term.isNotEmpty()
        if (!searching) return
        searchJob = viewModelScope.launch {
            try {
                val cached = library.searchCached(local, shelves, term)
                results = cached
                if (addons.any { addon -> addon.catalogs.any(Catalog::search) }) {
                    delay(300)
                    val found = library.searchAddons(addons, term)
                    results = (cached + found).distinctBy(Title::key)
                }
            } finally {
                if (isActive) searching = false
            }
        }
    }

    fun markChannelWatched(request: PlayRequest) {
        if (request.title.kind != Kind.LIVE || playback?.token != request.token) return
        val id = request.title.id
        val updated = (listOf(id) + recentChannels.filterNot { it == id }).take(20)
        if (updated == recentChannels) return
        recentChannels = updated
        viewModelScope.launch {
            recentWrites.withLock { attempt { library.recentChannels(recentChannels) } }
        }
    }

    suspend fun featuredDetails(title: Title): Title = attempt { library.detail(addons, title) } ?: title

    fun open(title: Title, resume: Resume? = null) {
        if (title.kind == Kind.LIVE) {
            val entry = local.entryIndex[title.id.removePrefix("iptv:")] ?: return
            start(title, null, Source("IPTV", entry.title, entry.url, mime = entry.mime,
                allowHttp = local.playlist?.allowHttp == true))
            return
        }
        detailsJob?.cancel()
        sourceJob?.cancel()
        details = Details(title)
        detailsJob = viewModelScope.launch {
            val expanded = attempt { library.detail(addons, title) } ?: title
            if (details?.title?.key == title.key) {
                details = details?.copy(title = expanded, loading = false)
                if (resume != null) pickSources(resume.episode)
            }
        }
    }

    fun pickSources(episode: Episode?) {
        val title = details?.title ?: return
        sourceJob?.cancel()
        details = details?.copy(episode = episode, showSources = true, findingSources = true,
            sources = emptyList(), error = null)
        sourceJob = viewModelScope.launch {
            try {
                val sources = library.sources(addons, local, title, episode)
                details = details?.copy(findingSources = false, sources = sources)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                details = details?.copy(findingSources = false, error = failure.display("Couldn’t load streams."))
            }
        }
    }

    fun backFromDetails() {
        sourceJob?.cancel()
        if (details?.showSources == true) details = details?.copy(showSources = false)
        else {
            detailsJob?.cancel()
            details = null
        }
    }

    fun openSourceSetup(section: SettingsSection) {
        if (details?.showSources == true) sourceSetup = section
    }

    fun closeSourceSetup() {
        sourceSetup = null
        details?.let { pickSources(it.episode) }
    }

    fun selectSource(source: Source) {
        val active = details ?: return
        if (source.url == null && !p2p) {
            openSourceSetup(SettingsSection.PLAYBACK)
            return
        }
        start(active.title, active.episode, source)
    }

    private fun start(title: Title, episode: Episode?, source: Source) {
        val key = playbackKey(title, episode)
        liveMinimized = false
        playback = PlayRequest(title, episode, source, progress.firstOrNull { it.key == key }?.position ?: 0)
    }

    fun minimizeLive() {
        if (playback?.title?.kind == Kind.LIVE) liveMinimized = true
    }

    fun expandLive() { liveMinimized = false }

    fun exitPlayback() {
        playback = null
        liveMinimized = false
    }

    fun toggleFavorite(title: Title, onSaved: (Boolean) -> Unit = {}) {
        val added = favorites.none { it.key == title.key }
        change("Couldn’t update favorites.") {
            library.favorite(title, added)
            favorites = if (added) favorites + title else favorites.filterNot { it.key == title.key }
            onSaved(added)
        }
    }

    fun record(request: PlayRequest, position: Long, duration: Long, ended: Boolean) {
        if (request.title.kind == Kind.LIVE || (!ended && position < 1_000L)) return
        val key = playbackKey(request.title, request.episode)
        val knownDuration = duration.takeIf { it > 0 } ?: progress.firstOrNull { it.key == key }?.duration
        val item = if (ended) null else Resume(
            request.title, request.episode, position, System.currentTimeMillis(), knownDuration
        )
        progress = listOfNotNull(item) + progress.filterNot { it.key == key }
        viewModelScope.launch {
            progressWrites.withLock { attempt { library.progress(key, item) } }
        }
    }
}

private fun Exception.display(fallback: String) = (this as? FeedError)?.message ?: fallback
