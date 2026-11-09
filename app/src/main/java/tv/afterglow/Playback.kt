@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.afterglow

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class Prepared(val item: MediaItem, val factory: DataSource.Factory, val torrent: Boolean = false)

private fun direct(source: Source): Prepared {
    val url = Network.checked(checkNotNull(source.url), source.allowHttp)
    val client = Network.client(source.allowHttp).newBuilder().addNetworkInterceptor { chain ->
        val request = chain.request()
        val target = request.url
        val sameOrigin = target.scheme == url.scheme && target.host == url.host && target.port == url.port
        val builder = request.newBuilder()
        source.headers.forEach { (name, value) ->
            if (sameOrigin) builder.header(name, value) else builder.removeHeader(name)
        }
        chain.proceed(builder.build())
    }.build()
    val type = source.mime ?: attempt {
        val call = client.newCall(Request.Builder().head().url(url).build())
        call.timeout().timeout(4, TimeUnit.SECONDS)
        call.execute().use { response ->
            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            if (!response.isSuccessful) null else when (contentType) {
                "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl" -> MimeTypes.APPLICATION_M3U8
                MimeTypes.APPLICATION_MPD -> MimeTypes.APPLICATION_MPD
                else -> null
            }
        }
    }
    val item = MediaItem.Builder().setUri(url.toString()).setMimeType(type)
        .setSubtitleConfigurations(subtitleConfigurations(source)).build()
    return Prepared(item, OkHttpDataSource.Factory(client))
}

private fun subtitleConfigurations(source: Source) = source.subtitles.mapNotNull { subtitle ->
    val uri = subtitle.url.httpUrl()?.takeIf { it.isHttps || source.allowHttp } ?: return@mapNotNull null
    val mime = when (uri.encodedPath.substringAfterLast('.').lowercase(Locale.ROOT)) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ttml", "dfxp", "xml" -> MimeTypes.APPLICATION_TTML
        "ssa", "ass" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }
    MediaItem.SubtitleConfiguration.Builder(Uri.parse(uri.toString())).setMimeType(mime)
        .setLanguage(subtitle.language).build()
}

private fun torrentMedia(engine: TorrentEngine, video: TorrentVideo, source: Source): Prepared {
    val uri = Uri.Builder().scheme("torrent").authority("video").appendPath(video.name).build()
    val item = MediaItem.Builder().setUri(uri).setSubtitleConfigurations(subtitleConfigurations(source)).build()
    val videoFactory = engine.select(video)
    val subtitleFactory = OkHttpDataSource.Factory(Network.client(source.allowHttp))
    return Prepared(item, DataSource.Factory { TorrentMediaDataSource(videoFactory, subtitleFactory) }, torrent = true)
}

private class TorrentMediaDataSource(
    private val video: DataSource.Factory, private val network: DataSource.Factory
) : DataSource {
    private val listeners = mutableListOf<TransferListener>()
    private var active: DataSource? = null

    override fun addTransferListener(listener: TransferListener) {
        if (listener !in listeners) {
            listeners += listener
            active?.addTransferListener(listener)
        }
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = (if (dataSpec.uri.scheme == "torrent") video else network).createDataSource()
        listeners.forEach(source::addTransferListener)
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int) = checkNotNull(active).read(buffer, offset, length)
    override fun getUri() = active?.uri
    override fun getResponseHeaders() = active?.responseHeaders.orEmpty()
    override fun close() {
        try { active?.close() } finally { active = null }
    }
}

@Composable
internal fun PlaybackScreen(
    request: PlayRequest, modifier: Modifier, minimized: Boolean, minimize: () -> Unit, expand: () -> Unit,
    onExit: () -> Unit, onStarted: () -> Unit, record: (Long, Long, Boolean) -> Unit
) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val preparation = remember { java.util.concurrent.atomic.AtomicReference<Job?>() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) preparation.get()?.cancel()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var prepared by remember { mutableStateOf<Prepared?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf(emptyList<TorrentVideo>()) }
    var retry by remember { mutableIntStateOf(0) }
    val selection = remember(retry) { Channel<TorrentVideo>(capacity = 1) }
    var engine by remember { mutableStateOf<TorrentEngine?>(null) }
    var resumePosition by remember { mutableLongStateOf(request.resume) }
    val exit = { engine?.cancel(); onExit() }
    BackHandler(enabled = prepared == null && !minimized, onBack = exit)

    LaunchedEffect(request.token, retry) {
        val job = currentCoroutineContext().job
        preparation.set(job)
        files = emptyList()
        prepared = null
        problem = null
        try {
            if (request.source.url != null) {
                prepared = withContext(Dispatchers.IO) { direct(request.source) }
            } else {
                TorrentEngine.exclusive.withLock {
                    // Assignment inside the IO block keeps a reference available for cancellation.
                    var active: TorrentEngine? = null
                    try {
                        val metadata = withContext(Dispatchers.IO) {
                            TorrentEngine(context, checkNotNull(request.source.magnet)).also { active = it }.metadata()
                        }
                        val torrent = checkNotNull(active)
                        engine = torrent
                        val video = metadata.firstOrNull { it.index == request.source.fileIndex }
                            ?: metadata.singleOrNull() ?: run {
                                files = metadata
                                selection.receive().also { files = emptyList() }
                            }
                        prepared = withContext(Dispatchers.IO) { torrentMedia(torrent, video, request.source) }
                        awaitCancellation()
                    } finally {
                        active?.cancel()
                        engine = null
                        withContext(NonCancellable + Dispatchers.IO) { active?.close() }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            problem = (failure as? FeedError)?.message ?: "This stream couldn’t be prepared."
        } catch (_: LinkageError) {
            problem = "Torrent playback is unavailable on this device."
        } finally {
            preparation.compareAndSet(job, null)
        }
    }

    val shape = RoundedCornerShape(14.dp)
    val appearance = if (minimized) Modifier.clip(shape).border(1.dp, Fog.copy(alpha = .42f), shape) else Modifier
    Box(modifier.then(appearance).background(Color.Black)) {
        val media = prepared
        if (media != null) {
            Video(media, request, resumePosition, minimized, minimize, expand, exit, onStarted, { position, duration, ended ->
                if (request.title.kind != Kind.LIVE) {
                    resumePosition = if (ended) 0 else position
                    record(position, duration, ended)
                }
            }) { retry++ }
        } else {
            val error = problem
            LazyColumn(Modifier.fillMaxSize().background(Ink),
                contentPadding = PaddingValues(horizontal = Rail, vertical = 30.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    IconAction(TvGlyph.BACK, "Back", "player/prepare/back", preferred = true, onClick = exit)
                    Spacer(Modifier.height(16.dp))
                    Heading(request.episode?.name ?: request.title.name, size = 34)
                    Muted(error ?: when {
                        files.isNotEmpty() -> "Choose a video file."
                        request.source.magnet != null -> "Finding peers and reading torrent metadata…"
                        else -> "Opening stream…"
                    })
                }
                if (error != null) item { IconAction(TvGlyph.RETRY, "Retry", "player/prepare/retry", primary = true) { retry++ } }
                items(files, key = TorrentVideo::index) { file ->
                    val megabytes = file.bytes / (1024 * 1024)
                    ListItem("player/file/${file.index}", file.name, "$megabytes MB") { selection.trySend(file) }
                }
            }
        }
    }
}

@SuppressLint("InflateParams")
@Composable
private fun Video(
    prepared: Prepared, request: PlayRequest, initialPosition: Long, minimized: Boolean,
    minimize: () -> Unit, expand: () -> Unit, exit: () -> Unit, onStarted: () -> Unit,
    record: (Long, Long, Boolean) -> Unit, retry: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestRecord by rememberUpdatedState(record)
    val latestStarted by rememberUpdatedState(onStarted)
    val live = request.title.kind == Kind.LIVE
    val player = remember(prepared) { makePlayer(context, prepared, initialPosition.takeUnless { live } ?: 0L) }
    var playing by remember { mutableStateOf(false) }
    var playRequested by remember(player) { mutableStateOf(player.playWhenReady) }
    var stage by remember(player) { mutableIntStateOf(player.playbackState) }
    var error by remember { mutableStateOf<PlaybackException?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(C.TIME_UNSET) }
    var visible by remember { mutableStateOf(true) }
    var activity by remember { mutableIntStateOf(0) }
    var trackType by remember { mutableStateOf<Int?>(null) }
    var ended by remember { mutableStateOf(false) }
    var hasPlayed by remember { mutableStateOf(false) }
    val hiddenFocus = remember { FocusRequester() }
    val overlayMemory = remember { FocusMemory() }
    val audioFocus = remember { FocusRequester() }
    val subtitleFocus = remember { FocusRequester() }
    var returningToTrack by remember { mutableStateOf<Int?>(null) }

    fun showControls() { visible = true; activity++ }
    fun toggle() {
        if (player.playWhenReady && !ended) player.pause()
        else {
            if (ended) {
                ended = false
                player.seekTo(0)
            }
            player.play()
        }
        showControls()
    }

    fun closeTrackPicker() {
        val type = trackType ?: return
        returningToTrack = type
        trackType = null
        showControls()
    }

    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
                if (hasPlayed) latestRecord(player.currentPosition, player.duration, ended)
            }
        }
        lifecycle.addObserver(observer)
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (isPlaying && !hasPlayed) {
                    hasPlayed = true
                    latestStarted()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playRequested = playWhenReady
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                stage = playbackState
                if (playbackState == Player.STATE_ENDED) {
                    ended = true
                    latestRecord(0, player.duration, true)
                    showControls()
                }
            }

            override fun onPlayerError(problem: PlaybackException) {
                error = problem
                trackType = null
                showControls()
            }

            override fun onTracksChanged(updated: Tracks) { tracks = updated }
        }
        player.addListener(listener)
        onDispose {
            lifecycle.removeObserver(observer)
            if (hasPlayed || player.currentPosition > 0) latestRecord(player.currentPosition, player.duration, ended)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(player) {
        var lastSaved = 0L
        while (true) {
            position = player.currentPosition
            duration = player.duration
            if (!live && playing && kotlin.math.abs(position - lastSaved) >= 4_000) {
                latestRecord(position, duration, false)
                lastSaved = position
            }
            delay(400)
        }
    }
    LaunchedEffect(minimized) {
        trackType = null
        if (!minimized) showControls()
    }
    LaunchedEffect(minimized, visible, playing, activity, trackType, error) {
        if (!minimized && visible && playing && trackType == null && error == null) {
            delay(6_500)
            visible = false
        }
    }
    LaunchedEffect(minimized, visible) {
        if (!minimized && !visible) {
            withFrameNanos { }
            hiddenFocus.requestFocus()
        }
    }
    LaunchedEffect(returningToTrack) {
        val type = returningToTrack ?: return@LaunchedEffect
        withFrameNanos { }
        val requester = if (type == C.TRACK_TYPE_AUDIO) audioFocus else subtitleFocus
        if (error == null && tracks.groups.any { it.type == type }) requester.requestFocus()
        returningToTrack = null
    }
    BackHandler(!minimized) {
        when {
            trackType != null -> closeTrackPicker()
            live && error == null -> minimize()
            else -> exit()
        }
    }

    Box(Modifier.fillMaxSize().onPreviewKeyEvent { key ->
        val code = key.nativeKeyEvent.keyCode
        if (minimized || key.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (code) {
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_HEADSETHOOK -> { toggle(); true }
            AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); showControls(); true }
            AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); showControls(); true }
            AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (!live) player.seekForward(); showControls(); true }
            AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { if (!live) player.seekBack(); showControls(); true }
            AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER -> {
                if (!visible) { toggle(); true } else { activity++; false }
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN,
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                val wasHidden = !visible
                showControls()
                wasHidden
            }
            else -> false
        }
    }) {
        key(minimized) {
            AndroidView(factory = { ctx ->
                val view = if (minimized) {
                    // AndroidView supplies the parent and measures the video using its Compose bounds.
                    LayoutInflater.from(ctx).inflate(R.layout.mini_player, null, false) as PlayerView
                } else PlayerView(ctx)
                view.apply {
                    useController = false
                    isFocusable = false
                }
            }, modifier = Modifier.fillMaxSize(), update = { it.player = player }, onRelease = { it.player = null })
        }

        if (minimized) {
            MiniPlayerControls(request.title.name, error != null, expand, exit)
            return@Box
        }
        if (!visible) Box(Modifier.size(1.dp).focusRequester(hiddenFocus).focusable())
        val activeError = error
        if (visible || activeError != null) {
            FocusScope(overlayMemory, if (activeError == null) "video-controls" else "video-error") {
                Box(Modifier.align(Alignment.TopStart).padding(start = Rail, top = 27.dp)) {
                    IconAction(if (live) TvGlyph.CLOSE else TvGlyph.BACK,
                        if (live) "Stop channel" else "Back", "video/back", onClick = exit)
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .94f)))
                    ).padding(start = Rail, end = Rail, top = 50.dp, bottom = 27.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Heading(request.episode?.name ?: request.title.name, size = 28)
                    if (activeError != null) {
                        Muted(playerError(activeError, prepared.torrent))
                        IconAction(TvGlyph.RETRY, "Retry", "video/retry", preferred = true, primary = true, onClick = retry)
                    } else {
                        if (!live && duration > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Muted(time(position), size = 12)
                                Box(Modifier.weight(1f).height(4.dp)
                                    .background(Fog.copy(alpha = .36f), RoundedCornerShape(2.dp))) {
                                    Box(Modifier.fillMaxWidth((position.toDouble() / duration).coerceIn(0.0, 1.0).toFloat())
                                        .fillMaxHeight().background(Porcelain, RoundedCornerShape(2.dp)))
                                }
                                Muted(time(duration), size = 12)
                            }
                        } else if (live) {
                            Muted("Live TV", size = 12)
                        }
                        val shouldPause = playRequested && !ended
                        Box(Modifier.fillMaxWidth().height(54.dp)) {
                            Row(
                                Modifier.align(Alignment.Center),
                                horizontalArrangement = Arrangement.spacedBy(13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!live) {
                                    IconAction(TvGlyph.REWIND, "Back 10 seconds", "video/seek-back") {
                                        player.seekBack()
                                        showControls()
                                    }
                                }
                                IconAction(if (shouldPause) TvGlyph.PAUSE else TvGlyph.PLAY,
                                    if (shouldPause) "Pause" else "Play", "video/play", preferred = true,
                                    primary = true, onClick = ::toggle)
                                if (!live) {
                                    IconAction(TvGlyph.FORWARD, "Ahead 30 seconds", "video/seek-forward") {
                                        player.seekForward()
                                        showControls()
                                    }
                                }
                            }
                            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (live) {
                                    IconAction(TvGlyph.MINIMIZE, "Minimize live channel", "video/minimize", onClick = minimize)
                                }
                                if (tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                                    IconAction(TvGlyph.AUDIO, "Audio", "video/audio", Modifier.focusRequester(audioFocus)) {
                                        trackType = C.TRACK_TYPE_AUDIO
                                    }
                                }
                                if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                                    IconAction(TvGlyph.SUBTITLES, "Subtitles", "video/subtitles", Modifier.focusRequester(subtitleFocus)) {
                                        trackType = C.TRACK_TYPE_TEXT
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (stage == Player.STATE_BUFFERING && activeError == null && trackType == null) {
            Muted(if (prepared.torrent) "Buffering verified pieces…" else "Buffering…",
                Modifier.align(Alignment.Center).background(Color.Black.copy(alpha = .65f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 13.dp, vertical = 7.dp))
        }
        trackType?.let { type ->
            Box(Modifier.align(Alignment.BottomEnd).padding(end = Rail, bottom = 111.dp)) {
                Popup(alignment = Alignment.BottomEnd, onDismissRequest = ::closeTrackPicker,
                    properties = PopupProperties(focusable = true)) {
                    FocusScope(overlayMemory, "tracks/$type") {
                        TrackPicker(player, tracks, type, ::closeTrackPicker)
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxScope.MiniPlayerControls(title: String, failed: Boolean, expand: () -> Unit, close: () -> Unit) {
    Row(Modifier.align(Alignment.TopEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IconAction(TvGlyph.EXPAND, "Full screen", "mini/expand", onClick = expand)
        IconAction(TvGlyph.CLOSE, "Close mini-player", "mini/close", onClick = close)
    }
    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .88f))))
        .padding(start = 12.dp, end = 12.dp, top = 17.dp, bottom = 10.dp)) {
        Text(title, color = Porcelain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (failed) Muted("Channel unavailable. Open full screen to retry.", size = 11)
    }
}

private fun makePlayer(context: Context, media: Prepared, position: Long): ExoPlayer {
    val player = ExoPlayer.Builder(context).setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(30_000).build()
    player.setAudioAttributes(AudioAttributes.DEFAULT, true)
    val factory = DefaultMediaSourceFactory(media.factory)
    player.setMediaSource(factory.createMediaSource(media.item), position)
    player.prepare()
    player.playWhenReady = true
    return player
}

private data class TrackOption(
    val label: String, val selected: Boolean, val override: TrackSelectionOverride? = null
)

@Composable
private fun TrackPicker(player: Player, tracks: Tracks, type: Int, close: () -> Unit) {
    val subtitle = type == C.TRACK_TYPE_TEXT
    val parameters = player.trackSelectionParameters
    val choices = tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
    }.mapIndexed { index, (group, track) ->
        val format = group.getTrackFormat(track)
        val label = format.label?.takeIf(String::isNotBlank)
            ?: format.language?.takeUnless { it.isBlank() || it == "und" }?.let { Locale.forLanguageTag(it).displayLanguage }
            ?: "Track ${index + 1}"
        val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(track))
        val selected = if (subtitle) group.isTrackSelected(track)
            else parameters.overrides[group.mediaTrackGroup]?.trackIndices?.contains(track) == true
        TrackOption(label, selected, override)
    }
    val options = listOf(TrackOption(if (subtitle) "Off" else "Automatic", choices.none(TrackOption::selected))) + choices
    val selectedIndex = options.indexOfFirst(TrackOption::selected).coerceAtLeast(0)
    val shape = RoundedCornerShape(15.dp)
    Column(Modifier.width(244.dp).background(Color(0xF22B2B2B), shape)
        .border(1.dp, Color.White.copy(alpha = .18f), shape).padding(11.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 7.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Heading(if (subtitle) "Subtitles" else "Audio", size = 16)
            IconAction(TvGlyph.CLOSE, "Close", "tracks/close", onClick = close)
        }
        LazyColumn(Modifier.heightIn(max = 198.dp), state = rememberLazyListState(selectedIndex)) {
            itemsIndexed(options, key = { index, _ -> index }) { index, option ->
                RemoteButton(
                    id = "tracks/$index",
                    modifier = Modifier.fillMaxWidth().semantics {
                        role = Role.RadioButton
                        selected = option.selected
                    },
                    preferred = index == selectedIndex,
                    radius = 7,
                    onClick = {
                        val selection = parameters.buildUpon()
                        selection.clearOverridesOfType(type)
                        selection.setTrackTypeDisabled(type, subtitle && option.override == null)
                        option.override?.let(selection::setOverrideForType)
                        player.trackSelectionParameters = selection.build()
                        close()
                    }
                ) { focused ->
                    Row(
                        Modifier.fillMaxWidth().background(if (focused) Porcelain else Color.Transparent)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val tint = if (focused) Ink else Porcelain
                        Glyph(if (option.selected) TvGlyph.CHECK else TvGlyph.RADIO,
                            if (option.selected && !focused) Glacier else tint, Modifier.size(17.dp))
                        Text(option.label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun playerError(error: PlaybackException, torrent: Boolean): String = when (error.errorCode) {
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
        "This TV doesn’t support the stream’s video or audio format."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this stream. Try again or choose another source."
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "The stream couldn’t connect. Check the network and try again."
    else -> if (torrent) "Torrent playback stopped. Try again or choose a source with more peers."
        else "The stream stopped. Retry or choose another source."
}

private fun time(millis: Long): String {
    val total = (millis.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
