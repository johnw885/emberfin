package com.johnw.emberfin.player

import android.view.KeyEvent as AndroidKey
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import android.view.SurfaceView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import com.johnw.emberfin.playback.PlaybackController
import com.johnw.emberfin.EmberfinApp
import com.johnw.emberfin.playback.SegmentUi
import kotlinx.coroutines.delay

/** Which overlay control the D-pad is on. The screen keeps a single Compose focus target and tracks this itself. */
enum class PlayerFocus { SEEK_BAR, EPISODES, AUDIO_SUBS, NEXT_EPISODE, PILL }

/**
 * The player. Entry point for every "play" action:
 * `PlayerScreen(PlayerArgs(itemId, PlayerArgs.RESUME or 0), onExit = { go back })`.
 * It owns its ViewModel (cleared when the screen leaves composition, which sends the final
 * "stopped" report) and exits by itself when the app goes to the background.
 */
@Composable
fun PlayerScreen(args: PlayerArgs, onExit: () -> Unit, onOpen: (itemId: String) -> Unit = {}) {
    val app = LocalContext.current.applicationContext as EmberfinApp
    val owner = remember(args) { object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() } }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }

    val exit by rememberUpdatedState(onExit)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Home button / input switch: stop and save the resume point.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) exit() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        val vm: PlayerViewModel = viewModel(factory = viewModelFactory { initializer { PlayerViewModel(app, args) } })
        PlayerContent(vm, onExit, onOpen)
    }
}

@Composable
private fun PlayerContent(vm: PlayerViewModel, onExit: () -> Unit, onOpen: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val episodes by vm.episodes.collectAsStateWithLifecycle()
    var episodesRow by remember { mutableStateOf(EpisodesRow.EPISODES) }
    var episodeFocus by remember { mutableIntStateOf(0) }
    var postFocus by remember { mutableIntStateOf(0) }
    var promptFocus by remember { mutableIntStateOf(0) }
    val cues by vm.controller.cues.collectAsStateWithLifecycle()
    val player = vm.controller.player
    val focusRequester = remember { FocusRequester() }
    val sheets = remember { TrickplaySheets() }
    val prefs = vm.settings

    var overlay by remember { mutableStateOf(true) }
    var lastInput by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var focus by remember { mutableStateOf(PlayerFocus.SEEK_BAR) }
    var scrubTarget by remember { mutableStateOf<Long?>(null) }
    var scrubTick by remember { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var menuColumn by remember { mutableIntStateOf(0) }
    var menuRow by remember { mutableIntStateOf(0) }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var firstFrame by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { firstFrame = false }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.takeIf { it > 0 } ?: 0L
            playing = player.playWhenReady
            buffering = player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE
            val vs = player.videoSize
            if (vs.width > 0 && vs.height > 0) aspect = vs.width * vs.pixelWidthHeightRatio / vs.height
            delay(200)
        }
    }

    // Commit a scrub once the remote has been quiet for a moment.
    LaunchedEffect(scrubTick) {
        val target = scrubTarget ?: return@LaunchedEffect
        delay(SCRUB_COMMIT_MS)
        vm.controller.seekTo(target)
        scrubTarget = null
        vm.userSeeking = false
    }

    // Auto-hide the overlay while playing.
    LaunchedEffect(lastInput, overlay, playing, menuOpen, scrubTarget, episodes == null) {
        if (overlay && playing && !menuOpen && scrubTarget == null && episodes == null) {
            delay(OVERLAY_HIDE_MS)
            overlay = false
            if (focus != PlayerFocus.PILL) focus = PlayerFocus.SEEK_BAR
        }
    }

    // The skip pill / next-up card takes focus, unless the user is scrubbing or in the menus.
    LaunchedEffect(state.segment) {
        val pill = state.segment is SegmentUi.SkipButton || state.segment is SegmentUi.NextUp
        if (pill && scrubTarget == null && !menuOpen && !(overlay && focus != PlayerFocus.SEEK_BAR)) focus = PlayerFocus.PILL
        if (!pill && focus == PlayerFocus.PILL) focus = PlayerFocus.SEEK_BAR
    }

    LaunchedEffect(state.finished) { if (state.finished) onExit() }

    // Episodes panel: start on the episode that's playing.
    val panelEpisodes = episodes?.episodes
    LaunchedEffect(panelEpisodes) {
        val list = panelEpisodes ?: return@LaunchedEffect
        episodeFocus = list.indexOfFirst { it.current }.coerceAtLeast(0)
    }
    LaunchedEffect(episodes == null) { if (episodes != null) episodesRow = EpisodesRow.EPISODES }
    LaunchedEffect(state.postPlay?.ended, state.postPlay == null) { postFocus = 0 }
    LaunchedEffect(state.stillWatching) { promptFocus = 0 }
    // A phone paused, seeked or changed a track: show where things are.
    LaunchedEffect(state.remotePing) {
        if (state.remotePing > 0) {
            overlay = true
            lastInput = System.currentTimeMillis()
        }
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(5_000)
            vm.clearNotice()
        }
    }

    fun showOverlay() {
        overlay = true
        lastInput = System.currentTimeMillis()
    }

    fun scrub(forward: Boolean, repeat: Int) {
        val step = when {
            repeat < 10 -> 10_000L
            repeat < 30 -> 30_000L
            else -> 60_000L
        }
        val base = scrubTarget ?: player.currentPosition
        val max = duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        scrubTarget = (base + if (forward) step else -step).coerceIn(0, max)
        vm.userSeeking = true
        focus = PlayerFocus.SEEK_BAR
        scrubTick++
        showOverlay()
    }

    fun commitScrub() {
        scrubTarget?.let { vm.controller.seekTo(it) }
        scrubTarget = null
        vm.userSeeking = false
    }

    val subtitleOptions = listOf<TrackOption?>(null) + state.subtitleTracks

    /** Keys while "Are you still watching?" is up. */
    fun onPromptKey(code: Int) {
        when (code) {
            AndroidKey.KEYCODE_DPAD_LEFT -> promptFocus = 0
            AndroidKey.KEYCODE_DPAD_RIGHT -> promptFocus = 1
            in OK_KEYS -> if (promptFocus == 0) vm.continueWatching() else onExit()
        }
    }

    /** Keys while the Episodes panel is open. */
    fun onEpisodesKey(code: Int, panel: EpisodesPanel) {
        when (code) {
            AndroidKey.KEYCODE_DPAD_UP -> if (panel.seasons.size > 1) episodesRow = EpisodesRow.SEASONS
            AndroidKey.KEYCODE_DPAD_DOWN -> episodesRow = EpisodesRow.EPISODES
            AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT -> {
                val step = if (code == AndroidKey.KEYCODE_DPAD_RIGHT) 1 else -1
                if (episodesRow == EpisodesRow.SEASONS) vm.showSeason((panel.season + step).coerceIn(0, panel.seasons.lastIndex))
                else episodeFocus = (episodeFocus + step).coerceIn(0, (panel.episodes.size - 1).coerceAtLeast(0))
            }
            in OK_KEYS -> when (episodesRow) {
                EpisodesRow.SEASONS -> episodesRow = EpisodesRow.EPISODES
                EpisodesRow.EPISODES -> panel.episodes.getOrNull(episodeFocus)?.let {
                    vm.playEpisode(it.id)
                    overlay = false
                }
            }
        }
    }

    /** Keys while "More like this" is up. */
    fun onPostPlayKey(code: Int, post: PostPlay) {
        when (code) {
            AndroidKey.KEYCODE_DPAD_LEFT -> postFocus = (postFocus - 1).coerceAtLeast(0)
            AndroidKey.KEYCODE_DPAD_RIGHT -> postFocus = (postFocus + 1).coerceAtMost(post.items.lastIndex)
            in OK_KEYS -> post.items.getOrNull(postFocus)?.let { onOpen(it.id) }
        }
    }

    fun onKey(ev: KeyEvent): Boolean {
        val native = ev.nativeKeyEvent
        val code = native.keyCode
        val handled = code in HANDLED_KEYS
        if (ev.type != KeyEventType.KeyDown) return handled
        lastInput = System.currentTimeMillis()
        vm.onUserInput()

        val panel = episodes
        val post = state.postPlay
        if (code == AndroidKey.KEYCODE_BACK) {
            when {
                state.error != null -> onExit()
                state.stillWatching != null -> onExit()
                menuOpen -> menuOpen = false
                panel != null -> vm.closeEpisodes()
                post != null && post.ended -> onExit()
                post != null -> vm.dismissSegment()
                scrubTarget != null -> { scrubTarget = null; vm.userSeeking = false }
                focus == PlayerFocus.PILL && state.segment != SegmentUi.None -> { vm.dismissSegment(); focus = PlayerFocus.SEEK_BAR }
                overlay && playing -> { overlay = false; focus = PlayerFocus.SEEK_BAR }
                else -> onExit()
            }
            return true
        }
        if (state.error != null) {
            if (code in OK_KEYS) vm.retry()
            return handled
        }
        if (!handled) return handled

        if (state.stillWatching != null) return true.also { onPromptKey(code) }
        if (panel != null) return true.also { onEpisodesKey(code, panel) }
        if (post != null) return true.also { onPostPlayKey(code, post) }

        if (menuOpen) {
            val rows = if (menuColumn == 0) state.audioTracks.size else subtitleOptions.size
            when (code) {
                AndroidKey.KEYCODE_DPAD_UP -> menuRow = (menuRow - 1).coerceAtLeast(0)
                AndroidKey.KEYCODE_DPAD_DOWN -> menuRow = (menuRow + 1).coerceAtMost((rows - 1).coerceAtLeast(0))
                AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT -> {
                    menuColumn = if (code == AndroidKey.KEYCODE_DPAD_LEFT) 0 else 1
                    menuRow = if (menuColumn == 0) {
                        state.audioTracks.indexOfFirst { it.index == state.selectedAudio }.coerceAtLeast(0)
                    } else {
                        subtitleOptions.indexOfFirst { it?.index == state.selectedSubtitle }.coerceAtLeast(0)
                    }
                }
                else -> {
                    if (menuColumn == 0) state.audioTracks.getOrNull(menuRow)?.let { vm.selectAudio(it.index) }
                    else if (menuRow in subtitleOptions.indices) vm.selectSubtitle(subtitleOptions[menuRow]?.index)
                    menuOpen = false
                }
            }
            return true
        }

        val buttons = buildList {
            if (state.isEpisode) add(PlayerFocus.EPISODES)
            add(PlayerFocus.AUDIO_SUBS)
            if (state.nextTitle != null) add(PlayerFocus.NEXT_EPISODE)
        }
        val onButtons = overlay && focus in buttons
        when (code) {
            AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT -> {
                val forward = code == AndroidKey.KEYCODE_DPAD_RIGHT
                if (onButtons) {
                    val i = (buttons.indexOf(focus) + if (forward) 1 else -1).coerceIn(0, buttons.lastIndex)
                    focus = buttons[i]
                    showOverlay()
                } else {
                    scrub(forward, native.repeatCount)
                }
            }
            AndroidKey.KEYCODE_DPAD_UP -> {
                focus = PlayerFocus.SEEK_BAR
                showOverlay()
            }
            AndroidKey.KEYCODE_DPAD_DOWN -> {
                if (overlay && focus == PlayerFocus.SEEK_BAR && scrubTarget == null) focus = buttons.first()
                else if (!overlay || focus == PlayerFocus.PILL) focus = PlayerFocus.SEEK_BAR
                showOverlay()
            }
            else -> when { // OK / Enter
                focus == PlayerFocus.PILL && state.segment is SegmentUi.SkipButton -> vm.skipSegment()
                focus == PlayerFocus.PILL && state.segment is SegmentUi.NextUp -> vm.playNext()
                scrubTarget != null -> commitScrub()
                onButtons && focus == PlayerFocus.AUDIO_SUBS -> {
                    menuOpen = true
                    menuColumn = 0
                    menuRow = state.audioTracks.indexOfFirst { it.index == state.selectedAudio }.coerceAtLeast(0)
                }
                onButtons && focus == PlayerFocus.NEXT_EPISODE -> vm.playNext()
                onButtons && focus == PlayerFocus.EPISODES -> {
                    vm.openEpisodes()
                    focus = PlayerFocus.SEEK_BAR
                }
                else -> {
                    vm.controller.togglePlayPause()
                    showOverlay()
                }
            }
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent(::onKey),
    ) {
        VideoSurface(vm.controller, aspect, firstFrame)
        val panel = episodes
        val post = state.postPlay
        val prompt = state.stillWatching
        val overlayVisible = (overlay || !playing) && !state.loading && state.error == null && panel == null && post == null && prompt == null
        // Subtitles would draw through the panels, so they pause while one is up.
        if (panel == null && post == null && prompt == null) {
            SubtitleLayer(cues, aspect, prefs.subtitleScale, prefs.subtitleBackground, liftForOverlay = overlayVisible)
        }

        if (overlayVisible) {
            PlayerOverlay(
                state = state,
                positionMs = scrubTarget ?: position,
                durationMs = duration,
                playing = playing,
                scrubbing = scrubTarget != null,
                focus = focus,
                trickplay = { pos ->
                    state.trickplay?.let { TrickplayThumb(it, pos, vm.api, vm.authorizationHeader, sheets) }
                },
            )
        }
        if (panel == null && post == null && prompt == null) SegmentPills(state, focused = focus == PlayerFocus.PILL, overlayVisible = overlayVisible)
        panel?.let { EpisodesPanelView(it, episodesRow, episodeFocus) }
        post?.let { PostPlayView(it, postFocus) }
        prompt?.let { StillWatchingView(it, promptFocus) }
        if (state.loading || (buffering && state.error == null && post?.ended != true && prompt == null)) LoadingBadge(state.loading)
        state.notice?.let { Notice(it) }
        state.error?.let { ErrorPanel(it) }
        if (menuOpen) TrackMenu(state, subtitleOptions, menuColumn, menuRow)
    }
}

private val OK_KEYS = setOf(AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER)
private const val SCRUB_COMMIT_MS = 800L
private const val OVERLAY_HIDE_MS = 5_000L

private val HANDLED_KEYS = setOf(
    AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_DPAD_RIGHT, AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN,
    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_NUMPAD_ENTER, AndroidKey.KEYCODE_BACK,
)

/**
 * The video: a SurfaceView (needed for HDR/Dolby Vision) sized to the picture's aspect ratio, black
 * until the first frame is drawn. The frame-rate "Always" mode sets the window's preferred display
 * mode while the player is open.
 */
@Composable
private fun VideoSurface(controller: PlaybackController, aspect: Float, firstFrame: Boolean) {
    val window = (LocalContext.current as? android.app.Activity)?.window
    val mode by controller.preferredDisplayMode.collectAsStateWithLifecycle()
    LaunchedEffect(window, mode) { window?.setPreferredDisplayMode(mode) }
    // Leaving the player gives the display back to the system (the home screen's own mode).
    DisposableEffect(window) { onDispose { window?.setPreferredDisplayMode(0) } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { view -> controller.attachSurface(view.holder) }
            },
            onRelease = { view -> controller.detachSurface(view.holder) },
            modifier = Modifier.aspectRatio(aspect.coerceIn(0.5f, 4f)),
        )
        if (!firstFrame) Box(Modifier.fillMaxSize().background(Color.Black))
    }
}

private fun android.view.Window.setPreferredDisplayMode(id: Int) {
    if (attributes.preferredDisplayModeId == id) return
    attributes = attributes.also { it.preferredDisplayModeId = id }
}
