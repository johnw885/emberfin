package com.johnw.emberfin.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException as Media3Error
import com.johnw.emberfin.EmberfinApp
import com.johnw.emberfin.data.isUnreachable
import com.johnw.emberfin.playback.AndroidDeviceCapabilities
import com.johnw.emberfin.playback.AudioTrackPolicy
import com.johnw.emberfin.playback.DeviceProfileBuilder
import com.johnw.emberfin.playback.FallbackPolicy
import com.johnw.emberfin.playback.PlaybackController
import com.johnw.emberfin.playback.PlaybackNegotiator
import com.johnw.emberfin.playback.PlaybackRequest
import com.johnw.emberfin.playback.PlaybackSettings
import com.johnw.emberfin.playback.PlaybackTier
import com.johnw.emberfin.playback.Segment
import com.johnw.emberfin.playback.SegmentCommand
import com.johnw.emberfin.playback.SegmentController
import com.johnw.emberfin.playback.SegmentUi
import com.johnw.emberfin.playback.StillWatchingPolicy
import com.johnw.emberfin.playback.StreamPlan
import com.johnw.emberfin.playback.TrackMemory
import com.johnw.emberfin.playback.Trickplay
import com.johnw.emberfin.ui.Art
import com.johnw.emberfin.ui.ItemFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.GeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayCommand
import org.jellyfin.sdk.model.api.PlayRequest
import org.jellyfin.sdk.model.api.PlaystateCommand
import org.jellyfin.sdk.model.api.PlaystateRequest
import org.jellyfin.sdk.model.serializer.toUUID

/**
 * How to open the player. Session 3's details page passes the item id and either [RESUME]
 * (Play/Resume: continue from the server's resume point) or 0 (Restart). A remote client's
 * "play on" may add a [queue] (containing [itemId]) and the audio/subtitle indexes it wants.
 */
data class PlayerArgs(
    val itemId: String,
    val startPositionMs: Long = RESUME,
    val queue: List<String> = emptyList(),
    val audioIndex: Int? = null,
    val subtitleIndex: Int? = null,
) {
    companion object {
        const val RESUME = -1L
    }
}

data class TrackOption(val index: Int, val label: String)

/** One episode in the player's Episodes panel. */
data class EpisodeCard(
    val id: String,
    val number: String,
    val title: String,
    val overview: String?,
    val runtime: String?,
    val progress: Float?,
    val played: Boolean,
    val art: Art?,
    val current: Boolean,
)

data class SeasonTab(val id: String, val name: String)

/** The Episodes panel: the series' seasons and the chosen season's episodes. */
data class EpisodesPanel(
    val seasons: List<SeasonTab> = emptyList(),
    val season: Int = 0,
    val episodes: List<EpisodeCard> = emptyList(),
    val loading: Boolean = true,
)

data class PostPlayCard(val id: String, val title: String, val meta: String?, val art: Art?)

/** "More like this" over the credits ([ended] = false) or after the end. */
data class PostPlay(val title: String, val items: List<PostPlayCard>, val ended: Boolean)

data class PlayerUiState(
    val title: String = "",
    val subtitle: String? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** Short message such as "Switched to compatibility mode". */
    val notice: String? = null,
    val segment: SegmentUi = SegmentUi.None,
    val nextTitle: String? = null,
    val audioTracks: List<TrackOption> = emptyList(),
    val subtitleTracks: List<TrackOption> = emptyList(),
    val selectedAudio: Int? = null,
    val selectedSubtitle: Int? = null,
    val trickplay: Trickplay? = null,
    val methodLabel: String = "",
    /** The player should close (movie ended, or nothing left to play). */
    val finished: Boolean = false,
    val isEpisode: Boolean = false,
    val postPlay: PostPlay? = null,
    /** "Are you still watching <this>?" is up (playback paused). */
    val stillWatching: String? = null,
    /** Bumped when a remote client changed playback, so the screen shows the overlay. */
    val remotePing: Int = 0,
)

/** Owns one [PlaybackController] for the life of the player screen and plays items through it. */
class PlayerViewModel(private val app: EmberfinApp, private val args: PlayerArgs) : ViewModel(), PlaybackController.Listener {
    private val session = app.sessions.require()
    val api: ApiClient = session.api
    val authorizationHeader: String = app.clients.authorizationHeader(session.account.accessToken)
    private val userId: UUID = session.userId
    private val appSettings = app.settings.snapshot()
    val settings: PlaybackSettings = appSettings.playback()
    private val audioPolicy = AudioTrackPolicy(settings.preferAtmos)
    private val negotiator = PlaybackNegotiator(
        profile = { DeviceProfileBuilder(capabilities(app), audioPolicy).build(settings.maxStreamingBitrate) },
        audioPolicy = audioPolicy,
    )

    val controller = PlaybackController(app, app.appScope, api, authorizationHeader, settings, this)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _episodes = MutableStateFlow<EpisodesPanel?>(null)
    /** Non-null while the Episodes panel is open. */
    val episodes: StateFlow<EpisodesPanel?> = _episodes.asStateFlow()

    private var item: BaseItemDto? = null
    private var next: BaseItemDto? = null
    private var segments: SegmentController? = null
    private var segmentList: List<Segment> = emptyList()
    private var loadJob: Job? = null
    private var fallbackInProgress = false

    /** What plays, in order; [queueIndex] is the current one. Next episodes are added as they play, so Previous works. */
    private val queue: MutableList<UUID> = args.queue.ifEmpty { listOf(args.itemId) }.map { it.toUUID() }.toMutableList()
    private var queueIndex = queue.indexOf(args.itemId.toUUID()).coerceAtLeast(0)

    private val stillWatching = StillWatchingPolicy(
        enabled = appSettings.stillWatching,
        maxEpisodes = debugStillWatchingEpisodes ?: StillWatchingPolicy.DEFAULT_EPISODES,
        maxIdleMs = debugStillWatchingIdleMs ?: StillWatchingPolicy.DEFAULT_IDLE_MS,
    )

    /** Titles for the post-play panel, loaded when nothing comes next. */
    private var similar: List<BaseItemDto>? = null
    private val seasonEpisodes = mutableMapOf<String, List<BaseItemDto>>()

    /** Set by the screen while the user scrubs, so skip buttons don't pop up over the seek bar. */
    @Volatile var userSeeking = false

    init {
        play(queue[queueIndex], args.startPositionMs, args.audioIndex, args.subtitleIndex)
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
        viewModelScope.launch { app.remote.playstate.collect(::onRemotePlaystate) }
        viewModelScope.launch { app.remote.commands.collect(::onRemoteCommand) }
        viewModelScope.launch { app.remote.enqueue.collect(::onRemoteEnqueue) }
    }

    private fun play(itemId: UUID, startMs: Long, audioIndex: Int? = null, subtitleIndex: Int? = null) {
        loadJob?.cancel()
        segments = null
        similar = null
        _episodes.value = null
        if (controller.plan != null) controller.player.playWhenReady = false
        _state.update { PlayerUiState(loading = true, remotePing = it.remotePing) }
        loadJob = viewModelScope.launch {
            try {
                val item = app.library.item(api, userId, itemId)
                this@PlayerViewModel.item = item
                val start = if (startMs == PlayerArgs.RESUME) resumePosition(item) else startMs
                // A series remembers the audio/subtitle choice made in any of its episodes.
                val remembered = item.seriesId?.takeIf { item.type == BaseItemKind.EPISODE }?.let { seriesId ->
                    runCatching { app.seriesTracks.get(session.account.key, seriesId.toString()) }.getOrNull()
                }
                val plan = negotiator.plan(
                    api,
                    PlaybackRequest(
                        itemId = itemId,
                        userId = userId,
                        maxBitrate = settings.maxStreamingBitrate,
                        startPositionMs = start,
                        audioStreamIndex = audioIndex?.takeIf { allowedAudio(item, it) },
                        subtitleStreamIndex = subtitleIndex,
                        trackPreference = remembered,
                    ),
                )
                val (title, subtitle) = titles(item)
                controller.load(plan, start, title, subtitle)
                _state.update {
                    it.copy(
                        title = title,
                        subtitle = subtitle,
                        loading = false,
                        trickplay = Trickplay.from(item, plan.mediaSourceId),
                        isEpisode = item.type == BaseItemKind.EPISODE,
                    ).withTracks(plan)
                }
                next = findNext(item)
                // The SDK reads response bodies on the calling thread: never call it from Main.
                segmentList = runCatching {
                    withContext(Dispatchers.IO) { api.mediaSegmentApi.getItemSegments(itemId).content.items.map(Segment::from) }
                }.onFailure { Log.w(TAG, "No media segments", it) }.getOrDefault(emptyList())
                rebuildSegments(item, plan)
                _state.update { it.copy(nextTitle = nextTitle()) }
                if (next == null) loadSimilar(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Can't play $itemId", e)
                _state.update { it.copy(loading = false, error = errorMessage(e)) }
            }
        }
    }

    /** An audio index a remote client asked for, unless it's TrueHD/DTS (then the usual choice applies). */
    private fun allowedAudio(item: BaseItemDto, index: Int): Boolean {
        val stream = item.mediaSources?.firstOrNull()?.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == index }
        return stream != null && !AudioTrackPolicy.isBanned(stream.codec)
    }

    /** The next thing to play: the queue's next item, else (for an episode) the series' next episode. */
    private suspend fun findNext(item: BaseItemDto): BaseItemDto? {
        queue.getOrNull(queueIndex + 1)?.let { id -> return runCatching { app.library.item(api, userId, id) }.getOrNull() }
        return runCatching { app.library.nextEpisode(api, userId, item) }.getOrNull()
    }

    private fun nextTitle(): String? = next?.let { n -> titles(n).let { (t, s) -> s ?: t } }

    private fun rebuildSegments(item: BaseItemDto, plan: StreamPlan?) {
        val durationMs = (item.runTimeTicks ?: plan?.runTimeTicks ?: 0L) / 10_000
        segments = SegmentController(segmentList, durationMs, hasNext = next != null, settings)
    }

    /** "More like this" for the post-play panel: titles similar to this one (to the series, for a last episode). */
    private fun loadSimilar(item: BaseItemDto) {
        viewModelScope.launch {
            val source = item.seriesId?.takeIf { item.type == BaseItemKind.EPISODE } ?: item.id
            similar = runCatching { app.library.similar(session, source, limit = 12) }.getOrDefault(emptyList())
        }
    }

    private fun PlayerUiState.withTracks(plan: StreamPlan): PlayerUiState = copy(
        audioTracks = audioPolicy.menuTracks(plan.streams).map { TrackOption(it.index, it.displayTitle ?: it.codec ?: "Track ${it.index}") },
        subtitleTracks = plan.streams.filter { it.type == MediaStreamType.SUBTITLE }.sortedBy { it.index }
            .map { TrackOption(it.index, it.displayTitle ?: it.language ?: "Subtitle ${it.index}") },
        selectedAudio = controller.audioIndex,
        selectedSubtitle = controller.subtitleIndex,
        methodLabel = when (plan.tier) {
            PlaybackTier.DIRECT_PLAY -> "Direct play"
            PlaybackTier.DIRECT_STREAM -> "Direct stream"
            PlaybackTier.TRANSCODE -> "Transcode"
        },
    )

    private fun tick() {
        val seg = segments ?: return
        val player = controller.player
        if (_state.value.loading || player.playbackState == androidx.media3.common.Player.STATE_IDLE) return
        if (_state.value.stillWatching != null) return
        val command = seg.update(player.currentPosition, userSeeking)
        if (_state.value.segment != seg.ui) _state.update { it.copy(segment = seg.ui) }
        // The credits of a title with nothing after it: "More like this".
        val post = _state.value.postPlay
        if (seg.ui == SegmentUi.Credits && post == null) showPostPlay(ended = false)
        if (seg.ui != SegmentUi.Credits && post != null && !post.ended) _state.update { it.copy(postPlay = null) }
        command?.let { run(it, auto = true) }
    }

    private fun run(command: SegmentCommand, auto: Boolean) {
        when (command) {
            is SegmentCommand.SeekTo -> controller.seekTo(command.positionMs)
            SegmentCommand.PlayNext -> if (auto) autoplayNext() else advance()
        }
    }

    /** The countdown ran out or the episode ended: play the next one, unless it's time to ask "still watching?". */
    private fun autoplayNext() {
        val upcoming = next ?: return
        if (stillWatching.shouldAskBeforeAutoplay()) {
            controller.setPlaying(false)
            val name = upcoming.seriesName ?: titles(upcoming).first
            _state.update { it.copy(stillWatching = name, segment = SegmentUi.None) }
            return
        }
        advance()
    }

    /** Plays [next], keeping the queue in order (so Previous goes back). */
    private fun advance() {
        val upcoming = next ?: return
        if (queue.getOrNull(queueIndex + 1) != upcoming.id) queue.add(queueIndex + 1, upcoming.id)
        queueIndex++
        play(upcoming.id, PlayerArgs.RESUME)
    }

    /** Previous: the queue's previous item near the start, otherwise back to the start of this one. */
    private fun previous() {
        if (queueIndex > 0 && controller.player.currentPosition < PREVIOUS_WINDOW_MS) {
            queueIndex--
            play(queue[queueIndex], 0)
        } else {
            controller.seekTo(0)
        }
    }

    /** Any key from the remote (commands from other clients count too). */
    fun onUserInput() = stillWatching.onInput()

    /** "Continue watching" on the still-watching prompt. */
    fun continueWatching() {
        stillWatching.onInput()
        _state.update { it.copy(stillWatching = null) }
        advance()
    }

    fun skipSegment() {
        segments?.onSkipPressed()?.let { run(it, auto = false) }
        _state.update { it.copy(segment = segments?.ui ?: SegmentUi.None) }
    }

    fun playNext() {
        val seg = segments
        if (seg != null) seg.onPlayNextPressed()?.let { run(it, auto = false) } else advance()
    }

    /** Back while a skip button, the countdown or the credits panel shows. Returns true if it hid something. */
    fun dismissSegment(): Boolean {
        val handled = segments?.onBack() == true
        if (handled) _state.update { it.copy(segment = SegmentUi.None, postPlay = null) }
        return handled
    }

    private fun showPostPlay(ended: Boolean) {
        val items = similar.orEmpty()
        if (items.isEmpty()) {
            if (ended) _state.update { it.copy(finished = true) }
            return
        }
        val cards = items.map { i ->
            PostPlayCard(
                id = i.id.toString(),
                title = i.name.orEmpty(),
                meta = listOfNotNull(i.productionYear?.toString(), ItemFormat.runtime(i.runTimeTicks)).joinToString("  ·  ").ifEmpty { null },
                art = ItemFormat.landscape(api, i),
            )
        }
        _state.update { it.copy(postPlay = PostPlay(title = it.title, items = cards, ended = ended)) }
    }

    fun selectAudio(index: Int) {
        if (_state.value.audioTracks.none { it.index == index }) return // Never TrueHD/DTS, whoever asks.
        if (!controller.selectAudio(index)) renegotiate(audio = index)
        _state.update { it.copy(selectedAudio = index) }
        rememberTracks(audio = index)
    }

    fun selectSubtitle(index: Int?) {
        if (index != null && _state.value.subtitleTracks.none { it.index == index }) return
        if (!controller.selectSubtitle(index)) renegotiate(subtitle = index ?: -1)
        _state.update { it.copy(selectedSubtitle = index) }
        rememberTracks(subtitle = index ?: -1)
    }

    /** Saves the user's track choice for the series, so its other episodes start the same way. */
    private fun rememberTracks(audio: Int? = null, subtitle: Int? = null) {
        val item = item?.takeIf { it.type == BaseItemKind.EPISODE } ?: return
        val seriesId = item.seriesId?.toString() ?: return
        val streams = controller.plan?.streams ?: return
        val key = session.account.key
        app.appScope.launch {
            runCatching {
                audio?.let { TrackMemory.audioFrom(streams, it) }?.let { app.seriesTracks.saveAudio(key, seriesId, it) }
                subtitle?.let { TrackMemory.subtitleFrom(streams, it.takeIf { i -> i >= 0 }) }?.let { app.seriesTracks.saveSubtitle(key, seriesId, it) }
            }.onFailure { Log.w(TAG, "Couldn't save the series' track choice", it) }
        }
    }

    // --- Episodes panel ---

    fun openEpisodes() {
        val item = item?.takeIf { it.type == BaseItemKind.EPISODE } ?: return
        val seriesId = item.seriesId ?: return
        _episodes.value = EpisodesPanel(loading = true)
        viewModelScope.launch {
            val seasons = runCatching { app.library.seasons(session, seriesId) }.getOrDefault(emptyList())
            val tabs = seasons.map { SeasonTab(it.id.toString(), it.name ?: "Season ${it.indexNumber ?: ""}".trim()) }
            val current = tabs.indexOfFirst { it.id == (item.seasonId ?: item.parentId)?.toString() }.coerceAtLeast(0)
            if (_episodes.value == null) return@launch
            _episodes.value = EpisodesPanel(seasons = tabs, season = current, loading = true)
            loadSeason(current)
        }
    }

    fun closeEpisodes() {
        _episodes.value = null
    }

    fun showSeason(index: Int) {
        val panel = _episodes.value ?: return
        if (index !in panel.seasons.indices || index == panel.season) return
        _episodes.value = panel.copy(season = index, episodes = emptyList(), loading = true)
        loadSeason(index)
    }

    private fun loadSeason(index: Int) {
        val item = item ?: return
        val seriesId = item.seriesId ?: return
        val season = _episodes.value?.seasons?.getOrNull(index) ?: run {
            _episodes.update { it?.copy(loading = false) }
            return
        }
        viewModelScope.launch {
            val list = seasonEpisodes[season.id] ?: runCatching { app.library.episodes(session, seriesId, season.id.toUUID()) }
                .getOrDefault(emptyList()).also { seasonEpisodes[season.id] = it }
            val panel = _episodes.value ?: return@launch
            if (panel.season != index) return@launch
            _episodes.value = panel.copy(episodes = list.map { it.toCard(item.id) }, loading = false)
        }
    }

    private fun BaseItemDto.toCard(currentId: UUID) = EpisodeCard(
        id = id.toString(),
        number = indexNumber?.toString() ?: "",
        title = name.orEmpty(),
        overview = overview,
        runtime = ItemFormat.runtime(runTimeTicks),
        progress = ItemFormat.progress(this),
        played = ItemFormat.played(this),
        art = ItemFormat.landscape(api, this),
        current = id == currentId,
    )

    /** OK on an episode in the panel: play it (from its resume point) as a fresh queue. */
    fun playEpisode(id: String) {
        _episodes.value = null
        val target = id.toUUID()
        if (target == item?.id) return
        seasonEpisodes.clear() // Watched state changes as episodes play.
        queue.clear()
        queue.add(target)
        queueIndex = 0
        play(target, PlayerArgs.RESUME)
    }

    // --- Remote control: another Jellyfin client (a phone) driving this player ---

    private fun onRemotePlaystate(request: PlaystateRequest) {
        stillWatching.onInput()
        val player = controller.player
        when (request.command) {
            PlaystateCommand.STOP -> _state.update { it.copy(finished = true) }
            PlaystateCommand.PAUSE -> controller.setPlaying(false)
            PlaystateCommand.UNPAUSE -> resumeFromRemote()
            PlaystateCommand.PLAY_PAUSE -> if (player.playWhenReady) controller.setPlaying(false) else resumeFromRemote()
            PlaystateCommand.SEEK -> request.seekPositionTicks?.let { controller.seekTo(it / 10_000) }
            PlaystateCommand.REWIND -> controller.seekTo(player.currentPosition - REMOTE_SKIP_MS)
            PlaystateCommand.FAST_FORWARD -> controller.seekTo(player.currentPosition + REMOTE_SKIP_MS)
            PlaystateCommand.NEXT_TRACK -> if (_state.value.stillWatching != null) continueWatching() else advance()
            PlaystateCommand.PREVIOUS_TRACK -> previous()
        }
        _state.update { it.copy(remotePing = it.remotePing + 1) }
    }

    /** Unpause from a phone also answers a waiting "still watching?" prompt. */
    private fun resumeFromRemote() {
        if (_state.value.stillWatching != null) continueWatching() else controller.setPlaying(true)
    }

    private fun onRemoteCommand(command: GeneralCommand) {
        stillWatching.onInput()
        val index = command.arguments.get("Index")?.toIntOrNull() ?: return
        when (command.name) {
            GeneralCommandType.SET_AUDIO_STREAM_INDEX -> selectAudio(index)
            GeneralCommandType.SET_SUBTITLE_STREAM_INDEX -> selectSubtitle(index.takeIf { it >= 0 })
            else -> return
        }
        _state.update { it.copy(remotePing = it.remotePing + 1) }
    }

    /** "Play next" / "Play last" from another client while something plays. */
    private fun onRemoteEnqueue(request: PlayRequest) {
        val ids = request.itemIds.orEmpty().filterNot { it in queue }
        if (ids.isEmpty()) return
        if (request.playCommand == PlayCommand.PLAY_NEXT) queue.addAll(queueIndex + 1, ids) else queue.addAll(ids)
        val item = item ?: return
        viewModelScope.launch {
            next = findNext(item)
            rebuildSegments(item, controller.plan)
            _state.update { it.copy(nextTitle = nextTitle()) }
        }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** OK on the error panel: try the same item again from where it stopped. */
    fun retry() {
        val itemId = controller.plan?.itemId ?: item?.id ?: queue[queueIndex]
        val position = if (controller.plan != null) controller.player.currentPosition else args.startPositionMs
        fallbackInProgress = false
        play(itemId, position)
    }

    private fun errorMessage(e: Exception): String = when {
        e.isUnreachable() -> "Can't reach the server. Check that it's running, then try again."
        e is org.jellyfin.sdk.api.client.exception.InvalidStatusException && e.status == 401 -> "Your sign-in has expired."
        e is org.jellyfin.sdk.api.client.exception.InvalidStatusException && e.status == 404 -> "This title is no longer on the server."
        else -> "Can't play this right now (${e.message ?: e.javaClass.simpleName})."
    }

    /** Asks the server for a new stream of the same item at the current position. */
    private fun renegotiate(
        tier: PlaybackTier? = null,
        audio: Int? = controller.audioIndex,
        subtitle: Int? = controller.subtitleIndex ?: -1,
        notice: String? = null,
    ) {
        val current = controller.plan ?: return
        val item = item ?: return
        val position = controller.player.currentPosition
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            try {
                val plan = negotiator.plan(
                    api,
                    PlaybackRequest(
                        itemId = current.itemId,
                        userId = userId,
                        maxBitrate = settings.maxStreamingBitrate,
                        tier = tier ?: current.tier,
                        mediaSourceId = current.mediaSourceId,
                        audioStreamIndex = audio,
                        subtitleStreamIndex = subtitle,
                        startPositionMs = position,
                    ),
                )
                val (title, sub) = titles(item)
                controller.load(plan, position, title, sub)
                _state.update { it.copy(notice = notice ?: it.notice, error = null).withTracks(plan) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = errorMessage(e)) }
            } finally {
                fallbackInProgress = false
            }
        }
    }

    override fun onFatalError(error: Media3Error, plan: StreamPlan, positionMs: Long) {
        Log.w(TAG, "Player error ${error.errorCodeName} at ${plan.tier}", error)
        val next = FallbackPolicy.onError(error.errorCode, plan.tier)
        if (next == null || fallbackInProgress) {
            val status = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            val text = when {
                status != null -> "The server couldn't stream this file (HTTP $status). The file may be damaged or incomplete."
                error.errorCode in NETWORK_ERRORS -> "Lost the connection to the server."
                else -> "This title won't play on this device, even in compatibility mode (${error.errorCodeName})."
            }
            _state.update { it.copy(error = text) }
            return
        }
        fallbackInProgress = true
        renegotiate(tier = next, notice = "Switched to compatibility mode")
    }

    override fun onEnded() {
        val command = segments?.onEnded()
        when {
            command != null -> run(command, auto = true)
            next == null -> showPostPlay(ended = true)
            else -> _state.update { it.copy(finished = true) }
        }
    }

    override fun onCleared() {
        controller.release()
    }

    private fun resumePosition(item: BaseItemDto): Long {
        val ticks = item.userData?.playbackPositionTicks ?: 0L
        val runtime = item.runTimeTicks ?: 0L
        // Nearly finished counts as watched: start over.
        if (runtime > 0 && ticks > runtime * 95 / 100) return 0
        return ticks / 10_000
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val TICK_MS = 250L
        private const val REMOTE_SKIP_MS = 10_000L
        /** Previous within this much of the start goes to the previous item; later, it restarts this one. */
        private const val PREVIOUS_WINDOW_MS = 10_000L
        private val NETWORK_ERRORS = setOf(
            Media3Error.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, Media3Error.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

        /** Debug builds: ask "still watching?" sooner (MainActivity's debug intent sets these). */
        @Volatile var debugStillWatchingEpisodes: Int? = null
        @Volatile var debugStillWatchingIdleMs: Long? = null

        @Volatile private var cachedCaps: com.johnw.emberfin.playback.DeviceCapabilities? = null

        fun capabilities(context: Context) = cachedCaps ?: AndroidDeviceCapabilities.read(context).also {
            Log.i(TAG, "Device capabilities: $it")
            cachedCaps = it
        }

        /** Title line and the episode line under it. */
        fun titles(item: BaseItemDto): Pair<String, String?> {
            if (item.type != BaseItemKind.EPISODE || item.seriesName == null) return (item.name ?: "") to null
            val s = item.parentIndexNumber
            val e = item.indexNumber
            val code = if (s != null && e != null) "S$s:E$e  " else ""
            return item.seriesName!! to "$code${item.name ?: ""}"
        }
    }
}
