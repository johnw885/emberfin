package com.johnw.emberfin.playback

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
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
    private var surface: Surface? = null
    private var videoFrameRate: Float? = null

    init {
        // ALWAYS is done by us on the surface (see applyFrameRate), so ExoPlayer's own switching is off.
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

    /** The player screen's SurfaceView holder. Video goes there; ALWAYS mode sets its frame rate. */
    fun attachSurface(holder: SurfaceHolder) {
        player.setVideoSurfaceHolder(holder)
    }

    fun detachSurface(holder: SurfaceHolder) {
        surface = null
        player.clearVideoSurfaceHolder(holder)
    }

    /** Called by the SurfaceView's callback when its surface is created (or destroyed: null). */
    fun onSurface(value: Surface?) {
        surface = value
        applyFrameRate()
    }

    private fun applyFrameRate() {
        if (frameRateMode != FrameRateMode.ALWAYS || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val s = surface?.takeIf { it.isValid } ?: return
        val rate = videoFrameRate ?: return
        runCatching { s.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS) }
            .onSuccess { Log.i(TAG, "Asked the display for $rate Hz (always)") }
            .onFailure { Log.w(TAG, "setFrameRate failed", it) }
    }

    private fun clearFrameRate() {
        if (frameRateMode != FrameRateMode.ALWAYS || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        surface?.takeIf { it.isValid }?.let { s ->
            runCatching { s.setFrameRate(0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS) }
        }
    }

    /** Plays [plan] from [startMs], ending the previous stream's reporting first. */
    fun load(plan: StreamPlan, startMs: Long, title: String, subtitle: String? = null) {
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
        player.playWhenReady = true
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
        player.playWhenReady = !player.playWhenReady
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
        clearFrameRate()
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
}
