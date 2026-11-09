package tv.afterglow

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun LiveScreen(model: AfterglowViewModel) {
    val local = model.local
    val channels = remember(local) { local.titles.filter { it.kind == Kind.LIVE } }
    val groups = remember(channels) { channels.map(Title::group).distinct() }
    val favoriteKeys = remember(model.favorites) { model.favorites.filter { it.kind == Kind.LIVE }.mapTo(hashSetOf(), Title::key) }
    val selected = model.liveGroup
    val visible = remember(channels, selected, favoriteKeys) {
        when (selected) {
            null -> channels
            FAVORITES_GROUP -> channels.filter { it.key in favoriteKeys }
            else -> channels.filter { it.group == selected }
        }
    }
    val resultFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }
    val backToCategories = Modifier.onPreviewKeyEvent { event ->
        event.key == Key.DirectionUp && event.type == KeyEventType.KeyDown && categoryFocus.tryFocus()
    }
    val resultState = rememberLazyListState()
    var openGroup by remember { mutableIntStateOf(0) }
    LaunchedEffect(openGroup) {
        if (openGroup != 0) {
            resultState.scrollToItem(0)
            withFrameNanos { }
            resultFocus.tryFocus()
        }
    }
    fun enterGroup(group: String?) {
        model.group(group)
        openGroup++
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    Column(Modifier.fillMaxSize().padding(top = 77.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Column(Modifier.padding(horizontal = Rail)) {
            Heading("Live TV", size = 38)
            Muted("Your channels, now and next.")
        }
        if (channels.isEmpty()) {
            Column(Modifier.padding(horizontal = Rail), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Muted(if (local.playlist == null) "Add an IPTV playlist to tune live channels."
                else "Set at least one playlist group to Live TV to see channels here.")
                Action("Open Settings", TvGlyph.SETTINGS, preferred = true, primary = true) { model.go(Tab.SETTINGS) }
            }
        } else {
            LazyRow(
                Modifier.focusRequester(categoryFocus).focusRestorer().onRemoteDown { resultFocus.tryFocus() },
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                contentPadding = PaddingValues(horizontal = Rail, vertical = 7.dp)
            ) {
                item {
                    Action("All channels", TvGlyph.GRID, "live/all", preferred = true, primary = selected == null) {
                        enterGroup(null)
                    }
                }
                item {
                    Action("Favorites", TvGlyph.STAR, "live/favs", primary = selected == FAVORITES_GROUP) {
                        enterGroup(FAVORITES_GROUP)
                    }
                }
                items(groups, key = { it }) { group ->
                    Action(group, TvGlyph.FOLDER, "live/group/$group", primary = selected == group) { enterGroup(group) }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().focusRequester(resultFocus).focusGroup(),
                state = resultState,
                contentPadding = PaddingValues(start = Rail, end = Rail, bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                item("columns") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Muted("Channel", Modifier.weight(.32f), size = 13)
                        Muted("On now", Modifier.weight(.36f), size = 13)
                        Muted("Up next", Modifier.weight(.32f), size = 13)
                        Spacer(Modifier.width(65.dp))
                    }
                }
                if (visible.isEmpty()) item {
                    Column(backToCategories, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Muted("Add a channel to Favorites using the star beside it.")
                        Action("Browse all channels", TvGlyph.GRID, "live/browse-all") { enterGroup(null) }
                    }
                }
                items(visible, key = Title::key) { channel ->
                    val entry = local.entryIndex[channel.id.removePrefix("iptv:")]
                    val guide = local.schedule[entry?.channelId].orEmpty()
                    val current = guide.lastOrNull { it.start <= now && it.end > now }
                    val next = guide.firstOrNull { it.start > now }
                    val favorite = channel.key in favoriteKeys
                    val back = if (channel == visible.firstOrNull()) backToCategories else Modifier
                    Row(Modifier.fillMaxWidth().then(back), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RemoteButton("live/channel/${channel.key}", Modifier.weight(1f), onClick = { model.open(channel) }) {
                            Row(Modifier.fillMaxWidth().background(Slate).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Row(Modifier.weight(.32f).padding(end = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (channel.poster.isNotBlank()) {
                                        Artwork(channel.poster, channel.allowHttp, Modifier.size(37.dp, 25.dp),
                                            androidx.compose.ui.layout.ContentScale.Fit)
                                    }
                                    if (channel.poster.isBlank()) Glyph(TvGlyph.LIVE, Fog, Modifier.size(22.dp))
                                    Text(channel.name, fontSize = 14.sp, color = Porcelain, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                                }
                                Programme(current, "No programme listed", Modifier.weight(.36f).padding(end = 9.dp))
                                Programme(next, "—", Modifier.weight(.32f).padding(end = 9.dp))
                                Glyph(TvGlyph.PLAY, Porcelain, Modifier.size(18.dp))
                            }
                        }
                        IconAction(
                            if (favorite) TvGlyph.STAR_FILLED else TvGlyph.STAR,
                            if (favorite) "Remove ${channel.name} from favorites" else "Add ${channel.name} to favorites",
                            "live/favorite/${channel.key}"
                        ) { model.toggleFavorite(channel) }
                    }
                }
            }
        }
    }
}

private const val FAVORITES_GROUP = "\u0000favorites"
private val guideClock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

@Composable
private fun Programme(program: Program?, fallback: String, modifier: Modifier) {
    Column(modifier) {
        Text(program?.title ?: fallback, color = if (program == null) Fog else Porcelain,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (program != null) {
            val zone = ZoneId.systemDefault()
            val from = guideClock.format(Instant.ofEpochMilli(program.start).atZone(zone))
            val to = guideClock.format(Instant.ofEpochMilli(program.end).atZone(zone))
            Muted("$from – $to", size = 10, maxLines = 1)
        }
    }
}

@Composable
internal fun SettingsScreen(
    model: AfterglowViewModel, initialSection: SettingsSection = SettingsSection.IPTV, returnToSources: (() -> Unit)? = null
) {
    var sectionLabel by rememberSaveable { mutableStateOf(initialSection.label) }
    val section = SettingsSection.entries.firstOrNull { it.label == sectionLabel } ?: SettingsSection.IPTV
    var enterSection by remember { mutableStateOf(false) }
    val sectionEntry = remember { FocusRequester() }
    val playlist = model.local.playlist
    var playlistUrl by remember(playlist?.url) { mutableStateOf(playlist?.url.orEmpty()) }
    var guideUrl by remember(playlist?.guide) { mutableStateOf(playlist?.guide.orEmpty()) }
    var newPlaylistHttp by rememberSaveable(playlist != null) { mutableStateOf(false) }
    val playlistHttp = playlist?.allowHttp ?: newPlaylistHttp
    var addonUrl by remember { mutableStateOf("") }
    var addonHttp by rememberSaveable { mutableStateOf(false) }
    var reveal by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var confirmPlaylistRemoval by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var resetFailed by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val status = model.settingsStatus?.takeIf { it.section == section || it.phase == StatusPhase.WORKING }
    val openSection: (SettingsSection) -> Unit = {
        sectionLabel = it.label
        reveal = false
        confirm = false
        enterSection = true
    }
    LaunchedEffect(section, enterSection) {
        if (enterSection) {
            withFrameNanos { }
            sectionEntry.tryFocus()
            enterSection = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Rail, end = Rail, top = if (returnToSources == null) 77.dp else 25.dp,
            bottom = if (status == null) 56.dp else 96.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)) {
            item {
                if (returnToSources != null) {
                    IconAction(TvGlyph.BACK, "Back to sources", "settings/back-to-sources", onClick = returnToSources)
                    Spacer(Modifier.height(10.dp))
                }
                Heading("Settings", size = 38)
                Muted("Manage the sources and playback on this TV.")
            }
            item("sections") {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.focusGroup()) {
                    val sections = if (returnToSources == null) SettingsSection.entries else listOf(initialSection)
                    for (page in sections) {
                        Box(Modifier.onRemoteDown { openSection(page); true }) {
                            val icon = when (page) {
                                SettingsSection.IPTV -> TvGlyph.LIVE
                                SettingsSection.ADDONS -> TvGlyph.LINK
                                SettingsSection.PLAYBACK -> TvGlyph.PLAY
                            }
                            Action(page.label, icon, "settings/${page.label}", preferred = page == initialSection,
                                primary = section == page) { openSection(page) }
                        }
                    }
                }
            }
            if (status == null && (model.busy || model.notice != null)) item("notice") {
                Muted(if (model.busy) "Working…" else model.notice.orEmpty())
            }
            when (section) {
                SettingsSection.IPTV -> {
                    item("iptv-title") {
                        Heading("IPTV playlist", size = 23)
                        Muted(if (playlist == null) "Enter your playlist URL and an optional XMLTV guide URL."
                        else "${model.local.entries.size} entries imported. Classify groups to organize live TV and on-demand titles.")
                    }
                    item("iptv-fields") {
                        Row(Modifier.focusRequester(sectionEntry), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                            TvField(playlistUrl, { playlistUrl = it }, "M3U playlist URL", "settings/m3u",
                                Modifier.weight(1f), secret = !reveal, url = true)
                            TvField(guideUrl, { guideUrl = it }, "XMLTV guide URL (optional)", "settings/xmltv",
                                Modifier.weight(1f), secret = !reveal, url = true)
                        }
                    }
                    item("iptv-toggles") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Action(if (playlistHttp) "HTTP allowed" else "HTTPS only",
                                if (playlistHttp) TvGlyph.UNLOCK else TvGlyph.LOCK, "settings/m3u-http", enabled = !model.busy) {
                                if (playlist == null) newPlaylistHttp = !playlistHttp
                                else model.setPlaylistHttp(!playlistHttp)
                            }
                            Action(if (reveal) "Hide URLs" else "Show URLs", if (reveal) TvGlyph.EYE_OFF else TvGlyph.EYE, "settings/m3u-reveal") { reveal = !reveal }
                        }
                    }
                    item("iptv-controls") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Action(if (playlist == null) "Import playlist" else "Save and import", TvGlyph.DOWNLOAD, "settings/m3u-import",
                                enabled = !model.busy && playlistUrl.isNotBlank(), primary = true) {
                                keyboard?.hide()
                                model.importPlaylist(playlistUrl, guideUrl, playlistHttp, true)
                            }
                            if (playlist != null) {
                                Action("Classify groups", TvGlyph.GRID, "settings/classify", enabled = !model.busy) { model.showGroups(true) }
                                Action("Refresh", TvGlyph.RETRY, "settings/m3u-refresh", enabled = !model.busy, onClick = model::refreshPlaylist)
                                Action(if (confirmPlaylistRemoval) "Confirm removal" else "Remove", TvGlyph.TRASH, "settings/m3u-remove",
                                    enabled = !model.busy) {
                                    if (confirmPlaylistRemoval) model.removePlaylist()
                                    confirmPlaylistRemoval = !confirmPlaylistRemoval
                                }
                            }
                        }
                    }
                }
                SettingsSection.ADDONS -> {
                    item("addons-title") {
                        Heading("Stremio add-ons", size = 23)
                        Muted("Add an add-on using the URL to its manifest.json.")
                    }
                    item("addons-controls") {
                        Row(Modifier.focusRequester(sectionEntry), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TvField(addonUrl, { addonUrl = it }, "Manifest URL", "settings/manifest", Modifier.weight(1f),
                                secret = !reveal, url = true)
                            Action(if (reveal) "Hide URL" else "Show URL", if (reveal) TvGlyph.EYE_OFF else TvGlyph.EYE, "settings/addon-reveal") { reveal = !reveal }
                            Action(if (addonHttp) "HTTP allowed" else "HTTPS only", if (addonHttp) TvGlyph.UNLOCK else TvGlyph.LOCK, "settings/addon-http") { addonHttp = !addonHttp }
                            Action("Install", TvGlyph.DOWNLOAD, "settings/install", enabled = !model.busy && addonUrl.isNotBlank(), primary = true) {
                                keyboard?.hide()
                                model.installAddon(addonUrl, addonHttp) { addonUrl = "" }
                            }
                        }
                    }
                    if (model.addons.isNotEmpty()) {
                        item("addons-refresh") {
                            Action("Refresh catalogs", TvGlyph.RETRY, "settings/addon-refresh", enabled = !model.busy, onClick = model::refreshCatalogs)
                        }
                    }
                    items(model.addons, key = Addon::key) { addon ->
                        Row(Modifier.fillMaxWidth().background(Slate).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(addon.name, color = Porcelain, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Muted("${addon.catalogs.size} catalogs  /  ${if (addon.allowHttp) "HTTP allowed" else "HTTPS only"}", size = 12)
                            }
                            Action("Remove", TvGlyph.TRASH, "settings/addon-remove/${addon.key}", enabled = !model.busy) { model.removeAddon(addon) }
                        }
                    }
                }
                SettingsSection.PLAYBACK -> {
                    item("p2p-copy") {
                        Heading("Peer-to-peer playback", size = 23)
                        Muted("When you play a torrent, peers can see your connection IP and receive pieces uploaded by this TV.")
                    }
                    item("p2p-actions") {
                        Row(Modifier.focusRequester(sectionEntry), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Action(when {
                                model.p2p -> "Turn off torrents"
                                confirm -> "Confirm and enable torrents"
                                else -> "Enable torrents"
                            }, TvGlyph.LINK, "settings/p2p", enabled = !model.busy, primary = true) {
                                if (model.p2p || confirm) {
                                    model.setPeerPlayback(!model.p2p)
                                    confirm = false
                                } else confirm = true
                            }
                            if (confirm) Action("Cancel", TvGlyph.CLOSE, "settings/p2p-cancel") { confirm = false }
                        }
                    }
                }
            }
            if (returnToSources == null && section == SettingsSection.PLAYBACK) item("reset") {
                Spacer(Modifier.height(12.dp))
                Heading("App data", size = 20)
                Action("Reset DASH", TvGlyph.TRASH, "settings/reset", enabled = !model.busy) {
                    resetFailed = false
                    confirmReset = true
                }
                if (resetFailed) Muted("Android couldn’t clear DASH data.")
            }
        }
        if (confirmReset) {
            ConfirmDashAction("Reset DASH?", "This clears your sources, favorites, watch history, settings, and cache. Open DASH again to start setup.",
                "Cancel", "Reset DASH", onDismiss = { confirmReset = false }) {
                confirmReset = false
                val manager = context.getSystemService(android.app.ActivityManager::class.java)
                resetFailed = manager?.clearApplicationUserData() != true
            }
        }
        if (status != null) {
            SettingsFeedback(status, Modifier.align(Alignment.BottomCenter)
                .padding(horizontal = Rail, vertical = 16.dp))
        }
    }
}

@Composable
private fun SettingsFeedback(status: SettingsStatus, modifier: Modifier = Modifier) {
    val tint = if (status.phase == StatusPhase.ERROR) MaterialTheme.colorScheme.error else Glacier
    Row(modifier.widthIn(max = 690.dp).fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }
        .background(Slate, RoundedCornerShape(15.dp)).padding(horizontal = 15.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (status.phase) {
            StatusPhase.WORKING -> CircularProgressIndicator(Modifier.size(18.dp), color = tint, strokeWidth = 2.dp)
            StatusPhase.SUCCESS -> Glyph(TvGlyph.CHECK, tint, Modifier.size(19.dp))
            StatusPhase.ERROR -> Glyph(TvGlyph.CLOSE, tint, Modifier.size(19.dp))
        }
        Text(status.message, color = if (status.phase == StatusPhase.ERROR) tint else Porcelain,
            fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun GroupsScreen(model: AfterglowViewModel) {
    val entries = remember(model.local) { model.local.entries.groupingBy(FeedEntry::group).eachCount().entries.sortedBy { it.key } }
    val groups = model.local.playlist?.groups.orEmpty()
    LazyColumn(Modifier.fillMaxSize().background(Ink), contentPadding = PaddingValues(start = Rail, top = 26.dp, end = Rail, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Action("Done", TvGlyph.CHECK, "groups/done", preferred = true, primary = true) { model.showGroups(false) }
            Spacer(Modifier.height(11.dp))
            Heading("Classify playlist groups", size = 36)
            Muted("Choose where each group appears. Changes save as you go.")
        }
        model.settingsStatus?.takeIf { it.section == SettingsSection.IPTV && it.phase == StatusPhase.SUCCESS }
            ?.let { status -> item("import-status") { SettingsFeedback(status) } }
        model.notice?.let { item { Muted(it) } }
        items(entries, key = { it.key }) { entry ->
            val current = groups[entry.key] ?: Kind.LIVE
            Row(Modifier.fillMaxWidth().background(Slate).padding(13.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(entry.key, color = Porcelain, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted("${entry.value} entries", size = 12)
                }
                Kind.entries.forEach { kind ->
                    Action(kind.label, kind.glyph, "groups/${entry.key}/$kind", primary = current == kind) {
                        if (!model.busy) model.classify(entry.key, kind)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DetailScreen(detail: Details, model: AfterglowViewModel) {
    if (detail.showSources) {
        SourcePicker(detail, model)
        return
    }
    val title = detail.title
    val favorite = model.favorites.any { it.key == title.key }
    val seasons = remember(title.episodes) { title.episodes.groupBy(Episode::season) }
    val firstEpisodeFocus = remember { FocusRequester() }
    var season by rememberSaveable(title.key) { mutableStateOf<Int?>(null) }
    val activeSeason = when {
        season == -1 && null in seasons -> null
        season != null && season in seasons -> season
        else -> seasons.keys.firstOrNull { it != null && it > 0 } ?: seasons.keys.firstOrNull()
    }
    val resume = model.progress.firstOrNull { it.title.key == title.key }
    val first = title.episodes.firstOrNull { (it.season ?: 1) > 0 } ?: title.episodes.firstOrNull()
    val action = when {
        resume != null -> "Resume"
        title.kind == Kind.MOVIE -> "Choose a source"
        first != null -> "Play first episode"
        else -> null
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var overviewHasFocus by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(BrowseBackground)) {
        val series = title.kind == Kind.SERIES
        val stageHeight = (maxHeight * (if (series) .82f else 1f)).coerceAtLeast(310.dp)
        val descriptionWidth = (maxWidth * .46f).coerceAtLeast(240.dp)
        val episodeWidth = (maxWidth * .218f).coerceAtLeast(150.dp)
        val castWidth = (maxWidth * .24f).takeIf { maxWidth >= 660.dp }
        val actionsWidth = (maxWidth - (castWidth ?: 0.dp) - BrowseRail * 2).coerceAtLeast(descriptionWidth)
        SpotlightColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = if (series) 56.dp else 48.dp),
            bottomInset = 56.dp
        ) {
            item("overview") {
                Box(Modifier.fillMaxWidth().height(stageHeight).onFocusChanged { state ->
                    val isFocused = state.hasFocus
                    if (isFocused && (!overviewHasFocus || listState.firstVisibleItemScrollOffset > 0 || listState.firstVisibleItemIndex > 0)) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0, 0)
                        }
                    }
                    overviewHasFocus = isFocused
                }) {
                    SpotlightBackdrop(title, BrowseBackground)
                    IconAction(TvGlyph.BACK, "Back", "detail/back",
                        Modifier.align(Alignment.TopStart).padding(start = BrowseRail, top = 21.dp),
                        preferred = action == null, onClick = model::backFromDetails)
                    Column(Modifier.align(Alignment.BottomStart).padding(start = BrowseRail, bottom = if (series) 32.dp else 48.dp)
                        .widthIn(max = actionsWidth),
                        verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        SpotlightTitle(title, compact = stageHeight < 370.dp)
                        SpotlightMetadata(title)
                        if (title.description.isNotBlank()) {
                            Box(Modifier.width(descriptionWidth)) {
                                Muted(title.description, size = 15, maxLines = 4)
                            }
                        }
                        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (resume != null) {
                                    SpotlightAction("Resume", TvGlyph.PLAY, "detail/resume", preferred = true) {
                                        model.resumePlayback(title, resume, fromBeginning = false)
                                    }
                                    SpotlightAction("Start from beginning", TvGlyph.RETRY, "detail/restart") {
                                        model.resumePlayback(title, resume, fromBeginning = true)
                                    }
                                } else if (action != null) {
                                    SpotlightAction(action, TvGlyph.PLAY, "detail/watch", preferred = true) {
                                        model.pickSources(first.takeIf { title.kind == Kind.SERIES })
                                    }
                                }
                                SpotlightFavorite(favorite, "detail/favorite") { model.toggleFavorite(title) }
                            }
                            if (resume != null) {
                                SpotlightAction("Start with different source", TvGlyph.GRID, "detail/sources") {
                                    model.pickSources(resume.episode)
                                }
                            }
                        }
                    }
                    if (title.cast.isNotEmpty() && castWidth != null) {
                        Column(Modifier.align(Alignment.BottomEnd).padding(end = BrowseRail, bottom = if (series) 36.dp else 48.dp)
                            .width(castWidth), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Starring", color = Porcelain, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Muted(title.cast.take(3).joinToString(", "), size = 13, maxLines = 2)
                        }
                    }
                }
            }
            if (series) {
                item("season-controls") {
                    if (seasons.isEmpty()) {
                        Column(Modifier.padding(horizontal = BrowseRail, vertical = 12.dp)) {
                            Heading("Episodes", size = 21)
                            Muted(if (detail.loading) "Loading episodes…" else "This add-on hasn’t supplied episodes.")
                        }
                    } else if (seasons.size == 1) {
                        Heading(seasonLabel(activeSeason), Modifier.padding(start = BrowseRail, top = 12.dp), size = 22)
                    } else {
                        LazyRow(Modifier.focusGroup(), contentPadding = PaddingValues(horizontal = BrowseRail, vertical = 9.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(seasons.keys.toList(), key = { it ?: -1 }) { number ->
                                Box(Modifier.onRemoteDown { firstEpisodeFocus.tryFocus() }) {
                                    Action(seasonLabel(number), TvGlyph.SERIES, "detail/season/$number",
                                        primary = number == activeSeason) { season = number ?: -1 }
                                }
                            }
                        }
                    }
                }
                val episodes = seasons[activeSeason].orEmpty()
                if (episodes.isNotEmpty()) item("episode-strip") {
                    ShelfStrip(14.dp) {
                        items(episodes, key = Episode::id) { episode ->
                            val entry = if (episode.id == episodes.first().id) Modifier.focusRequester(firstEpisodeFocus) else Modifier
                            EpisodeCard(title, episode, episodeWidth,
                                model.progress.any { it.key == playbackKey(title, episode) }, entry) {
                                model.pickSources(episode)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun seasonLabel(season: Int?) = when (season) {
    null -> "Other episodes"
    0 -> "Specials"
    else -> "Season $season"
}

@Composable
private fun EpisodeCard(
    title: Title, episode: Episode, width: androidx.compose.ui.unit.Dp, resume: Boolean,
    modifier: Modifier = Modifier, open: () -> Unit
) {
    RemoteButton("detail/episode/${episode.id}", modifier.width(width), radius = 13, onClick = open) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9)) {
                Prism(Modifier.matchParentSize())
                Artwork(episode.thumbnail.ifBlank { title.backdrop }, title.allowHttp, Modifier.matchParentSize())
                Box(Modifier.align(Alignment.BottomEnd).padding(9.dp)
                    .background(Ink.copy(alpha = .72f), RoundedCornerShape(12.dp)).padding(6.dp)) {
                    Glyph(TvGlyph.PLAY, Porcelain, Modifier.size(14.dp))
                }
            }
            Column(Modifier.fillMaxWidth().background(Slate).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val label = episode.number?.let { "$it. ${episode.name}" } ?: episode.name
                Text(label, color = Porcelain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (resume) Muted("Resume", size = 11, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SourcePicker(detail: Details, model: AfterglowViewModel) {
    val title = detail.title
    val sourceId = detail.episode?.id ?: title.id
    val hasStreamAddon = model.addons.any { it.supports("stream", title.kind.stremioType(), sourceId) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
        val columns = if (maxWidth >= 900.dp) 3 else 2
        Box(Modifier.fillMaxWidth().height(290.dp)) { SpotlightBackdrop(title, Ink) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = Rail, top = 27.dp, end = Rail, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item("source-header") {
                IconAction(TvGlyph.BACK, "Back", "detail/back",
                    preferred = detail.findingSources && detail.sources.isEmpty(), onClick = model::backFromDetails)
                Spacer(Modifier.height(19.dp))
                SpotlightMetadata(title)
                Heading(title.name, size = 40)
            }
            item("source-heading") {
                Heading(detail.episode?.name?.let { "Sources for $it" } ?: "Choose a source")
                if (detail.findingSources) Muted(if (detail.sources.isEmpty()) "Finding streams…" else "Looking for more sources…")
                else if (detail.sources.isEmpty()) {
                    Muted(detail.error ?: when {
                        title.iptv -> "This playlist entry is no longer available."
                        hasStreamAddon -> "Your streaming add-ons returned no playable streams for this title."
                        else -> "Add a compatible Stremio stream add-on to play this title."
                    })
                    if (!title.iptv && !hasStreamAddon) {
                        Muted("Cinemeta supplies title information. A torrent source appears here when a stream add-on offers one.")
                    }
                } else detail.error?.let { Muted(it) }
            }
            val chunked = detail.sources.withIndex().chunked(columns)
            items(chunked, key = { row -> row.first().value.provider + row.first().value.label + row.first().index }) { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for ((index, source) in row) {
                        Box(Modifier.weight(1f)) {
                            SourceGridCard(
                                id = "detail/source/$index",
                                source = source,
                                p2p = model.p2p,
                                preferred = index == 0,
                                onClick = { model.selectSource(source) }
                            )
                        }
                    }
                    val remainder = columns - row.size
                    if (remainder > 0) {
                        repeat(remainder) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (!detail.findingSources && detail.sources.isEmpty()) item("retry") {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!title.iptv) {
                        Action("Stremio add-ons", TvGlyph.LINK, "detail/add-ons", preferred = !hasStreamAddon) {
                            model.openSourceSetup(SettingsSection.ADDONS)
                        }
                    }
                    Action("Try again", TvGlyph.RETRY, "detail/retry", preferred = title.iptv || hasStreamAddon) {
                        model.pickSources(detail.episode)
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceGridCard(
    id: String, source: Source, p2p: Boolean, preferred: Boolean = false,
    onClick: () -> Unit
) {
    val transport = if (source.url != null) "Direct" else if (p2p) "Torrent" else "Torrent off"
    val disabledTorrent = source.url == null && !p2p
    val description = source.description.trim()
    RemoteButton(id, Modifier.fillMaxWidth(), preferred = preferred, radius = 14, onClick = onClick) { focused ->
        val tint = if (focused) Ink else Porcelain
        val mutedTint = if (focused) Ink.copy(alpha = 0.72f) else Fog
        Column(
            Modifier.fillMaxWidth()
                .background(if (focused) Porcelain else Slate, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier.background(
                            if (focused) Ink.copy(alpha = 0.12f) else Glacier.copy(alpha = 0.20f),
                            RoundedCornerShape(6.dp)
                        ).padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            transport,
                            color = if (focused) Ink else Glacier,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        source.provider,
                        color = mutedTint,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Glyph(
                    if (disabledTorrent) TvGlyph.SETTINGS else TvGlyph.PLAY,
                    tint,
                    Modifier.size(16.dp)
                )
            }
            Text(
                source.label,
                color = tint,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (description.isNotBlank()) {
                Text(
                    description,
                    color = mutedTint,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TvField(
    value: String, change: (String) -> Unit, label: String, id: String,
    modifier: Modifier = Modifier, preferred: Boolean = false, secret: Boolean = false,
    url: Boolean = false
) {
    var editing by remember { mutableStateOf(false) }
    val field = rememberEditorFocus(editing)
    RemoteButton(id, modifier.focusRequester(field), preferred = preferred, onClick = { editing = true }) { focused ->
        val tint = if (focused) Ink else Porcelain
        Row(Modifier.fillMaxWidth().background(if (focused) Porcelain else Slate).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(if (url) TvGlyph.LINK else TvGlyph.EDIT, tint, Modifier.size(20.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = if (focused) Ink.copy(alpha = .70f) else Fog, fontSize = 12.sp,
                    lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val text = when {
                    value.isEmpty() -> "Select to enter"
                    secret -> "•".repeat(value.length.coerceAtMost(40))
                    else -> value
                }
                Text(text, color = tint, fontSize = 14.sp, lineHeight = 20.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (editing) {
        TvTextEntry(label, id, value, change, secret = secret, url = url,
            onDismiss = { editing = false }, onSubmit = { editing = false })
    }
}

@Composable
internal fun rememberEditorFocus(editing: Boolean): FocusRequester {
    val field = remember { FocusRequester() }
    var returnFocus by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(editing, windowFocused) {
        if (editing) {
            returnFocus = true
        } else if (returnFocus && windowFocused) {
            withFrameNanos { }
            field.requestFocus()
            returnFocus = false
        }
    }
    return field
}

@Composable
internal fun TvTextEntry(
    label: String, id: String, value: String, change: (String) -> Unit, secret: Boolean = false,
    url: Boolean = false, onDismiss: () -> Unit, onSubmit: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = remember { FocusRequester() }
        val finish = {
            keyboard?.hide()
            onSubmit()
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            focus.requestFocus()
            keyboard?.show()
        }
        Column(Modifier.widthIn(min = 420.dp, max = 590.dp).background(Slate, RoundedCornerShape(22.dp))
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Heading(label, size = 21)
            OutlinedTextField(value, change, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                shape = RoundedCornerShape(15.dp),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (url) KeyboardType.Uri else KeyboardType.Text,
                    autoCorrectEnabled = !url, imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { finish() }))
            Action("Done", TvGlyph.CHECK, "$id/done", primary = true, onClick = finish)
        }
    }
}
