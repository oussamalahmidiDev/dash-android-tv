package tv.afterglow

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SearchKinds = listOf(Kind.MOVIE, Kind.SERIES, Kind.LIVE)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchScreen(model: AfterglowViewModel) {
    var editing by remember { mutableStateOf(false) }
    var sawKeyboard by remember { mutableStateOf(false) }
    var draft by remember(editing) { mutableStateOf(TextFieldValue(model.query, TextRange(model.query.length))) }
    val field = rememberEditorFocus(editing)
    val editor = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val grid = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    val results = remember(model.results) { model.results.groupBy(Title::kind) }
    val firstKind = SearchKinds.firstOrNull(results::containsKey)
    val favorites = remember(model.favorites) { model.favorites.mapTo(hashSetOf(), Title::key) }
    val feedback = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(editing) {
        if (editing) {
            grid.requestScrollToItem(0)
            withFrameNanos { }
            editor.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(editing, imeVisible) {
        if (!editing) sawKeyboard = false
        else if (imeVisible) sawKeyboard = true
        else if (sawKeyboard) {
            delay(180)
            grid.requestScrollToItem(0)
            editing = false
        }
    }
    BackHandler(editing && !imeVisible) { editing = false }

    BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
        val gap = 16.dp
        val gridWidth = (maxWidth - BrowseRail * 2).coerceAtLeast(1.dp)
        Column(Modifier.fillMaxSize().padding(top = 79.dp)) {
            Row(Modifier.padding(horizontal = BrowseRail).widthIn(max = 730.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editing) {
                    OutlinedTextField(
                        value = draft, onValueChange = {
                            draft = it
                            model.search(it.text)
                        },
                        modifier = Modifier.weight(1f).focusRequester(editor)
                            .semantics { contentDescription = "Search your library" },
                        singleLine = true, shape = RoundedCornerShape(28.dp),
                        placeholder = { Text("Movies, series, or live channels", color = Fog) },
                        leadingIcon = { Glyph(TvGlyph.SEARCH, Glacier, Modifier.size(23.dp)) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            keyboard?.hide()
                            grid.requestScrollToItem(0)
                            editing = false
                        })
                    )
                } else {
                    RemoteButton(
                        "search/input", Modifier.weight(1f).focusRequester(field)
                            .focusProperties {
                                if (firstKind != null && grid.firstVisibleItemIndex == 0) down = firstResult
                            }
                            .semantics {
                                contentDescription = if (model.query.isBlank()) "Search your library"
                                else "Edit search for ${model.query}"
                            },
                        preferred = true, radius = 28, onClick = { editing = true }
                    ) { focused ->
                        val tint = if (focused) Ink else Porcelain
                        Row(Modifier.fillMaxWidth().height(56.dp)
                            .background(if (focused) Porcelain else Slate).padding(horizontal = 17.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(13.dp)) {
                            Glyph(TvGlyph.SEARCH, if (focused) Ink else Glacier, Modifier.size(23.dp))
                            Text(model.query.ifBlank { "Movies, series, or live channels" }, Modifier.weight(1f),
                                color = if (model.query.isBlank() && !focused) Fog else tint, fontSize = 17.sp,
                                lineHeight = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (model.query.isNotEmpty()) {
                    IconAction(TvGlyph.CLOSE, "Clear search", "search/clear") {
                        if (editing) {
                            editor.requestFocus()
                            draft = TextFieldValue()
                        } else field.requestFocus()
                        model.search("")
                    }
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f), state = grid,
                contentPadding = PaddingValues(start = BrowseRail, end = BrowseRail, top = gap, bottom = 56.dp),
                verticalArrangement = Arrangement.spacedBy(gap)
            ) {
                when {
                    model.query.isBlank() -> item("hint") {
                        Muted("Search across your IPTV playlist and add-ons.")
                    }
                    results.isNotEmpty() -> if (!editing) item("favorites-hint") {
                        Muted("Hold Select on a result to add or remove it from favorites.")
                    }
                    model.searching -> item("searching") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = Glacier, strokeWidth = 2.dp)
                            Muted("Searching your sources…")
                        }
                    }
                    else -> item("no-results") {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Heading("No results", size = 21)
                            Muted("Try another name for “${model.query.trim()}”.")
                        }
                    }
                }
                model.notice?.let { message -> item("notice") { Muted(message) } }

                for (kind in SearchKinds) {
                    val matches = results[kind].orEmpty()
                    if (matches.isEmpty()) continue
                    val minWidth = if (kind == Kind.LIVE) 142.dp else 104.dp
                    val columns = ((gridWidth + gap) / (minWidth + gap)).toInt().coerceAtLeast(1)
                    val cardWidth = (gridWidth - gap * (columns - 1)) / columns
                    item("header/$kind") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Glyph(kind.glyph, Fog, Modifier.size(18.dp))
                            Heading(kind.label, size = 19)
                        }
                    }
                    itemsIndexed(matches.chunked(columns), key = { index, _ -> "search/$kind/$index" }) { index, row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                            for ((column, title) in row.withIndex()) key(title.key) {
                                val focus = if (kind == firstKind && index == 0 && column == 0)
                                    Modifier.focusRequester(firstResult) else Modifier
                                val favorite = FavoriteAction(title.key in favorites) {
                                    model.toggleFavorite(title) { added ->
                                        feedback.currentSnackbarData?.dismiss()
                                        scope.launch {
                                            feedback.showSnackbar(if (added) "Added to favorites" else "Removed from favorites")
                                        }
                                    }
                                }
                                if (kind == Kind.LIVE) {
                                    ChannelCard(title, "search/${title.key}", cardWidth, favorite = favorite,
                                        modifier = focus) { model.open(title) }
                                } else {
                                    PortraitCard(title, "search/${title.key}", cardWidth, rank = null,
                                        preferred = false, favorite = favorite,
                                        height = cardWidth * (if (editing) .76f else 1.5f), modifier = focus) { model.open(title) }
                                }
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(feedback, Modifier.align(Alignment.BottomCenter)
            .padding(horizontal = BrowseRail, vertical = 12.dp).widthIn(max = 340.dp))
    }
}
