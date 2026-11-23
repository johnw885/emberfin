package com.johnw.reelfin.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
import androidx.media3.exoplayer.ExoPlayer
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
        .setUserAgent("Reelfin")
        .setAllowCrossProtocolRedirects(true)
        .setDefaultRequestProperties(mapOf("Authorization" to authorizationHeader))

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    private val mediaSession: MediaSession = MediaSession.Builder(context, player)
        .setId("reelfin-${sessionCounter++}")
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

    init {
        player.videoChangeFrameRateStrategy = if (settings.frameRateMatching) {
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS
        } else {
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        }
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

    /** Plays [plan] from [startMs], ending the previous stream's reporting first. */
    fun load(plan: StreamPlan, startMs: Long, title: String, subtitle: String? = null) {
        finishReporting(failed = false)
        this.plan = plan
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
        mediaSession.release()
        player.release()
    }

    private companion object {
        var sessionCounter = 0
    }
}
