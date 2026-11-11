package tv.afterglow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.LocalTextStyle

internal val BrowseBackground = Color(0xFF1D2026)
internal val BrowseRail = 30.dp
private val CardBorder = Color(0xFF747474).copy(alpha = .52f)
private val VideoKinds = listOf(Kind.SERIES, Kind.MOVIE)

internal fun continueCatalog(kind: Kind?) = "continue/${kind?.name ?: "all"}"

internal fun browseShelves(addons: List<Shelf>, local: List<Title>): List<Shelf> {
    val groups = local.filter { it.kind in VideoKinds }.groupBy { it.kind to it.group }.map { (key, titles) ->
        val (kind, group) = key
        Shelf("iptv/$kind/${digest(group)}", group, kind, titles)
    }
    val catalogs = (addons + groups).filter { it.titles.isNotEmpty() }
    val ranked = VideoKinds.mapNotNull { kind ->
        val titles = catalogs.asSequence().filter { it.kind == kind }.flatMap { it.titles.asSequence() }
            .distinctBy(Title::key).take(10).toList()
        if (titles.isEmpty()) null else {
            val singular = if (kind == Kind.SERIES) "TV Show" else "Movie"
            val name = if (titles.size == 1) "Top $singular" else "Top ${titles.size} ${singular}s"
            Shelf("ranked/$kind", name, kind, titles)
        }
    }
    return ranked + catalogs
}

@Composable
internal fun BrowseScreen(model: AfterglowViewModel, shelves: List<Shelf>, kind: Kind?) {
    val available = shelves.filter { kind == null || it.kind == kind }
    val featured = remember(available, kind) { featuredTitles(available, kind) }
    val progress = model.continueWatching.filter { kind == null || it.title.kind == kind }
    val favorites = model.favorites.filter { it.kind in VideoKinds && (kind == null || kind == it.kind) }
    val favoriteChannels = remember(kind, model.favorites, model.local) {
        if (kind != null) emptyList()
        else {
            val keys = model.favorites.asSequence().filter { it.kind == Kind.LIVE }.map(Title::key).toSet()
            model.local.titles.filter { it.kind == Kind.LIVE && it.key in keys }
        }
    }
    var removal by remember { mutableStateOf<String?>(null) }
    var returnFocus by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize().background(BrowseBackground)) {
        val landscapeWidth = (maxWidth * .222f).coerceAtLeast(150.dp)
        val portraitWidth = (maxWidth * .140f).coerceAtLeast(98.dp)
        val gap = (maxWidth * .020f).coerceAtLeast(12.dp)
        val stageHeight = (maxHeight * .80f).coerceAtLeast(310.dp)
        val listState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()
        var carouselHasFocus by remember { mutableStateOf(false) }
        SpotlightColumn(
            state = listState,
            contentPadding = PaddingValues(top = if (featured.isEmpty()) 74.dp else 0.dp, bottom = 56.dp),
            topInset = 66.dp,
            bottomInset = 56.dp
        ) {
            if (featured.isNotEmpty()) item("featured") {
                Box(Modifier.onFocusChanged { state ->
                    val isFocused = state.hasFocus
                    if (isFocused && (!carouselHasFocus || listState.firstVisibleItemScrollOffset > 0 || listState.firstVisibleItemIndex > 0)) {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0, 0)
                        }
                    }
                    carouselHasFocus = isFocused
                }) {
                    FeaturedCarousel(model, featured, stageHeight)
                }
                Spacer(Modifier.height(13.dp))
            }
            if (progress.isNotEmpty()) item("continue") {
                BrowseShelf("Continue Watching", "continue", onOpen = { model.openCatalog(continueCatalog(kind)) }) {
                    ShelfStrip(gap) {
                        itemsIndexed(progress.take(30), key = { _, resume -> resume.title.key }) { index, resume ->
                            val titleKey = resume.title.key
                            ContinueCard(resume, landscapeWidth, featured.isEmpty() && index == 0,
                                confirming = removal == titleKey, restoreFocus = returnFocus == titleKey,
                                onFocusRestored = { returnFocus = null },
                                onRequestRemove = { removal = titleKey },
                                onCancel = { removal = null; returnFocus = titleKey },
                                onRemove = {
                                    returnFocus = (progress.getOrNull(index + 1)?.takeIf { index < 29 }
                                        ?: progress.getOrNull(index - 1))?.title?.key
                                    removal = null
                                    model.removeFromContinueWatching(resume.title)
                                },
                                open = { model.open(resume.title) })
                        }
                    }
                }
            }
            if (favorites.isNotEmpty()) item("favorites") {
                BrowseShelf("Favorites", "favorites") {
                    ShelfStrip(gap) {
                        itemsIndexed(favorites, key = { _, title -> title.key }) { index, title ->
                            PortraitCard(title, "favorites/${title.key}", portraitWidth, null,
                                featured.isEmpty() && progress.isEmpty() && index == 0) { model.open(title) }
                        }
                    }
                }
            }
            if (favoriteChannels.isNotEmpty()) item("favorite-channels") {
                BrowseShelf("Favorite Channels", "favorite-channels", icon = TvGlyph.STAR) {
                    ShelfStrip(gap) {
                        itemsIndexed(favoriteChannels, key = { _, channel -> channel.key }) { index, channel ->
                            val favorite = FavoriteAction(true) { model.toggleFavorite(channel) }
                            ChannelCard(channel, "favorite-channels/${channel.key}", landscapeWidth * .86f,
                                preferred = featured.isEmpty() && progress.isEmpty() && favorites.isEmpty() && index == 0,
                                favorite = favorite) { model.open(channel) }
                        }
                    }
                }
            }
            itemsIndexed(available, key = { _, shelf -> shelf.id }) { shelfIndex, shelf ->
                val ranked = shelf.id.startsWith("ranked/")
                BrowseShelf(shelf.name, shelf.id, onOpen = { model.openCatalog(shelf.id) }) {
                    ShelfStrip(gap) {
                        itemsIndexed(shelf.titles.take(24), key = { _, title -> title.key }) { index, title ->
                            PortraitCard(title, "${shelf.id}/${title.key}", portraitWidth,
                                if (ranked) index + 1 else null,
                                featured.isEmpty() && progress.isEmpty() && favorites.isEmpty() && favoriteChannels.isEmpty() && shelfIndex == 0 && index == 0
                            ) { model.open(title) }
                        }
                    }
                }
            }
            if (progress.isEmpty() && favoriteChannels.isEmpty() && available.isEmpty() && favorites.isEmpty()) item("empty") {
                EmptyPanel(
                    kind?.label ?: "Your library",
                    when {
                        model.loadingCinemeta -> "Loading Cinemeta catalogs…"
                        model.addons.any { it.url == CINEMETA_MANIFEST } -> "Refresh your catalogs in Settings to load titles."
                        else -> "Add a Stremio add-on or an IPTV playlist to start watching."
                    },
                    if (kind == null && model.addons.isEmpty()) "Add a source" else "Open Settings"
                ) { model.go(Tab.SETTINGS) }
            }
        }
    }
}

@Composable
internal fun BrowseShelf(
    name: String, id: String, onOpen: (() -> Unit)? = null, icon: TvGlyph? = null,
    cards: @Composable () -> Unit
) {
    Column(Modifier.padding(bottom = 29.dp)) {
        Row(Modifier.padding(start = BrowseRail, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Glyph(it, Fog, Modifier.size(18.dp)) }
            Heading(name, size = 19)
        }
        cards()
    }
}

@Composable
internal fun ShelfStrip(gap: Dp, content: LazyListScope.() -> Unit) {
    LazyRow(
        modifier = Modifier.focusGroup(),
        contentPadding = PaddingValues(start = BrowseRail, end = BrowseRail, top = 9.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        content = content
    )
}

internal data class FavoriteAction(val isFavorite: Boolean, val toggle: () -> Unit) {
    val label get() = if (isFavorite) "Remove from favorites" else "Add to favorites"
}

@Composable
internal fun PortraitCard(
    title: Title, id: String, width: Dp, rank: Int?, preferred: Boolean,
    modifier: Modifier = Modifier, favorite: FavoriteAction? = null, height: Dp = width * 1.5f,
    open: () -> Unit
) {
    ArtworkCard(title, title.poster.ifBlank { title.backdrop }, id, width, height, preferred, open,
        focusIcon = TvGlyph.NEXT, favorite = favorite, modifier = modifier) {
        if (rank != null) {
            Text(rank.toString(), Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 5.dp),
                style = LocalTextStyle.current.merge(TextStyle(
                    brush = Brush.verticalGradient(listOf(Color.White, Color.White, Color.White.copy(alpha = .42f)))
                )), fontSize = 39.sp, lineHeight = 47.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 11.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title.name, color = Porcelain, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 17.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            title.genre.takeIf(String::isNotBlank)?.let {
                Text(it, color = Color(0xFFD0D0D0), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ContinueCard(
    resume: Resume, width: Dp, preferred: Boolean, confirming: Boolean, restoreFocus: Boolean,
    onFocusRestored: () -> Unit, onRequestRemove: () -> Unit, onCancel: () -> Unit,
    onRemove: () -> Unit, open: () -> Unit
) {
    val title = resume.title
    val requester = remember { FocusRequester() }
    val height = width * .70f
    LaunchedEffect(restoreFocus, confirming) {
        if (restoreFocus && !confirming) {
            withFrameNanos { }
            if (requester.tryFocus()) onFocusRestored()
        }
    }
    if (confirming) {
        BackHandler(onBack = onCancel)
        val memory = remember { FocusMemory() }
        val confirmRequester = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            confirmRequester.tryFocus()
        }
        FocusScope(memory, "continue/confirm/${title.key}", entry = confirmRequester) {
            Column(
                Modifier.size(width, height).background(Slate, RoundedCornerShape(14.dp))
                    .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp).focusGroup(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Remove from Continue Watching?", color = Porcelain, fontSize = 13.sp,
                    lineHeight = 17.sp, fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ContinueConfirmButton("Keep", true, onCancel)
                    ContinueConfirmButton("Remove", false, onRemove)
                }
            }
        }
        return
    }
    ArtworkCard(title, title.backdrop.ifBlank { title.poster }, "continue/${title.key}",
        width, height, preferred, open, modifier = Modifier.focusRequester(requester),
        onLongClick = onRequestRemove, onLongClickLabel = "Remove from Continue Watching") {
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title.name, color = Porcelain, fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Glyph(TvGlyph.PLAY, Porcelain, Modifier.size(13.dp))
                resume.duration?.takeIf { it > 0 }?.let { duration ->
                    Box(Modifier.width(26.dp).height(4.dp).background(Color.White.copy(alpha = .28f), RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth((resume.position.toDouble() / duration).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight().background(Porcelain, RoundedCornerShape(2.dp)))
                    }
                }
                Text(resumeCaption(resume), color = Color(0xFFD0D0D0), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ContinueConfirmButton(label: String, preferred: Boolean, onClick: () -> Unit) {
    RemoteButton("continue/confirm/$label", preferred = preferred, radius = 10, onClick = onClick) { focused ->
        Text(
            label,
            Modifier
                .background(if (focused) Porcelain else Ink, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            color = if (focused) Ink else Porcelain,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ArtworkCard(
    title: Title, artwork: String, id: String, width: Dp, height: Dp, preferred: Boolean, open: () -> Unit,
    modifier: Modifier = Modifier, focusIcon: TvGlyph? = null, favorite: FavoriteAction? = null,
    onLongClick: (() -> Unit)? = favorite?.toggle, onLongClickLabel: String? = favorite?.label,
    content: @Composable BoxScope.() -> Unit
) {
    RemoteButton("artwork/$id", modifier.size(width, height).favoriteState(favorite), preferred, radius = 14,
        onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, onClick = open) { focused ->
        val shape = RoundedCornerShape(14.dp)
        Box(Modifier.fillMaxSize().border(1.dp, CardBorder, shape)) {
            Prism(Modifier.fillMaxSize(), if (title.kind == Kind.SERIES) Color(0xFFABBCFC) else Glacier)
            Artwork(artwork, title.allowHttp,
                if (title.kind == Kind.LIVE) Modifier.align(Alignment.TopCenter).padding(8.dp).fillMaxSize(.63f)
                else Modifier.fillMaxSize(),
                if (title.kind == Kind.LIVE) ContentScale.Fit else ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .32f), .30f to Color.Transparent,
                .58f to Color.Transparent, 1f to Color.Black.copy(alpha = .9f)
            )))
            content()
            val badge = if (favorite?.isFavorite == true) TvGlyph.STAR_FILLED else focusIcon.takeIf { focused }
            if (badge != null) {
                Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(30.dp)
                    .background(Porcelain, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                    Glyph(badge, Ink, Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
internal fun ChannelCard(
    channel: Title, id: String, width: Dp, modifier: Modifier = Modifier,
    preferred: Boolean = false, favorite: FavoriteAction? = null, open: () -> Unit
) {
    RemoteButton("channel/$id", modifier.size(width, width * .57f).favoriteState(favorite), preferred,
        onLongClick = favorite?.toggle, onLongClickLabel = favorite?.label, onClick = open) { focused ->
        val tint = if (focused) Ink else Porcelain
        Column(
            Modifier.fillMaxSize().background(if (focused) Porcelain else Slate).padding(11.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth().height(35.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (channel.poster.isBlank()) Glyph(TvGlyph.LIVE, tint, Modifier.size(27.dp))
                else Artwork(channel.poster, channel.allowHttp, Modifier.width(62.dp).fillMaxHeight(), ContentScale.Fit)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (favorite?.isFavorite == true) Glyph(TvGlyph.STAR_FILLED, tint, Modifier.size(19.dp))
                    Glyph(TvGlyph.PLAY, tint, Modifier.size(20.dp))
                }
            }
            Text(channel.name, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun Modifier.favoriteState(favorite: FavoriteAction?): Modifier =
    if (favorite?.isFavorite == true) semantics { stateDescription = "Favorite" } else this

@Composable
internal fun ContinueScreen(progress: List<Resume>, model: AfterglowViewModel) {
    LazyColumn(Modifier.fillMaxSize().background(BrowseBackground),
        contentPadding = PaddingValues(start = BrowseRail, top = 25.dp, end = BrowseRail, bottom = 56.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { IconAction(TvGlyph.BACK, "Back", "continue/back", preferred = true) { model.openCatalog(null) } }
        item { Heading("Continue Watching", size = 30) }
        itemsIndexed(progress, key = { _, resume -> resume.key }) { _, resume ->
            val title = resume.title
            ListItem("continue/list/${resume.key}", title.name, resumeCaption(resume),
                title.poster, title.allowHttp) { model.open(title) }
        }
    }
}

private fun resumeCaption(resume: Resume): String {
    if (resume.title.kind == Kind.LIVE) return "Live TV"
    val episode = resume.episode
    val episodeLabel = if (episode?.season != null && episode.number != null) "S${episode.season}, E${episode.number}"
        else episode?.name
    val total = resume.duration?.takeIf { it > 0 }
    val minutes = ((total?.let { (it - resume.position).coerceAtLeast(0) } ?: resume.position) / 60_000)
    val time = when {
        minutes < 1 -> "Just started"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
    return listOfNotNull(episodeLabel, time).joinToString("  ·  ")
}
