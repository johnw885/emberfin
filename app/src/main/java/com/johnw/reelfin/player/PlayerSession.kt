package com.johnw.reelfin.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.johnw.reelfin.playback.ProgressReporter
import com.johnw.reelfin.playback.StreamPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient

/**
 * One playback: owns the ExoPlayer and reports start, progress (every 10 s and on pause/seek)
 * and stop. Session 2 splits this into the `:playback` player core with a MediaSession.
 */
class PlayerSession(
    context: Context,
    private val appScope: CoroutineScope,
    api: ApiClient,
    authorizationHeader: String,
    val plan: StreamPlan,
    val title: String,
    startPositionMs: Long,
) {
    private val reporter = ProgressReporter(api, plan)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent("Reelfin")
                    .setDefaultRequestProperties(mapOf("Authorization" to authorizationHeader)),
            ),
        )
        .build()

    private var started = false
    private var released = false
    private var progressJob: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !started) {
                    started = true
                    val pos = player.currentPosition
                    appScope.launch(Dispatchers.Main) { reporter.start(pos, paused = !player.playWhenReady) }
                    startProgressLoop()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) = reportProgressNow()

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = reportProgressNow()
        })
        player.setMediaItem(MediaItem.fromUri(plan.url), startPositionMs)
        player.playWhenReady = true
        player.prepare()
    }

    private fun startProgressLoop() {
        progressJob = appScope.launch(Dispatchers.Main) {
            while (isActive) {
                delay(ProgressReporter.PROGRESS_INTERVAL_MS)
                reporter.progress(player.currentPosition, paused = !player.isPlaying)
            }
        }
    }

    private fun reportProgressNow() {
        if (!started || released) return
        val pos = player.currentPosition
        val paused = !player.playWhenReady
        appScope.launch { reporter.progress(pos, paused) }
    }

    fun togglePlayPause() {
        player.playWhenReady = !player.playWhenReady
    }

    fun seekBy(deltaMs: Long) {
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
    }

    /** Stops playback and sends the final report, which is what leaves the resume point. */
    fun release() {
        if (released) return
        released = true
        progressJob?.cancel()
        val pos = player.currentPosition
        val failed = player.playerError != null
        player.release()
        if (started) appScope.launch { reporter.stop(pos, failed) }
    }
}
