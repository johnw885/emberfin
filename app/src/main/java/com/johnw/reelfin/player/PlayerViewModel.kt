package com.johnw.reelfin.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException as Media3Error
import com.johnw.reelfin.ReelfinApp
import com.johnw.reelfin.playback.AndroidDeviceCapabilities
import com.johnw.reelfin.playback.AudioTrackPolicy
import com.johnw.reelfin.playback.DeviceProfileBuilder
import com.johnw.reelfin.playback.FallbackPolicy
import com.johnw.reelfin.playback.PlaybackController
import com.johnw.reelfin.playback.PlaybackNegotiator
import com.johnw.reelfin.playback.PlaybackRequest
import com.johnw.reelfin.playback.PlaybackSettings
import com.johnw.reelfin.playback.PlaybackTier
import com.johnw.reelfin.playback.Segment
import com.johnw.reelfin.playback.SegmentCommand
import com.johnw.reelfin.playback.SegmentController
import com.johnw.reelfin.playback.SegmentUi
import com.johnw.reelfin.playback.StreamPlan
import com.johnw.reelfin.playback.Trickplay
import com.johnw.reelfin.data.isUnreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.serializer.toUUID

/**
 * How to open the player. Session 3's details page passes the item id and either [RESUME]
 * (Play/Resume: continue from the server's resume point) or 0 (Restart).
 */
data class PlayerArgs(val itemId: String, val startPositionMs: Long = RESUME) {
    companion object {
        const val RESUME = -1L
    }
}

data class TrackOption(val index: Int, val label: String)

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
)

/** Owns one [PlaybackController] for the life of the player screen and plays items through it. */
class PlayerViewModel(private val app: ReelfinApp, private val args: PlayerArgs) : ViewModel(), PlaybackController.Listener {
    private val session = app.sessions.require()
    val api: ApiClient = session.api
    val authorizationHeader: String = app.clients.authorizationHeader(session.account.accessToken)
    private val userId: UUID = session.userId
    val settings: PlaybackSettings = app.settings.snapshot().playback()
    private val audioPolicy = AudioTrackPolicy(settings.preferAtmos)
    private val negotiator = PlaybackNegotiator(
        profile = { DeviceProfileBuilder(capabilities(app), audioPolicy).build(settings.maxStreamingBitrate) },
        audioPolicy = audioPolicy,
    )

    val controller = PlaybackController(app, app.appScope, api, authorizationHeader, settings, this)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var item: BaseItemDto? = null
    private var next: BaseItemDto? = null
    private var segments: SegmentController? = null
    private var loadJob: Job? = null
    private var fallbackInProgress = false

    /** Set by the screen while the user scrubs, so skip buttons don't pop up over the seek bar. */
    @Volatile var userSeeking = false

    init {
        play(args.itemId.toUUID(), args.startPositionMs)
        viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    private fun play(itemId: UUID, startMs: Long) {
        loadJob?.cancel()
        segments = null
        if (controller.plan != null) controller.player.playWhenReady = false
        _state.update { PlayerUiState(loading = true) }
        loadJob = viewModelScope.launch {
            try {
                val item = app.library.item(api, userId, itemId)
                this@PlayerViewModel.item = item
                val start = if (startMs == PlayerArgs.RESUME) resumePosition(item) else startMs
                val plan = negotiator.plan(api, PlaybackRequest(itemId, userId, settings.maxStreamingBitrate, startPositionMs = start))
                val (title, subtitle) = titles(item)
                controller.load(plan, start, title, subtitle)
                _state.update {
                    it.copy(
                        title = title,
                        subtitle = subtitle,
                        loading = false,
                        trickplay = Trickplay.from(item, plan.mediaSourceId),
                    ).withTracks(plan)
                }
                next = runCatching { app.library.nextEpisode(api, userId, item) }.getOrNull()
                // The SDK reads response bodies on the calling thread: never call it from Main.
                val segmentList = runCatching {
                    withContext(Dispatchers.IO) { api.mediaSegmentApi.getItemSegments(itemId).content.items.map(Segment::from) }
                }.onFailure { Log.w(TAG, "No media segments", it) }.getOrDefault(emptyList())
                val durationMs = (item.runTimeTicks ?: plan.runTimeTicks ?: 0L) / 10_000
                segments = SegmentController(segmentList, durationMs, hasNext = next != null, settings)
                _state.update { it.copy(nextTitle = next?.let { n -> titles(n).let { (t, s) -> s ?: t } }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Can't play $itemId", e)
                _state.update { it.copy(loading = false, error = errorMessage(e)) }
            }
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
        val command = seg.update(player.currentPosition, userSeeking)
        if (_state.value.segment != seg.ui) _state.update { it.copy(segment = seg.ui) }
        command?.let(::run)
    }

    private fun run(command: SegmentCommand) {
        when (command) {
            is SegmentCommand.SeekTo -> controller.seekTo(command.positionMs)
            SegmentCommand.PlayNext -> next?.let { play(it.id, PlayerArgs.RESUME) }
        }
    }

    fun skipSegment() {
        segments?.onSkipPressed()?.let(::run)
        _state.update { it.copy(segment = segments?.ui ?: SegmentUi.None) }
    }

    fun playNext() {
        val seg = segments
        if (seg != null) seg.onPlayNextPressed()?.let(::run) else next?.let { play(it.id, PlayerArgs.RESUME) }
    }

    /** Back while a skip button or countdown shows. Returns true if it hid something. */
    fun dismissSegment(): Boolean {
        val handled = segments?.onBack() == true
        if (handled) _state.update { it.copy(segment = SegmentUi.None) }
        return handled
    }

    fun selectAudio(index: Int) {
        if (!controller.selectAudio(index)) renegotiate(audio = index)
        _state.update { it.copy(selectedAudio = index) }
    }

    fun selectSubtitle(index: Int?) {
        if (!controller.selectSubtitle(index)) renegotiate(subtitle = index ?: -1)
        _state.update { it.copy(selectedSubtitle = index) }
    }

    fun clearNotice() = _state.update { it.copy(notice = null) }

    /** OK on the error panel: try the same item again from where it stopped. */
    fun retry() {
        val itemId = controller.plan?.itemId ?: item?.id ?: args.itemId.toUUID()
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
        if (command != null) run(command) else _state.update { it.copy(finished = true) }
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
        private val NETWORK_ERRORS = setOf(
            Media3Error.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, Media3Error.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

        @Volatile private var cachedCaps: com.johnw.reelfin.playback.DeviceCapabilities? = null

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
