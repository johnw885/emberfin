package com.johnw.emberfin.browse

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.johnw.emberfin.data.SessionManager
import com.johnw.emberfin.settings.SettingsStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.koinInject

/**
 * The one muted player for billboard trailers, app-wide, so there's never more than one preview
 * decoder. Audio is switched off entirely (no audio decoder, nothing sent to the receiver), the
 * display's refresh rate is never changed, and [release] runs before main playback starts
 * (see `AppNavigator.play`).
 */
@OptIn(UnstableApi::class)
class PreviewPlayer(private val context: Context) {
    private var player: ExoPlayer? = null
    private var holder: SurfaceHolder? = null
    private val main = Handler(Looper.getMainLooper())

    private val _rendering = MutableStateFlow(false)
    /** True once the first video frame is on screen (the backdrop can fade away). */
    val rendering: StateFlow<Boolean> = _rendering.asStateFlow()

    private val _videoSize = MutableStateFlow<VideoSize?>(null)
    val videoSize: StateFlow<VideoSize?> = _videoSize.asStateFlow()

    fun play(url: String, authorization: String, onFinished: () -> Unit) {
        release()
        Log.i(TAG, "start")
        val p = ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory().setUserAgent("Emberfin")
                        .setDefaultRequestProperties(mapOf("Authorization" to authorization)),
                ),
            )
            // Start fast; a preview doesn't need a big buffer.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 15_000, 1_000, 2_000).build())
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
            .build()
        p.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .setMaxVideoSize(1920, 1080)
            .build()
        p.volume = 0f
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                Log.i(TAG, "first frame")
                _rendering.value = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) _videoSize.value = videoSize
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) finish(p, onFinished, "ended")
            }

            override fun onPlayerError(error: PlaybackException) = finish(p, onFinished, "error ${error.errorCodeName}")
        })
        holder?.let { p.setVideoSurfaceHolder(it) }
        p.setMediaItem(MediaItem.fromUri(url))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    private fun finish(p: ExoPlayer, onFinished: () -> Unit, why: String) {
        Log.i(TAG, why)
        // Not from inside the player's own callback.
        main.post {
            if (player === p) release()
            onFinished()
        }
    }

    fun attach(h: SurfaceHolder) {
        holder = h
        player?.setVideoSurfaceHolder(h)
    }

    fun detach(h: SurfaceHolder) {
        if (holder !== h) return
        player?.clearVideoSurfaceHolder(h)
        holder = null
    }

    fun release() {
        val p = player ?: return
        player = null
        p.release()
        _rendering.value = false
        _videoSize.value = null
        Log.i(TAG, "released")
    }

    companion object {
        const val TAG = "EmberfinPreview"
        /** How long the billboard must keep focus before its trailer starts. */
        const val DELAY_MS = 3_000L
    }
}

/**
 * The billboard's muted trailer. Starts [PreviewPlayer.DELAY_MS] after the billboard settles
 * ([active]: it has focus and the screen is in front), stops as soon as focus leaves, and plays
 * once. [onVideo] says when frames are showing, so the caller can fade its backdrop out (the
 * video sits under it). Without a trailer, or with Settings → Billboard trailers off, nothing
 * happens and the Ken Burns backdrop stays.
 */
@Composable
fun BillboardTrailer(
    item: BaseItemDto?,
    active: Boolean,
    trailerUrl: suspend (BaseItemDto) -> String?,
    onVideo: (Boolean) -> Unit,
) {
    val preview: PreviewPlayer = koinInject()
    val settingsStore: SettingsStore = koinInject()
    val sessions: SessionManager = koinInject()
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    var resumed by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumed = true }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { resumed = false }

    val run = active && resumed && settings.billboardTrailers && item != null
    var url by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(item?.id, run) {
        url = null
        if (!run) return@LaunchedEffect
        delay(PreviewPlayer.DELAY_MS)
        url = trailerUrl(item!!)
    }
    val rendering by preview.rendering.collectAsStateWithLifecycle()
    LaunchedEffect(rendering, url) { onVideo(rendering && url != null) }

    val playUrl = url ?: return
    DisposableEffect(playUrl) {
        preview.play(playUrl, sessions.authorizationHeader()) { url = null }
        onDispose { preview.release() }
    }
    val size by preview.videoSize.collectAsStateWithLifecycle()
    // Crop to fill the screen, like the backdrop it replaces.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val v = size
        val m = if (v == null) Modifier.fillMaxSize() else {
            val aspect = v.width * v.pixelWidthHeightRatio / v.height
            if (aspect > maxWidth / maxHeight) Modifier.requiredSize(maxHeight * aspect, maxHeight)
            else Modifier.requiredSize(maxWidth, maxWidth / aspect)
        }
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { preview.attach(it.holder) } },
            onRelease = { preview.detach(it.holder) },
            modifier = m,
        )
    }
}
