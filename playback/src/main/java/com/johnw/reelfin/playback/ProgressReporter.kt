package com.johnw.reelfin.playback

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode

/**
 * Reports `Sessions/Playing`, `/Progress` and `/Stopped` for one stream. Failures are logged
 * and swallowed: a reporting hiccup must never stop playback.
 */
class ProgressReporter(private val api: ApiClient, private val plan: StreamPlan) {
    suspend fun start(positionMs: Long, paused: Boolean) = report("start") {
        api.sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = true,
                itemId = plan.itemId,
                mediaSourceId = plan.mediaSourceId,
                playSessionId = plan.playSessionId,
                isPaused = paused,
                isMuted = false,
                positionTicks = positionMs.toTicks(),
                playMethod = plan.playMethod,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    suspend fun progress(positionMs: Long, paused: Boolean) = report("progress") {
        api.sessionApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                canSeek = true,
                itemId = plan.itemId,
                mediaSourceId = plan.mediaSourceId,
                playSessionId = plan.playSessionId,
                isPaused = paused,
                isMuted = false,
                positionTicks = positionMs.toTicks(),
                playMethod = plan.playMethod,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    suspend fun stop(positionMs: Long, failed: Boolean = false) = report("stop") {
        api.sessionApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = plan.itemId,
                mediaSourceId = plan.mediaSourceId,
                playSessionId = plan.playSessionId,
                positionTicks = positionMs.toTicks(),
                failed = failed,
            ),
        )
    }

    private suspend fun report(what: String, block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        runCatching { block() }.onFailure { Log.w(TAG, "Playback $what report failed", it) }
        Unit
    }

    companion object {
        private const val TAG = "ProgressReporter"
        const val PROGRESS_INTERVAL_MS = 10_000L

        /** Jellyfin ticks are 100 ns. */
        fun Long.toTicks(): Long = this * 10_000
    }
}
