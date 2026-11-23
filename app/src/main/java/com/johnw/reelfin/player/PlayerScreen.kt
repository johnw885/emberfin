package com.johnw.reelfin.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

/** Spike player: SurfaceView (needed for HDR), OK = play/pause, Left/Right = -/+10 s, Back = stop. */
@Composable
fun PlayerScreen(session: PlayerSession, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    val focus = remember { FocusRequester() }
    var overlayUntil by remember { mutableLongStateOf(System.currentTimeMillis() + 5_000) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(session) {
        while (true) {
            val p = session.player
            position = p.currentPosition
            duration = p.duration.coerceAtLeast(0)
            playing = p.playWhenReady
            error = p.playerError?.let { "${it.errorCodeName}: ${it.message}" }
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                overlayUntil = System.currentTimeMillis() + 4_000
                when (ev.key.nativeKeyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                        session.togglePlayPause(); true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { session.seekBy(-10_000); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { session.seekBy(10_000); true }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
                    else -> false
                }
            },
    ) {
        ContentFrame(
            player = session.player,
            modifier = Modifier.fillMaxSize(),
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
        )
        val showOverlay = !playing || error != null || now < overlayUntil
        if (showOverlay) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .padding(horizontal = 48.dp, vertical = 32.dp),
            ) {
                Text(session.title, color = Color.White, fontSize = 24.sp)
                Text(
                    "${format(position)} / ${format(duration)}  ·  ${if (playing) "Playing" else "Paused"}  ·  ${session.plan.playMethod.serialName}",
                    color = Color(0xFFCCCCCC),
                    fontSize = 16.sp,
                )
                error?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 16.sp) }
            }
        }
    }
}

private fun format(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

