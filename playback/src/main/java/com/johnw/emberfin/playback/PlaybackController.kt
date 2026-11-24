package com.johnw.emberfin.playback

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRangeType

/**
 * One ExoPlayer + Media3 [MediaSession] for the life of the player screen. Each [load] plays a
 * [StreamPlan] (a new item, or the same item after a fallback or a track change that needed a
 * new stream) and reports it to the server. The MediaSession is what makes the remote's
 * play/pause keys and HDMI-CEC work.
 */
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    /** Outlives the screen, so the final "stopped" report still goes out. */
    private val reportScope: CoroutineScope,
    private val api: ApiClient,
    authorizationHeader: String,
    settings: PlaybackSettings,
    private val listener: Listener,
) {
    interface Listener {
        fun onFatalError(error: PlaybackException, plan: StreamPlan, positionMs: Long)
        fun onEnded()
    }

    private val main = CoroutineScope(Dispatchers.Main)
    private val dataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("Emberfin")
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(mapOf("Authorization" to authorizationHeader))

    /** True while the current stream is Dolby Vision profile 7: decode its HDR10 base layer only. */
    @Volatile private var dvBaseLayerOnly = false

    private val codecSelector = MediaCodecSelector { mimeType, secure, tunneling ->
        val mime = if (dvBaseLayerOnly && mimeType == MimeTypes.VIDEO_DOLBY_VISION) MimeTypes.VIDEO_H265 else mimeType
        MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
    }

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true).setMediaCodecSelector(codecSelector))
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private val mediaSession: MediaSession = MediaSession.Builder(context, player)
        .setId("emberfin-${sessionCounter++}")
        .build()

    private val _cues = MutableStateFlow<List<Cue>>(emptyList())
    val cues: StateFlow<List<Cue>> = _cues.asStateFlow()

    var plan: StreamPlan? = null
        private set
    var audioIndex: Int? = null
        private set
    var subtitleIndex: Int? = null
        private set

    private var reporter: ProgressReporter? = null
    private var started = false
    private var tracksApplied = false
    private var progressJob: Job? = null
    private var released = false

    private val frameRateMode = settings.frameRateMode
    private var videoFrameRate: Float? = null

    private val displays = context.getSystemService(DisplayManager::class.java)
    /** Set while playback waits for the TV to finish a refresh-rate switch (see [holdForDisplaySwitch]). */
    private var displayHold: Job? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private val _displayMode = MutableStateFlow(0)
    /**
     * ALWAYS mode: the display mode the player's window should ask for (0 = no preference). The
     * screen sets it as the window's `preferredDisplayModeId`, which switches straight away.
     * (`Surface.setFrameRate` only switched once frames were flowing, so the TV's re-sync blank
     * swallowed the first seconds of playback.)
     */
    val preferredDisplayMode: StateFlow<Int> = _displayMode.asStateFlow()

    init {
        // ALWAYS is done by us through the window (see applyFrameRate), so ExoPlayer's own switching is off.
        player.videoChangeFrameRateStrategy = when (frameRateMode) {
            FrameRateMode.SEAMLESS -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
            FrameRateMode.OFF, FrameRateMode.ALWAYS -> C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        }
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                val streamRate = plan?.streams?.firstOrNull { it.type == MediaStreamType.VIDEO }?.let { it.realFrameRate ?: it.averageFrameRate }
                videoFrameRate = FrameRatePolicy.target(format.frameRate, streamRate)
                Log.i(TAG, "Video ${format.width}x${format.height} @ ${format.frameRate} fps (stream $streamRate), mode $frameRateMode")
                applyFrameRate()
            }
        })
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            // A safety net before our explicit choice lands: prefer Dolby, never TrueHD/DTS.
            .setPreferredAudioMimeTypes(MimeTypes.AUDIO_E_AC3_JOC, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_AAC)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> if (!started) onStarted()
                    Player.STATE_ENDED -> listener.onEnded()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (!tracksApplied && !tracks.isEmpty) {
                    tracksApplied = true
                    applyAudio(audioIndex)
                    applySubtitle(subtitleIndex)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = reportProgressNow()

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) reportProgressNow()
            }

            override fun onCues(cueGroup: CueGroup) {
                _cues.value = cueGroup.cues
            }

            override fun onPlayerError(error: PlaybackException) {
                val current = plan ?: return
                listener.onFatalError(error, current, player.currentPosition)
            }
        })
    }

    /** The player screen's SurfaceView holder. */
    fun attachSurface(holder: SurfaceHolder) {
        player.setVideoSurfaceHolder(holder)
    }

    fun detachSurface(holder: SurfaceHolder) {
        player.clearVideoSurfaceHolder(holder)
    }

    private fun applyFrameRate() {
        if (frameRateMode != FrameRateMode.ALWAYS) return
        val rate = videoFrameRate ?: return
        val display = displays?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val current = display.mode.info()
        val target = FrameRatePolicy.pickMode(display.supportedModes.map { it.info() }, current, rate) ?: return
        if (target != current.id) holdForDisplaySwitch(target)
        _displayMode.value = target
        Log.i(TAG, "Display mode $target for $rate fps (always; display at ${current.refreshRate} Hz, mode ${current.id})")
    }

    private fun Display.Mode.info() = DisplayModeInfo(modeId, physicalWidth, physicalHeight, refreshRate)

    /**
     * A refresh-rate switch blanks the TV for a second or two (HDMI re-sync), and whatever plays
     * meanwhile is lost: the first lines of dialogue, and the receiver may miss the start of the
     * Dolby bitstream. So playback waits for the switch, then a moment for the TV, then starts.
     * Gives up waiting after [FrameRatePolicy.SWITCH_TIMEOUT_MS] (the TV may refuse the mode).
     */
    private fun holdForDisplaySwitch(targetMode: Int) {
        if (displayHold != null || !player.playWhenReady) return
        val dm = displays ?: return
        player.playWhenReady = false
        val started = System.currentTimeMillis()
        var changedAt: Long? = null
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || changedAt != null) return
                if (dm.getDisplay(displayId)?.mode?.modeId == targetMode) changedAt = System.currentTimeMillis()
            }
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
        }
        displayListener = l
        dm.registerDisplayListener(l, Handler(Looper.getMainLooper()))
        Log.i(TAG, "Holding playback for the display's refresh-rate switch")
        displayHold = main.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val changed = changedAt
                if (changed != null && now - changed >= FrameRatePolicy.SETTLE_MS) break
                if (changed == null && now - started >= FrameRatePolicy.SWITCH_TIMEOUT_MS) break
                delay(100)
            }
            val switched = changedAt?.let { "switched after ${it - started} ms" } ?: "didn't switch"
            Log.i(TAG, "Display $switched; playing after ${System.currentTimeMillis() - started} ms")
            endDisplayHold(resume = true)
        }
    }

    private fun endDisplayHold(resume: Boolean) {
        displayListener?.let { displays?.unregisterDisplayListener(it) }
        displayListener = null
        val hold = displayHold ?: return
        displayHold = null
        hold.cancel()
        if (resume && !released) player.playWhenReady = true
    }

    /** Plays [plan] from [startMs], ending the previous stream's reporting first. */
    fun load(plan: StreamPlan, startMs: Long, title: String, subtitle: String? = null, playWhenReady: Boolean = true) {
        endDisplayHold(resume = false)
        finishReporting(failed = false)
        this.plan = plan
        dvBaseLayerOnly = !plan.isTranscoding &&
            DolbyVisionPolicy.decodeBaseLayerOnly(plan.streams.firstOrNull { it.type == MediaStreamType.VIDEO }?.videoRangeType)
        audioIndex = plan.audioStreamIndex
        subtitleIndex = plan.subtitleStreamIndex?.takeIf { it >= 0 }
        reporter = ProgressReporter(api, plan)
        started = false
        tracksApplied = false
        _cues.value = emptyList()

        val subtitles = plan.externalSubtitles.map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setId(TrackMapper.sideLoadedId(s.index))
                .setMimeType(s.mimeType)
                .setLanguage(s.language)
                .setLabel(s.label)
                .build()
        }
        val item = MediaItem.Builder()
            .setUri(plan.url)
            .setMediaId(plan.itemId.toString())
            .setMimeType(if (plan.isTranscoding) MimeTypes.APPLICATION_M3U8 else null)
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).build())
            .build()
        // Text stays off until our own choice is applied, so ExoPlayer never auto-picks a forced track.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        player.setMediaItem(item, startMs)
        player.playWhenReady = playWhenReady
        player.prepare()
    }

    /** Switches audio inside the current stream. False = the stream doesn't carry it; renegotiate. */
    fun selectAudio(index: Int): Boolean {
        val current = plan ?: return false
        if (TrackMapper.audio(current, index) is TrackTarget.Renegotiate) return false
        audioIndex = index
        return applyAudio(index).also { if (it) reportProgressNow() }
    }

    /** Switches subtitles (null = off). False = needs a new stream (burn-in or HLS). */
    fun selectSubtitle(index: Int?): Boolean {
        val current = plan ?: return false
        if (TrackMapper.subtitle(current, index) is TrackTarget.Renegotiate) return false
        subtitleIndex = index
        return applySubtitle(index).also { if (it) reportProgressNow() }
    }

    private fun applyAudio(index: Int?): Boolean {
        val current = plan ?: return false
        if (index == null) return true
        val target = TrackMapper.audio(current, index) as? TrackTarget.Embedded ?: return false
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        val group = groups.getOrNull(target.ordinal) ?: return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        return true
    }

    private fun applySubtitle(index: Int?): Boolean {
        val current = plan ?: return false
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val group = when (val target = TrackMapper.subtitle(current, index)) {
            TrackTarget.Off -> null
            is TrackTarget.SideLoaded -> groups.firstOrNull { g -> g.getTrackFormat(0).id?.endsWith(target.id) == true }
            is TrackTarget.Embedded -> groups.filterNot { g -> g.getTrackFormat(0).id?.contains("jf-sub-") == true }.getOrNull(target.ordinal)
            TrackTarget.Renegotiate -> return false
        }
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (group == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = params.build()
        if (group == null) _cues.value = emptyList()
        return index == null || group != null
    }

    fun togglePlayPause() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        // A press during the refresh-rate wait means "play now".
        if (displayHold != null) return endDisplayHold(resume = true)
        player.playWhenReady = !player.playWhenReady
    }

    fun setPlaying(playing: Boolean) {
        if (displayHold != null) endDisplayHold(resume = playing)
        if (playing && player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.playWhenReady = playing
    }

    fun seekTo(positionMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo(positionMs.coerceIn(0, duration))
    }

    private fun onStarted() {
        started = true
        val r = reporter ?: return
        val pos = player.currentPosition
        val paused = !player.playWhenReady
        reportScope.launch { r.start(pos, paused) }
        progressJob?.cancel()
        progressJob = main.launch {
            while (isActive) {
                delay(ProgressReporter.PROGRESS_INTERVAL_MS)
                reportProgressNow()
            }
        }
    }

    private fun reportProgressNow() {
        val r = reporter ?: return
        if (!started || released) return
        val pos = player.currentPosition
        val paused = !player.isPlaying
        val audio = audioIndex
        val sub = subtitleIndex ?: -1
        reportScope.launch { r.progress(pos, paused, audio, sub) }
    }

    private fun finishReporting(failed: Boolean) {
        progressJob?.cancel()
        val r = reporter ?: return
        reporter = null
        if (!started) return
        val pos = player.currentPosition
        reportScope.launch { r.stop(pos, failed) }
    }

    /** Stops playback; the final report leaves the resume point on the server. */
    fun release() {
        if (released) return
        finishReporting(failed = player.playerError != null)
        released = true
        endDisplayHold(resume = false)
        _displayMode.value = 0
        mediaSession.release()
        player.release()
    }

    private companion object {
        const val TAG = "PlaybackController"
        var sessionCounter = 0
    }
}

/** Dolby Vision streams the player decodes as plain HEVC (their HDR10 base layer). Pure, so it's unit-tested. */
object DolbyVisionPolicy {
    /**
     * Profile 7 (dual layer). No TV decoder handles its enhancement layer properly, and the server
     * can only get rid of it with a full software transcode, so we direct-play and let the HEVC
     * decoder skip the enhancement-layer NAL units: the picture is the HDR10 base layer.
     */
    fun decodeBaseLayerOnly(range: VideoRangeType?): Boolean =
        range == VideoRangeType.DOVI_WITH_EL || range == VideoRangeType.DOVI_WITH_ELHDR10_PLUS
}

/** Which refresh rate to ask the display for. Pure, so it's unit-tested. */
object FrameRatePolicy {
    /**
     * The decoder's [formatRate] when the container states one, otherwise the server's [streamRate]
     * (Jellyfin's RealFrameRate). Null when neither is known or the value is implausible.
     */
    fun target(formatRate: Float, streamRate: Float?): Float? =
        formatRate.takeIf { it in 1f..240f } ?: streamRate?.takeIf { it in 1f..240f }

    /**
     * The display mode for a [videoRate] fps video: one at the current resolution whose refresh
     * rate is the video's or a whole multiple of it. Keeps the [current] mode when it already fits
     * (30 fps on 60 Hz: no blank); otherwise the highest fitting rate up to the current one
     * (59.94 Hz for 29.97 fps, 23.976 Hz for film). Null = no mode fits, leave the display alone.
     */
    fun pickMode(modes: List<DisplayModeInfo>, current: DisplayModeInfo, videoRate: Float): Int? {
        if (videoRate <= 0f) return null
        val fitting = modes.filter { it.width == current.width && it.height == current.height && fits(it.refreshRate, videoRate) }
        if (fitting.isEmpty()) return null
        if (fitting.any { it.id == current.id }) return current.id
        return (fitting.filter { it.refreshRate <= current.refreshRate + 0.01f }.maxByOrNull { it.refreshRate }
            ?: fitting.minBy { it.refreshRate }).id
    }

    private fun fits(displayRate: Float, videoRate: Float): Boolean {
        val ratio = displayRate / videoRate
        val whole = kotlin.math.round(ratio)
        return whole >= 1f && kotlin.math.abs(ratio - whole) / whole <= 0.0005f
    }

    /** After the display reports the new mode, the TV still needs this long to show a picture. */
    const val SETTLE_MS = 1_500L
    /** No mode change by then: the TV refused the mode, so play anyway. */
    const val SWITCH_TIMEOUT_MS = 4_000L
}

/** A display mode, as plain data so [FrameRatePolicy] can be unit-tested. */
data class DisplayModeInfo(val id: Int, val width: Int, val height: Int, val refreshRate: Float)
