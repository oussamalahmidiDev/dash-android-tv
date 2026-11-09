package tv.afterglow

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest

internal val Ink = Color(0xFF14171C)
internal val Slate = Color(0xFF292D35)
internal val Fog = Color(0xFFB6BFCB)
internal val Porcelain = Color(0xFFF6F7FA)
internal val Glacier = Color(0xFFB9EBFF)
internal val Rail = 44.dp

private val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private val InterTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Inter),
        displayMedium = displayMedium.copy(fontFamily = Inter),
        displaySmall = displaySmall.copy(fontFamily = Inter),
        headlineLarge = headlineLarge.copy(fontFamily = Inter),
        headlineMedium = headlineMedium.copy(fontFamily = Inter),
        headlineSmall = headlineSmall.copy(fontFamily = Inter),
        titleLarge = titleLarge.copy(fontFamily = Inter),
        titleMedium = titleMedium.copy(fontFamily = Inter),
        titleSmall = titleSmall.copy(fontFamily = Inter),
        bodyLarge = bodyLarge.copy(fontFamily = Inter),
        bodyMedium = bodyMedium.copy(fontFamily = Inter),
        bodySmall = bodySmall.copy(fontFamily = Inter),
        labelLarge = labelLarge.copy(fontFamily = Inter),
        labelMedium = labelMedium.copy(fontFamily = Inter),
        labelSmall = labelSmall.copy(fontFamily = Inter),
    )
}

class MainActivity : ComponentActivity() {
    private val model: AfterglowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.exitPlayback()
        })
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                background = Ink, surface = Slate, onSurface = Porcelain, primary = Glacier,
                onBackground = Porcelain, onPrimary = Ink, onSurfaceVariant = Fog, outline = Fog
            ), typography = InterTypography) {
                ProvideTextStyle(InterTypography.bodyLarge.copy(letterSpacing = 0.sp)) { TvApp(model) }
            }
        }
    }
}

internal class FocusMemory {
    private val focused = mutableMapOf<String, String>()
    fun get(scope: String) = focused[scope]
    fun set(scope: String, id: String) { focused[scope] = id }
}

internal data class FocusArea(
    val memory: FocusMemory, val scope: String, val target: String?, val entry: FocusRequester?
)
internal val LocalFocusArea = compositionLocalOf<FocusArea?> { null }

@Composable
internal fun FocusScope(
    memory: FocusMemory, scope: String, entry: FocusRequester? = null, content: @Composable () -> Unit
) {
    key(scope) {
        val initial = remember { memory.get(scope) }
        CompositionLocalProvider(LocalFocusArea provides FocusArea(memory, scope, initial, entry), content = content)
    }
}

@Composable
internal fun RemoteButton(
    id: String, modifier: Modifier = Modifier, preferred: Boolean = false,
    enabled: Boolean = true, filled: Boolean = false, radius: Int = 16,
    onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null,
    onClick: () -> Unit, content: @Composable (Boolean) -> Unit
) {
    val area = LocalFocusArea.current
    val ownRequester = remember { FocusRequester() }
    val requester = area?.entry?.takeIf { preferred } ?: ownRequester
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1f else 0f, tween(155), label = "remote focus")
    val shape = RoundedCornerShape(radius.dp)
    LaunchedEffect(area?.scope, id, enabled) {
        if (enabled && area != null && (area.target == id || (area.target == null && preferred))) {
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    Box(
        modifier.zIndex(if (focused) 1f else 0f)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (focused) area?.memory?.set(area.scope, id)
            }
            .graphicsLayer {
                scaleX = 1f + .04f * lift
                scaleY = scaleX
                translationY = -2.dp.toPx() * lift
                shadowElevation = 13.dp.toPx() * lift
                this.shape = shape
                alpha = if (enabled) 1f else .48f
            }
            .clip(shape)
            .background(if (filled) Porcelain else if (focused) Slate else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Glacier else Color.Transparent, shape)
            .combinedClickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled,
                role = Role.Button, onLongClick = onLongClick, onLongClickLabel = onLongClickLabel, onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) { content(focused) }
}

@Composable
internal fun Action(
    text: String, icon: TvGlyph, id: String = text, preferred: Boolean = false, enabled: Boolean = true,
    primary: Boolean = false, onClick: () -> Unit
) {
    RemoteButton(id, preferred = preferred, enabled = enabled, filled = primary, radius = 24, onClick = onClick) { focused ->
        val tint = if (primary || focused) Ink else Porcelain
        Row(
            Modifier.background(if (primary || focused) Porcelain else Slate)
                .padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Glyph(icon, tint, Modifier.size(17.dp))
            Text(text, color = tint, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun Heading(text: String, modifier: Modifier = Modifier, size: Int = 24) {
    Text(text, modifier.semantics { heading() }, color = Porcelain, fontSize = size.sp,
        lineHeight = (size + 5).sp, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 14, maxLines: Int = Int.MAX_VALUE) {
    Text(text, modifier, color = Fog, fontSize = size.sp, lineHeight = (size + 5).sp, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun Artwork(url: String, allowHttp: Boolean, modifier: Modifier = Modifier,
                     scale: ContentScale = ContentScale.Crop, alignment: Alignment = Alignment.Center,
                     colorFilter: ColorFilter? = null, onLoaded: (() -> Unit)? = null) {
    val context = LocalContext.current
    val safe = remember(url, allowHttp) { url.httpUrl()?.takeIf { allowHttp || it.isHttps } }
    val loader = remember(context, allowHttp) { ArtworkLoaders.get(context, allowHttp) }
    if (safe != null) AsyncImage(
        remember(safe) { ImageRequest.Builder(context).data(safe.toString()).diskCachePolicy(CachePolicy.DISABLED).build() },
        contentDescription = null, imageLoader = loader, contentScale = scale, alignment = alignment,
        modifier = modifier, colorFilter = colorFilter, onSuccess = { onLoaded?.invoke() }
    )
}

private object ArtworkLoaders {
    private val loaders = mutableMapOf<Boolean, ImageLoader>()

    fun get(context: Context, http: Boolean) = loaders.getOrPut(http) {
        ImageLoader.Builder(context.applicationContext).okHttpClient { Network.client(http) }
            .diskCachePolicy(CachePolicy.DISABLED).build()
    }
}

@Composable
internal fun Prism(modifier: Modifier = Modifier, accent: Color = Glacier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.linearGradient(listOf(Ink, Slate, Color(0xFF23364A)),
            start = Offset.Zero, end = Offset(w * .92f, h * .76f)))
        for (index in 0..3) {
            val x = w * (.39f + index * .155f)
            val tip = x + w * .32f
            val band = Path().apply {
                moveTo(x - w * .11f, h)
                lineTo(x + w * .03f, 0f)
                lineTo(tip, 0f)
                lineTo(tip - w * .22f, h)
                close()
            }
            drawPath(band, Brush.linearGradient(
                listOf(accent.copy(alpha = .03f), accent.copy(alpha = .22f - index * .03f), Ink.copy(alpha = .22f)),
                start = Offset(x, h * .89f), end = Offset(tip, h * .19f)
            ))
            drawLine(accent.copy(alpha = .22f), Offset(x - w * .11f, h), Offset(x + w * .03f, 0f), 1.1.dp.toPx())
        }
    }
}

@Composable
private fun TvApp(model: AfterglowViewModel) {
    val memory = remember { FocusMemory() }
    val saved = rememberSaveableStateHolder()
    var enterTabContent by remember { mutableStateOf(false) }
    val playback = model.playback
    val detail = model.details
    val sourceSetup = model.sourceSetup
    val minimized = playback != null && model.liveMinimized
    val browseShelves = remember(model.shelves, model.local) { browseShelves(model.shelves, model.local.titles) }
    val catalog = browseShelves.firstOrNull { it.id == model.catalog }
    val resumeCatalog = Kind.entries.firstOrNull { continueCatalog(it) == model.catalog }
    val showAllResumes = model.catalog == continueCatalog(null)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (playback == null || minimized) {
            when {
                !model.ready -> Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                    Muted("Opening DASH…")
                }
                sourceSetup != null -> {
                    BackHandler(onBack = model::closeSourceSetup)
                    FocusScope(memory, "source-setup/$sourceSetup") {
                        SettingsScreen(model, sourceSetup, model::closeSourceSetup)
                    }
                }
                detail != null -> {
                    BackHandler(onBack = model::backFromDetails)
                    val stage = when {
                        !detail.showSources -> "overview"
                        detail.findingSources -> "finding"
                        detail.sources.isEmpty() -> "empty"
                        else -> "sources"
                    }
                    val episodeKey = detail.episode?.id.orEmpty().takeIf { detail.showSources }.orEmpty()
                    val scope = "detail/${detail.title.key}/$episodeKey/$stage"
                    saved.SaveableStateProvider(scope) {
                        FocusScope(memory, scope) { DetailScreen(detail, model) }
                    }
                }
                model.classify -> {
                    BackHandler { model.showGroups(false) }
                    FocusScope(memory, "groups") { GroupsScreen(model) }
                }
                catalog != null || resumeCatalog != null || showAllResumes -> {
                    BackHandler { model.openCatalog(null) }
                    val scope = "catalog/${model.catalog}"
                    saved.SaveableStateProvider(scope) {
                        FocusScope(memory, scope) {
                            if (catalog != null) CatalogScreen(catalog, model)
                            else ContinueScreen(model.progress.filter { resumeCatalog == null || it.title.kind == resumeCatalog }, model)
                        }
                    }
                }
                else -> {
                    BackHandler(model.tab != Tab.HOME || minimized) {
                        if (model.tab != Tab.HOME) model.go(Tab.HOME) else model.exitPlayback()
                    }
                    val scope = "tab/${model.tab.name}"
                    val entry = remember(scope) { FocusRequester() }
                    val content = remember(scope) { FocusRequester() }
                    LaunchedEffect(scope, enterTabContent) {
                        if (enterTabContent) {
                            withFrameNanos { }
                            if (!entry.tryFocus()) content.tryFocus()
                            enterTabContent = false
                        }
                    }
                    saved.SaveableStateProvider(scope) {
                        FocusScope(memory, scope, entry) {
                            Box(Modifier.fillMaxSize().background(BrowseBackground)) {
                                Box(Modifier.fillMaxSize().focusRequester(content).focusGroup()) {
                                    when (model.tab) {
                                        Tab.HOME -> BrowseScreen(model, browseShelves, null)
                                        Tab.MOVIES -> BrowseScreen(model, browseShelves, Kind.MOVIE)
                                        Tab.SERIES -> BrowseScreen(model, browseShelves, Kind.SERIES)
                                        Tab.LIVE -> LiveScreen(model)
                                        Tab.SEARCH -> SearchScreen(model)
                                        Tab.SETTINGS -> SettingsScreen(model)
                                    }
                                }
                                CompositionLocalProvider(LocalFocusArea provides null) {
                                    Navigation(model, Modifier.align(Alignment.TopCenter), if (minimized) model::expandLive else null) { tab ->
                                        if (model.tab != tab) model.go(tab)
                                        enterTabContent = true
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (playback != null) {
            val frame = if (minimized) {
                Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 22.dp)
                    .width((maxWidth * .31f).coerceIn(220.dp, 340.dp)).aspectRatio(16f / 9)
            } else Modifier.fillMaxSize()
            FocusScope(memory, "player/${playback.token}") {
                PlaybackScreen(playback, frame, minimized, model::minimizeLive, model::expandLive,
                    onExit = model::exitPlayback, onStarted = { model.markChannelWatched(playback) }) { position, duration, ended ->
                    model.record(playback, position, duration, ended)
                }
            }
        }
    }
}

internal fun FocusRequester.tryFocus() = try {
    requestFocus(FocusDirection.Enter)
} catch (_: IllegalStateException) {
    false
}

internal fun Modifier.onRemoteDown(action: () -> Boolean): Modifier = onPreviewKeyEvent { event ->
    event.key == Key.DirectionDown && event.type == KeyEventType.KeyDown && action()
}

@Composable
private fun Navigation(
    model: AfterglowViewModel, modifier: Modifier, expandLive: (() -> Unit)?, enter: (Tab) -> Unit
) {
    Row(modifier.fillMaxWidth()
        .background(Brush.verticalGradient(listOf(BrowseBackground, BrowseBackground.copy(alpha = .95f), Color.Transparent)))
        .padding(start = BrowseRail, end = BrowseRail, top = 13.dp, bottom = 17.dp).focusGroup(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Brand()
        Spacer(Modifier.width(14.dp))
        for (tab in Tab.entries) {
            val selected = tab == model.tab
            RemoteButton("nav/${tab.name}", Modifier.onRemoteDown {
                enter(tab)
                true
            }.semantics { this.selected = selected }, radius = 20, onClick = { enter(tab) }) { focused ->
                val tint = if (focused) Ink else if (selected) Porcelain else Fog
                Row(
                    Modifier.background(if (focused) Porcelain else if (selected) Slate else Color.Transparent)
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Glyph(tab.glyph, tint, Modifier.size(17.dp))
                    Text(tab.title, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        if (expandLive != null) {
            IconAction(TvGlyph.EXPAND, "Return to live playback", "nav/live-player", onClick = expandLive)
        }
    }
}

@Composable
internal fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Image(painterResource(R.drawable.dash_mark), contentDescription = null, modifier = Modifier.size(29.dp))
        Text("DASH", color = Porcelain, fontSize = 19.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    }
}

private val Tab.glyph get() = when (this) {
    Tab.HOME -> TvGlyph.HOME
    Tab.SEARCH -> TvGlyph.SEARCH
    Tab.MOVIES -> TvGlyph.MOVIES
    Tab.SERIES -> TvGlyph.SERIES
    Tab.LIVE -> TvGlyph.LIVE
    Tab.SETTINGS -> TvGlyph.SETTINGS
}

@Composable
internal fun EmptyPanel(title: String, note: String, action: String, click: () -> Unit) {
    Column(Modifier.padding(horizontal = Rail, vertical = 25.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Heading(title)
        Muted(note)
        Action(action, TvGlyph.SETTINGS, preferred = true, primary = true, onClick = click)
    }
}

@Composable
internal fun CatalogScreen(shelf: Shelf, model: AfterglowViewModel) {
    LazyColumn(Modifier.fillMaxSize().background(Ink), contentPadding = PaddingValues(Rail, 25.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { IconAction(TvGlyph.BACK, "Back", "catalog/back", preferred = true) { model.openCatalog(null) } }
        item { Heading(shelf.name, size = 38) }
        items(shelf.titles, key = Title::key) { title ->
            ListItem("catalog/${title.key}", title.name, title.provider, title.poster, title.allowHttp) { model.open(title) }
        }
        if (shelf.nextOffset != null) item {
            Action(if (model.busy) "Loading…" else "Load more", TvGlyph.ADD, enabled = !model.busy) { model.nextPage(shelf) }
        }
        model.notice?.let { item { Muted(it) } }
    }
}

@Composable
internal fun ListItem(
    id: String, title: String, subtitle: String = "", image: String = "", allowHttp: Boolean = false,
    preferred: Boolean = false, icon: TvGlyph = TvGlyph.NEXT, subtitleLines: Int = 1, onClick: () -> Unit
) {
    RemoteButton(id, Modifier.fillMaxWidth(), preferred, onClick = onClick) { focused ->
        val tint = if (focused) Ink else Porcelain
        Row(Modifier.fillMaxWidth().background(if (focused) Porcelain else Slate).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
            if (image.isNotBlank()) Artwork(image, allowHttp, Modifier.size(48.dp, 30.dp), ContentScale.Fit)
            Column(Modifier.weight(1f)) {
                Text(title, color = tint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) Text(subtitle, color = if (focused) Ink.copy(alpha = .70f) else Fog,
                    fontSize = 12.sp, lineHeight = 17.sp, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
            }
            Glyph(icon, tint, Modifier.size(19.dp))
        }
    }
}
