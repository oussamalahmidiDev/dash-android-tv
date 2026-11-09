@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package tv.afterglow

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
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

private data class Prepared(val item: MediaItem, val factory: DataSource.Factory, val engine: TorrentEngine? = null) {
    val torrent get() = engine != null
}

private val directFileExtensions = setOf(
    "m3u8", "mpd", "ism", "isml", "mp4", "m4v", "mkv", "webm", "mov", "ts", "m2ts", "avi"
)

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
    val extension = url.encodedPath.substringAfterLast('.').lowercase(Locale.ROOT)
    val type = source.mime ?: if (extension in directFileExtensions) null else attempt {
        val call = client.newCall(Request.Builder().head().url(url).build())
        call.timeout().timeout(1500, TimeUnit.MILLISECONDS)
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
    return Prepared(item, DataSource.Factory { TorrentMediaDataSource(videoFactory, subtitleFactory) }, engine)
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
    model: AfterglowViewModel, onExit: () -> Unit, onStarted: () -> Unit, record: (Long, Long, Boolean) -> Unit
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
    val activeSource = request.source
    val selection = remember(activeSource, retry) { Channel<TorrentVideo>(capacity = 1) }
    var engine by remember { mutableStateOf<TorrentEngine?>(null) }
    var resumePosition by remember(request.token) { mutableLongStateOf(request.resume) }
    val stop = { engine?.cancel(); onExit() }
    BackHandler(enabled = prepared == null && !minimized) {
        if (problem == null) minimize() else stop()
    }

    LaunchedEffect(activeSource, retry) {
        val job = currentCoroutineContext().job
        preparation.set(job)
        files = emptyList()
        prepared = null
        problem = null
        try {
            if (activeSource.url != null) {
                prepared = withContext(Dispatchers.IO) { direct(activeSource) }
            } else {
                TorrentEngine.exclusive.withLock {
                    // Assignment inside the IO block keeps a reference available for cancellation.
                    var active: TorrentEngine? = null
                    try {
                        val metadata = withContext(Dispatchers.IO) {
                            TorrentEngine(context, checkNotNull(activeSource.magnet)).also { active = it }.metadata()
                        }
                        val torrent = checkNotNull(active)
                        engine = torrent
                        val video = metadata.firstOrNull { it.index == activeSource.fileIndex }
                            ?: metadata.singleOrNull() ?: run {
                                files = metadata
                                selection.receive().also { files = emptyList() }
                            }
                        prepared = withContext(Dispatchers.IO) { torrentMedia(torrent, video, activeSource) }
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
            Log.e("AfterglowTorrent", "[PREPARATION_ERROR] ${failure.javaClass.simpleName}")
            problem = (failure as? FeedError)?.message ?: "This stream couldn’t be prepared."
        } catch (linkage: LinkageError) {
            Log.e("AfterglowTorrent", "[LINKAGE_ERROR] ${linkage.javaClass.simpleName}")
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
            key(media) {
                Video(
                    media, request, activeSource, resumePosition, minimized, minimize, expand,
                    sources = model.playerSources,
                    loadingSources = model.loadingPlayerSources,
                    p2p = model.p2p,
                    volume = model.volume,
                    setVolume = model::updateVolume,
                    loadSources = { model.loadSourcesForPlayer(request.title, request.episode) },
                    onSwitchSource = model::switchPlayerSource,
                    stop, onStarted, { position, duration, ended ->
                        if (request.title.kind != Kind.LIVE) {
                            resumePosition = if (ended) 0 else position
                            record(position, duration, ended)
                        }
                    }
                ) { retry++ }
            }
        } else if (minimized) {
            MiniPlayerControls(playbackTitle(request), problem != null, expand, stop)
            if (problem == null && files.isEmpty()) Loading(Modifier.align(Alignment.Center))
        } else {
            val error = problem
            val poster = request.title.backdrop.ifBlank { request.title.poster }
            Box(Modifier.fillMaxSize().background(Ink)) {
                if (poster.isNotBlank()) {
                    Artwork(poster, request.title.allowHttp, Modifier.fillMaxSize(), ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(
                        Brush.radialGradient(listOf(Ink.copy(alpha = 0.55f), Ink.copy(alpha = 0.94f)), radius = 1400f)
                    ))
                    Box(Modifier.fillMaxSize().background(
                        Brush.verticalGradient(listOf(Ink.copy(alpha = 0.65f), Color.Transparent, Ink.copy(alpha = 0.85f), Ink))
                    ))
                }
                Box(Modifier.align(Alignment.TopStart).padding(start = BrowseRail, top = 27.dp)) {
                    IconAction(TvGlyph.BACK, "Back", "player/prepare/back", preferred = files.isEmpty() && error == null, onClick = {
                        if (error == null) minimize() else stop()
                    })
                }
                if (files.isNotEmpty()) {
                    Column(
                        Modifier.align(Alignment.Center).widthIn(max = 640.dp).padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Heading("Choose a video file", size = 26)
                        LazyColumn(
                            Modifier.fillMaxWidth().heightIn(max = 340.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(files, key = TorrentVideo::index) { file ->
                                val megabytes = file.bytes / (1024 * 1024)
                                ListItem("player/file/${file.index}", file.name, "$megabytes MB", preferred = file.index == files.first().index) {
                                    selection.trySend(file)
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (error != null) {
                            Heading(request.episode?.name ?: request.title.name, size = 30)
                            Muted(error, size = 15)
                            Spacer(Modifier.height(4.dp))
                            Action("Try again", TvGlyph.RETRY, "player/prepare/retry", preferred = true, primary = true) { retry++ }
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(46.dp),
                                color = Glacier,
                                strokeWidth = 3.dp
                            )
                            Loading()
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("InflateParams")
@Composable
private fun Video(
    prepared: Prepared, request: PlayRequest, activeSource: Source, initialPosition: Long, minimized: Boolean,
    minimize: () -> Unit, expand: () -> Unit,
    sources: List<Source>, loadingSources: Boolean, p2p: Boolean,
    volume: Int, setVolume: (Int) -> Unit,
    loadSources: () -> Unit, onSwitchSource: (Source) -> Unit,
    exit: () -> Unit, onStarted: () -> Unit,
    record: (Long, Long, Boolean) -> Unit, retry: () -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestRecord by rememberUpdatedState(record)
    val latestStarted by rememberUpdatedState(onStarted)
    val live = request.title.kind == Kind.LIVE
    val player = remember(prepared) {
        makePlayer(context, prepared, initialPosition.takeUnless { live } ?: 0L,
            volume, live || !minimized)
    }
    LaunchedEffect(player, volume) { player.volume = volume / 100f }
    var videoSize by remember(player) { mutableStateOf(VideoSize.UNKNOWN) }
    val quality = when {
        videoSize.width >= 3700 || videoSize.height >= 2100 -> "4K"
        videoSize.width >= 2500 || videoSize.height >= 1400 -> "1440p"
        videoSize.width >= 1850 || videoSize.height >= 1000 -> "1080p"
        videoSize.width >= 1200 || videoSize.height >= 680 -> "720p"
        videoSize.height > 0 -> "${videoSize.height}p"
        else -> sourceQuality(activeSource)
    }
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
    var showSourcePicker by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    var resumeOnExpand by remember(player) { mutableStateOf(true) }
    var wasExpanded by remember(player) { mutableStateOf(!minimized) }
    val volumeFocus = remember { FocusRequester() }
    var restoreVolumeFocus by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var hasPlayed by remember { mutableStateOf(false) }
    val hiddenFocus = remember { FocusRequester() }
    val timelineFocus = remember { FocusRequester() }
    var timelineFocused by remember { mutableStateOf(false) }
    var focusTimeline by remember { mutableStateOf(false) }
    var seekPreview by remember(player) { mutableStateOf<Long?>(null) }
    val canSeek = !live && duration > 0 && player.isCurrentMediaItemSeekable
    val overlayMemory = remember { FocusMemory() }
    val audioFocus = remember { FocusRequester() }
    val subtitleFocus = remember { FocusRequester() }
    var returningToTrack by remember { mutableStateOf<Int?>(null) }

    fun showControls() { visible = true; activity++ }
    fun previewSeek(delta: Long) {
        val base = seekPreview ?: player.currentPosition
        seekPreview = (base + delta).coerceIn(0L, duration)
        showControls()
    }
    LaunchedEffect(seekPreview) {
        val target = seekPreview ?: return@LaunchedEffect
        delay(230)
        player.seekTo(target)
        position = target
        seekPreview = null
    }
    LaunchedEffect(focusTimeline) {
        if (focusTimeline) {
            // Let the reopened overlay restore its button focus before moving to the timeline.
            withFrameNanos { }
            withFrameNanos { }
            timelineFocus.tryFocus()
            focusTimeline = false
        }
    }
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

    fun closeVolume() {
        showVolume = false
        restoreVolumeFocus = true
        showControls()
    }
    LaunchedEffect(restoreVolumeFocus) {
        if (restoreVolumeFocus) {
            withFrameNanos { }
            volumeFocus.tryFocus()
            restoreVolumeFocus = false
        }
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
                val stateName = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($playbackState)"
                }
                Log.i("AfterglowTorrent", "[PLAYER_STATE] state=$stateName, isTorrent=${prepared.torrent}, playWhenReady=${player.playWhenReady}, pos=${player.currentPosition}ms")
                if (playbackState == Player.STATE_ENDED) {
                    ended = true
                    latestRecord(0, player.duration, true)
                    showControls()
                }
            }

            override fun onPlayerError(problem: PlaybackException) {
                Log.e("AfterglowTorrent", "[PLAYER_ERROR] code=${problem.errorCode}, isTorrent=${prepared.torrent}, msg=${problem.message}", problem)
                error = problem
                trackType = null
                showSourcePicker = false
                showVolume = false
                showControls()
            }

            override fun onTracksChanged(updated: Tracks) { tracks = updated }
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
        }
        player.addListener(listener)
        onDispose {
            lifecycle.removeObserver(observer)
            if (hasPlayed || player.currentPosition > 0) latestRecord(player.currentPosition, player.duration, ended)
            player.removeListener(listener)
            prepared.engine?.cancel()
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
        showSourcePicker = false
        showVolume = false
        if (minimized && !live) {
            if (wasExpanded) resumeOnExpand = player.playWhenReady
            player.pause()
        } else if (!minimized) {
            wasExpanded = true
            if (!live && resumeOnExpand && !ended && error == null) player.play()
            showControls()
        }
    }
    LaunchedEffect(minimized, visible, playing, activity, trackType, showSourcePicker, showVolume, error) {
        if (!minimized && visible && playing && trackType == null && !showSourcePicker && !showVolume && error == null) {
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
            showVolume -> closeVolume()
            showSourcePicker -> showSourcePicker = false
            trackType != null -> closeTrackPicker()
            error == null -> minimize()
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
            AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> {
                val wasHidden = !visible
                if (canSeek && error == null && trackType == null && (wasHidden || timelineFocused || focusTimeline)) {
                    val step = if (key.nativeKeyEvent.repeatCount >= 4) 60_000L else 10_000L
                    if (wasHidden) focusTimeline = true
                    previewSeek(if (code == AndroidKeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                    true
                } else {
                    showControls()
                    wasHidden
                }
            }
            AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> {
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
            MiniPlayerControls(playbackTitle(request), error != null, expand, exit)
            if (stage == Player.STATE_BUFFERING && error == null) Loading(Modifier.align(Alignment.Center))
            return@Box
        }
        if (!visible) Box(Modifier.size(1.dp).focusRequester(hiddenFocus).focusable())
        val activeError = error
        if (visible || activeError != null) {
            FocusScope(overlayMemory, if (activeError == null) "video-controls" else "video-error") {
                Box(Modifier.align(Alignment.TopStart).padding(start = Rail, top = 27.dp)) {
                    IconAction(if (live) TvGlyph.CLOSE else TvGlyph.BACK,
                        if (live) "Stop channel" else "Back", "video/back",
                        onClick = if (live || activeError != null) exit else minimize)
                }
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .94f)))
                    ).padding(start = Rail, end = Rail, top = 50.dp, bottom = 27.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Heading(playbackTitle(request), Modifier.weight(1f, fill = false), size = 28)
                        if (quality != null) Muted(quality, Modifier.background(Slate, RoundedCornerShape(8.dp))
                            .padding(horizontal = 9.dp, vertical = 4.dp), size = 13)
                    }
                    if (activeError != null) {
                        Muted(playerError(activeError, prepared.torrent))
                        IconAction(TvGlyph.RETRY, "Retry", "video/retry", preferred = true, primary = true, onClick = retry)
                    } else {
                        if (!live && duration > 0) {
                            val displayedPosition = seekPreview ?: position
                            val timeline = if (canSeek) Modifier.focusRequester(timelineFocus)
                                .onFocusChanged { timelineFocused = it.isFocused }
                                .semantics { contentDescription = "Timeline. Press left or right to seek." }.focusable() else Modifier
                            Row(timeline.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Muted(time(displayedPosition), size = 12)
                                Box(Modifier.weight(1f).height(if (timelineFocused) 7.dp else 4.dp)
                                    .background(Fog.copy(alpha = .36f), RoundedCornerShape(2.dp))) {
                                    Box(Modifier.fillMaxWidth((displayedPosition.toDouble() / duration).coerceIn(0.0, 1.0).toFloat())
                                        .fillMaxHeight().background(if (timelineFocused) Glacier else Porcelain, RoundedCornerShape(2.dp)))
                                }
                                Muted(time(duration), size = 12)
                            }
                        } else if (live) {
                            Muted("Live TV", size = 12)
                        }
                        val shouldPause = playRequested && !ended
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val stacked = maxWidth < 450.dp
                            val playbackAlignment = when {
                                stacked -> Alignment.TopCenter
                                maxWidth < 740.dp -> Alignment.CenterStart
                                else -> Alignment.Center
                            }
                            Spacer(Modifier.height(if (stacked) 104.dp else 54.dp))
                            Row(
                                Modifier.align(playbackAlignment),
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
                                    if (shouldPause) "Pause" else "Play", "video/play",
                                    Modifier.focusProperties { if (canSeek) up = timelineFocus }, preferred = true,
                                    primary = true, onClick = ::toggle)
                                if (!live) {
                                    IconAction(TvGlyph.FORWARD, "Ahead 30 seconds", "video/seek-forward") {
                                        player.seekForward()
                                        showControls()
                                    }
                                }
                            }
                            Row(Modifier.align(if (stacked) Alignment.BottomCenter else Alignment.CenterEnd),
                                horizontalArrangement = Arrangement.spacedBy(if (maxWidth < 275.dp) 4.dp else 10.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                if (!live) {
                                    IconAction(TvGlyph.GRID, "Change source", "video/change-source", onClick = {
                                        trackType = null
                                        showSourcePicker = true
                                        loadSources()
                                    })
                                }
                                IconAction(TvGlyph.MINIMIZE, "Minimize player", "video/minimize", onClick = minimize)
                                IconAction(TvGlyph.AUDIO, "Volume", "video/volume", Modifier.focusRequester(volumeFocus)) {
                                    showVolume = true
                                }
                                if (tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                                    IconAction(TvGlyph.AUDIO_TRACKS, "Audio tracks", "video/audio", Modifier.focusRequester(audioFocus)) {
                                        showSourcePicker = false
                                        trackType = C.TRACK_TYPE_AUDIO
                                    }
                                }
                                if (tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                                    IconAction(TvGlyph.SUBTITLES, "Subtitles", "video/subtitles", Modifier.focusRequester(subtitleFocus)) {
                                        showSourcePicker = false
                                        trackType = C.TRACK_TYPE_TEXT
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (stage == Player.STATE_BUFFERING && activeError == null && trackType == null && !showSourcePicker && !showVolume) {
            Loading(Modifier.align(Alignment.Center))
        }
        if (showVolume) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = Rail, bottom = 111.dp)) {
                Popup(alignment = Alignment.BottomEnd, onDismissRequest = ::closeVolume,
                    properties = PopupProperties(focusable = true)) {
                    FocusScope(overlayMemory, "volume") { VolumeControl(volume, setVolume, ::closeVolume) }
                }
            }
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
        if (showSourcePicker) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = Rail, bottom = 111.dp)) {
                Popup(alignment = Alignment.BottomEnd, onDismissRequest = { showSourcePicker = false },
                    properties = PopupProperties(focusable = true)) {
                    FocusScope(overlayMemory, "source-picker") {
                        SourceFloatingPicker(
                            sources = sources,
                            currentSource = activeSource,
                            loading = loadingSources,
                            p2p = p2p,
                            onSelect = { selectedSource ->
                                showSourcePicker = false
                                if (selectedSource != activeSource) {
                                    onSwitchSource(selectedSource)
                                }
                            },
                            close = { showSourcePicker = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Loading(modifier: Modifier = Modifier) {
    Muted("Loading...", modifier.background(Color.Black.copy(alpha = .65f), RoundedCornerShape(8.dp))
        .padding(horizontal = 13.dp, vertical = 7.dp))
}

@Composable
private fun VolumeControl(volume: Int, setVolume: (Int) -> Unit, close: () -> Unit) {
    Column(Modifier.width(256.dp).background(Slate, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Heading("Volume", size = 20)
            Muted("$volume%")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconAction(TvGlyph.REMOVE, "Lower volume", "volume/down", preferred = true) {
                setVolume((volume - 10).coerceAtLeast(0))
            }
            IconAction(TvGlyph.ADD, "Raise volume", "volume/up") {
                setVolume((volume + 10).coerceAtMost(100))
            }
            IconAction(TvGlyph.CLOSE, "Close volume", "volume/close", onClick = close)
        }
    }
}

private fun playbackTitle(request: PlayRequest): String {
    val episode = request.episode ?: return request.title.name
    val number = listOfNotNull(episode.season?.let { "S$it" }, episode.number?.let { "E$it" }).joinToString(", ")
    return listOf(number, episode.name).filter(String::isNotBlank).joinToString("  ·  ")
}

private val qualityPattern = Regex("""\b(4k|2160p|1440p|1080p|720p|480p|360p)\b""", RegexOption.IGNORE_CASE)
private fun sourceQuality(source: Source): String? {
    val value = qualityPattern.find(source.label + " " + source.description)?.value?.lowercase(Locale.ROOT)
    return if (value == "2160p" || value == "4k") "4K" else value
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
        if (failed) Muted("Playback stopped. Open full screen to retry.", size = 11)
    }
}

private fun makePlayer(context: Context, media: Prepared, position: Long, volume: Int, autoplay: Boolean): ExoPlayer {
    val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(
            /* minBufferMs = */ 15_000,
            /* maxBufferMs = */ 50_000,
            /* bufferForPlaybackMs = */ 500,
            /* bufferForPlaybackAfterRebufferMs = */ 2_000
        )
        .setPrioritizeTimeOverSizeThresholds(true)
        .setBackBuffer(10_000, false)
        .build()
    val player = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(30_000)
        .build()
    val audioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()
    player.setAudioAttributes(audioAttributes, true)
    val factory = DefaultMediaSourceFactory(media.factory)
    player.setMediaSource(factory.createMediaSource(media.item), position)
    player.volume = volume / 100f
    player.playWhenReady = autoplay
    player.prepare()
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

@Composable
private fun SourceFloatingPicker(
    sources: List<Source>, currentSource: Source, loading: Boolean, p2p: Boolean,
    onSelect: (Source) -> Unit, close: () -> Unit
) {
    val shape = RoundedCornerShape(15.dp)
    val selectedIndex = sources.indexOfFirst {
        it.provider == currentSource.provider && it.label == currentSource.label
    }.coerceAtLeast(0)
    Column(
        Modifier.width(280.dp).background(Color(0xF22B2B2B), shape)
            .border(1.dp, Color.White.copy(alpha = .18f), shape).padding(11.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 7.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Heading("Sources", size = 16)
            IconAction(TvGlyph.CLOSE, "Close", "sources/close", onClick = close)
        }
        if (loading) {
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), color = Glacier, strokeWidth = 2.dp)
                Muted("Finding sources…", size = 13)
            }
        } else if (sources.isEmpty()) {
            Muted("No other streams available.", Modifier.padding(horizontal = 8.dp, vertical = 10.dp), size = 13)
        } else {
            LazyColumn(
                Modifier.heightIn(max = 240.dp),
                state = rememberLazyListState(selectedIndex),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(sources, key = { index, s -> s.provider + s.label + index }) { index, s ->
                    val isSelected = s.provider == currentSource.provider && s.label == currentSource.label
                    val transport = if (s.url != null) "Direct" else if (p2p) "Torrent" else "Torrent off"
                    RemoteButton(
                        id = "floating-sources/$index",
                        modifier = Modifier.fillMaxWidth().semantics {
                            role = Role.RadioButton
                            selected = isSelected
                        },
                        preferred = index == selectedIndex,
                        radius = 8,
                        onClick = { onSelect(s) }
                    ) { focused ->
                        val tint = if (focused) Ink else Porcelain
                        val mutedTint = if (focused) Ink.copy(alpha = 0.70f) else Fog
                        Row(
                            Modifier.fillMaxWidth().background(if (focused) Porcelain else Color.Transparent)
                                .padding(horizontal = 8.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Glyph(
                                if (isSelected) TvGlyph.CHECK else TvGlyph.RADIO,
                                if (isSelected && !focused) Glacier else tint,
                                Modifier.size(16.dp)
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                val desc = listOf(s.provider, transport, s.description.trim()).filter(String::isNotBlank).joinToString(" · ")
                                Text(desc, color = mutedTint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
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
    else -> if (torrent) "This source stopped sending video. Try again or choose another source."
        else "The stream stopped. Retry or choose another source."
}

private fun time(millis: Long): String {
    val total = (millis.coerceAtLeast(0) / 1000)
    val hours = total / 3600
    val minutes = total / 60 % 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
