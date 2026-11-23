package com.johnw.reelfin.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.text.Cue
import androidx.tv.material3.Text

/**
 * Draws ExoPlayer's subtitle cues over the video: text cues (SRT, ASS, VTT) in our own caption
 * style, bitmap cues (PGS, VobSub) where the subtitle stream places them. media3-ui-compose has
 * no subtitle view, and drawing them here gives full control of size and background.
 *
 * @param videoAspect width/height of the picture, so cues land inside the letterboxed frame.
 */
@Composable
fun SubtitleLayer(cues: List<Cue>, videoAspect: Float, scale: Float, background: Boolean, liftForOverlay: Boolean) {
    if (cues.isEmpty()) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val screenW = constraints.maxWidth.toFloat()
        val screenH = constraints.maxHeight.toFloat()
        val aspect = if (videoAspect > 0f) videoAspect else screenW / screenH
        val (frameW, frameH) = if (screenW / screenH > aspect) (screenH * aspect) to screenH else screenW to (screenW / aspect)
        val frameX = (screenW - frameW) / 2
        val frameY = (screenH - frameH) / 2

        cues.filter { it.bitmap != null }.forEach { cue ->
            val bmp = cue.bitmap!!
            val w = if (cue.size != Cue.DIMEN_UNSET) cue.size * frameW else bmp.width.toFloat()
            val h = if (cue.bitmapHeight != Cue.DIMEN_UNSET) cue.bitmapHeight * frameH else w * bmp.height / bmp.width
            val x = frameX + anchored(cue.position.takeIf { it != Cue.DIMEN_UNSET } ?: 0.5f, cue.positionAnchor, w / frameW) * frameW
            val y = frameY + anchored(cue.line.takeIf { it != Cue.DIMEN_UNSET && cue.lineType == Cue.LINE_TYPE_FRACTION } ?: 0.9f, cue.lineAnchor, h / frameH) * frameH
            val image = bmp.asImageBitmap()
            with(density) {
                Canvas(Modifier.offset { IntOffset(x.toInt(), y.toInt()) }.size(w.toDp(), h.toDp())) {
                    drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                }
            }
        }

        val text = cues.filter { it.bitmap == null && !it.text.isNullOrBlank() }
        if (text.isNotEmpty()) {
            val top = text.all { it.lineType == Cue.LINE_TYPE_FRACTION && it.line != Cue.DIMEN_UNSET && it.line < 0.4f }
            val fontPx = screenH * 0.045f * scale
            val bottomInset = (screenH - (frameY + frameH)) + frameH * 0.06f + if (liftForOverlay) screenH * 0.18f else 0f
            Column(
                Modifier
                    .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(
                        top = with(density) { (frameY + frameH * 0.06f).toDp() },
                        bottom = with(density) { bottomInset.toDp() },
                        start = 96.dp,
                        end = 96.dp,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                text.forEach { cue ->
                    Box(
                        if (background) Modifier.background(Color(0xB0000000), RoundedCornerShape(4.dp)).padding(horizontal = 10.dp, vertical = 2.dp)
                        else Modifier,
                    ) {
                        Text(
                            cue.text.toString(),
                            style = TextStyle(
                                color = Color.White,
                                fontSize = with(density) { fontPx.toSp() },
                                textAlign = TextAlign.Center,
                                shadow = if (background) null else Shadow(Color.Black, Offset(2f, 2f), 6f),
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** Converts a cue coordinate plus its anchor into the top/left edge (all as fractions). */
private fun anchored(value: Float, anchor: Int, extent: Float): Float = when (anchor) {
    Cue.ANCHOR_TYPE_MIDDLE -> value - extent / 2
    Cue.ANCHOR_TYPE_END -> value - extent
    else -> value
}
