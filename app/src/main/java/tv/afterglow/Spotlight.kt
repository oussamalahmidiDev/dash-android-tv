package tv.afterglow

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpotlightColumn(
    contentPadding: PaddingValues,
    topInset: Dp = 0.dp,
    bottomInset: Dp = 56.dp,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit
) {
    val top = with(LocalDensity.current) { topInset.toPx() }
    val bottom = with(LocalDensity.current) { bottomInset.toPx() }
    val keepVisible = remember(top, bottom) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val end = offset + size
                val bottomLimit = containerSize - bottom
                return when {
                    offset < top && end > bottomLimit -> 0f
                    offset < top -> offset - top
                    end > bottomLimit -> end - bottomLimit
                    else -> 0f
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides keepVisible) {
        LazyColumn(state = state, contentPadding = contentPadding, content = content)
    }
}

internal fun featuredTitles(shelves: List<Shelf>, kind: Kind?): List<Title> {
    val cinemetaPrefix = "${cinemetaDefault.key}/"
    val ordered = shelves.sortedByDescending { it.id.startsWith(cinemetaPrefix) }
    fun titles(type: Kind) = ordered.asSequence().filter { it.kind == type }.flatMap { it.titles.asSequence() }
        .filter { it.backdrop.isNotBlank() || it.poster.isNotBlank() }.distinctBy(Title::key).take(8).toList()
    if (kind != null) return titles(kind)
    val movies = titles(Kind.MOVIE)
    val shows = titles(Kind.SERIES)
    return (0 until maxOf(movies.size, shows.size)).flatMap { index ->
        listOfNotNull(movies.getOrNull(index), shows.getOrNull(index))
    }.take(8)
}

@Composable
internal fun FeaturedCarousel(model: AfterglowViewModel, titles: List<Title>, height: Dp) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val index = titles.indexOfFirst { it.key == selected }.coerceAtLeast(0)
    val initial = titles[index]
    var title by remember(initial) { mutableStateOf(initial) }
    LaunchedEffect(initial) {
        if (initial.provider == cinemetaDefault.name && initial.logo.isBlank()) {
            title = model.featuredDetails(initial)
        }
    }
    val favorite = model.favorites.any { it.key == title.key }
    BoxWithConstraints(Modifier.fillMaxWidth().height(height)) {
        val compact = height < 370.dp
        SpotlightBackdrop(title, BrowseBackground)
        Column(Modifier.align(Alignment.BottomStart).padding(start = BrowseRail, bottom = 49.dp)
            .width((maxWidth * .47f).coerceAtLeast(240.dp)), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            SpotlightTitle(title, compact)
            SpotlightMetadata(title)
            if (title.description.isNotBlank()) Muted(title.description, size = 15, maxLines = 3)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                SpotlightAction("View details", TvGlyph.NEXT, "featured/open", preferred = true) { model.open(title) }
                SpotlightFavorite(favorite, "featured/favorite") { model.toggleFavorite(title) }
            }
        }
        if (titles.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                .semantics { contentDescription = "Featured title ${index + 1} of ${titles.size}" },
                horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                titles.indices.forEach { page ->
                    Box(Modifier.width(if (page == index) 18.dp else 6.dp).height(6.dp)
                        .background(if (page == index) Porcelain else Fog.copy(alpha = .60f), CircleShape))
                }
            }
            Row(Modifier.align(Alignment.BottomEnd).padding(end = BrowseRail, bottom = 33.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconAction(TvGlyph.BACK, "Previous featured title", "featured/previous") {
                    selected = titles[(index - 1 + titles.size) % titles.size].key
                }
                IconAction(TvGlyph.NEXT, "Next featured title", "featured/next") {
                    selected = titles[(index + 1) % titles.size].key
                }
            }
        }
    }
}

@Composable
internal fun BoxScope.SpotlightBackdrop(title: Title, floor: Color) {
    Prism(Modifier.matchParentSize())
    val hasBackdrop = title.backdrop.isNotBlank()
    Artwork(title.backdrop.ifBlank { title.poster }, title.allowHttp,
        Modifier.align(Alignment.CenterEnd).fillMaxWidth(if (hasBackdrop) 1f else .64f).fillMaxHeight(),
        alignment = Alignment.CenterEnd)
    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
        0f to Ink, .28f to Ink.copy(alpha = .95f), .46f to Ink.copy(alpha = .62f),
        .68f to Ink.copy(alpha = .14f), 1f to Ink.copy(alpha = .08f))))
    Box(Modifier.matchParentSize().background(Brush.verticalGradient(
        0f to Ink.copy(alpha = .58f), .27f to Color.Transparent, .58f to Color.Transparent,
        .87f to floor.copy(alpha = .62f), 1f to floor)))
}

@Composable
internal fun SpotlightTitle(title: Title, compact: Boolean = false) {
    var logoReady by remember(title.logo) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(if (compact) 76.dp else 98.dp), contentAlignment = Alignment.BottomStart) {
        if (title.logo.isNotBlank()) {
            Artwork(title.logo, title.allowHttp, Modifier.fillMaxHeight().widthIn(max = 325.dp).fillMaxWidth(.94f)
                .semantics { if (logoReady) { heading(); contentDescription = title.name } },
                ContentScale.Fit, Alignment.BottomStart, ColorFilter.tint(Porcelain), onLoaded = { logoReady = true })
        }
        if (!logoReady) Text(title.name, Modifier.semantics { heading() }, color = Porcelain,
            fontSize = if (compact) 35.sp else 46.sp, lineHeight = if (compact) 37.sp else 48.sp,
            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun SpotlightMetadata(title: Title) {
    val format = if (title.kind == Kind.SERIES) "TV Show" else "Movie"
    Muted(listOf(format, title.genre, title.year, title.runtime).filter(String::isNotBlank).joinToString("  ·  "),
        size = 14, maxLines = 1)
}

@Composable
internal fun SpotlightAction(
    label: String, icon: TvGlyph, id: String, preferred: Boolean = false, onClick: () -> Unit
) {
    RemoteButton(id, preferred = preferred, filled = true, radius = 25, onClick = onClick) {
        Row(Modifier.height(48.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Glyph(icon, Ink, Modifier.size(19.dp))
            Text(label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun SpotlightFavorite(favorite: Boolean, id: String, toggle: () -> Unit) {
    IconAction(if (favorite) TvGlyph.CHECK else TvGlyph.ADD,
        if (favorite) "Remove from favorites" else "Add to favorites", id, onClick = toggle)
}
