package tv.afterglow

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val progress = model.progress.filter { kind == null || it.title.kind == kind }
    val favorites = model.favorites.filter { it.kind in VideoKinds && (kind == null || kind == it.kind) }
    val recent = remember(kind, model.recentChannels, model.local) {
        if (kind != null || model.recentChannels.isEmpty()) emptyList()
        else {
            val byId = model.local.titles.asSequence().filter { it.kind == Kind.LIVE }.associateBy(Title::id)
            model.recentChannels.mapNotNull(byId::get)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(BrowseBackground)) {
        val landscapeWidth = (maxWidth * .222f).coerceAtLeast(150.dp)
        val portraitWidth = (maxWidth * .140f).coerceAtLeast(98.dp)
        val gap = (maxWidth * .020f).coerceAtLeast(12.dp)
        val stageHeight = (maxHeight * .80f).coerceAtLeast(310.dp)
        SpotlightColumn(
            contentPadding = PaddingValues(top = if (featured.isEmpty()) 74.dp else 0.dp, bottom = 40.dp),
            topInset = 66.dp
        ) {
            if (featured.isNotEmpty()) item("featured") {
                FeaturedCarousel(model, featured, stageHeight)
                Spacer(Modifier.height(13.dp))
            }
            if (progress.isNotEmpty()) item("continue") {
                BrowseShelf("Continue Watching", "continue", onOpen = { model.openCatalog(continueCatalog(kind)) }) {
                    ShelfStrip(gap) {
                        itemsIndexed(progress.take(30), key = { _, resume -> resume.key }) { index, resume ->
                            ContinueCard(resume, landscapeWidth, featured.isEmpty() && index == 0) { model.open(resume.title, resume) }
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) item("recent-channels") {
                BrowseShelf("Recently Watched Channels", "recent-channels", icon = TvGlyph.CLOCK) {
                    ShelfStrip(gap) {
                        itemsIndexed(recent, key = { _, channel -> channel.key }) { index, channel ->
                            ChannelCard(channel, "recent/${channel.key}", landscapeWidth * .86f,
                                preferred = featured.isEmpty() && progress.isEmpty() && index == 0) { model.open(channel) }
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
                                featured.isEmpty() && progress.isEmpty() && recent.isEmpty() && shelfIndex == 0 && index == 0
                            ) { model.open(title) }
                        }
                    }
                }
            }
            if (favorites.isNotEmpty()) item("favorites") {
                BrowseShelf("Favorites", "favorites") {
                    ShelfStrip(gap) {
                        itemsIndexed(favorites, key = { _, title -> title.key }) { index, title ->
                            PortraitCard(title, "favorites/${title.key}", portraitWidth, null,
                                progress.isEmpty() && recent.isEmpty() && available.isEmpty() && index == 0) { model.open(title) }
                        }
                    }
                }
            }
            if (progress.isEmpty() && recent.isEmpty() && available.isEmpty() && favorites.isEmpty()) item("empty") {
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
        if (onOpen == null) Row(Modifier.padding(start = BrowseRail, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Glyph(it, Fog, Modifier.size(18.dp)) }
            Heading(name, size = 19)
        } else RemoteButton("shelf-heading/$id", Modifier.padding(start = BrowseRail - 4.dp, bottom = 8.dp),
            radius = 12, onClick = onOpen) {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Heading(name, size = 19)
                Glyph(TvGlyph.NEXT, Color.White.copy(alpha = .55f), Modifier.size(17.dp))
            }
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
private fun ContinueCard(resume: Resume, width: Dp, preferred: Boolean, open: () -> Unit) {
    val title = resume.title
    ArtworkCard(title, title.backdrop.ifBlank { title.poster }, "continue/${resume.key}",
        width, width * .70f, preferred, open) {
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
private fun ArtworkCard(
    title: Title, artwork: String, id: String, width: Dp, height: Dp, preferred: Boolean, open: () -> Unit,
    modifier: Modifier = Modifier, focusIcon: TvGlyph? = null, favorite: FavoriteAction? = null,
    content: @Composable BoxScope.() -> Unit
) {
    RemoteButton("artwork/$id", modifier.size(width, height).favoriteState(favorite), preferred, radius = 14,
        onLongClick = favorite?.toggle, onLongClickLabel = favorite?.label, onClick = open) { focused ->
        val shape = RoundedCornerShape(14.dp)
        Box(Modifier.fillMaxSize().border(1.dp, CardBorder, shape)) {
            Prism(Modifier.fillMaxSize(), if (title.kind == Kind.SERIES) Color(0xFFABBCFC) else Glacier)
            Artwork(artwork, title.allowHttp, Modifier.fillMaxSize())
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
        contentPadding = PaddingValues(BrowseRail, 25.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { IconAction(TvGlyph.BACK, "Back", "continue/back", preferred = true) { model.openCatalog(null) } }
        item { Heading("Continue Watching", size = 30) }
        itemsIndexed(progress, key = { _, resume -> resume.key }) { _, resume ->
            val title = resume.title
            ListItem("continue/list/${resume.key}", title.name, resumeCaption(resume),
                title.poster, title.allowHttp) { model.open(title, resume) }
        }
    }
}

private fun resumeCaption(resume: Resume): String {
    val episode = resume.episode
    val episodeLabel = if (episode?.season != null && episode.number != null) "S${episode.season}, E${episode.number}"
        else episode?.name
    val total = resume.duration?.takeIf { it > 0 }
    val minutes = ((total?.let { (it - resume.position).coerceAtLeast(0) } ?: resume.position) / 60_000)
    val time = when {
        minutes < 1 -> if (total == null) "Just started" else "<1m left"
        minutes < 60 -> "${minutes}m"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
    return listOfNotNull(episodeLabel, time).joinToString("  ·  ")
}
