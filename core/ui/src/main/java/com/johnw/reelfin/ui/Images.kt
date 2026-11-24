package com.johnw.reelfin.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowRgb565
import coil3.request.crossfade

/** A sized image URL plus the BlurHash Jellyfin sent for it. */
@Immutable
data class Art(val url: String?, val blurHash: String? = null)

@Composable
fun rememberBlurHashPainter(hash: String?): Painter? = remember(hash) {
    hash?.let { BlurHash.bitmap(it) }?.let { BitmapPainter(it.asImageBitmap()) }
}

/**
 * A remote image with a BlurHash placeholder and a short crossfade. [opaque] images (photos)
 * decode as RGB_565 to halve their memory; logos keep alpha.
 */
@Composable
fun RemoteImage(
    art: Art?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    opaque: Boolean = true,
    crossfadeMs: Int = 180,
) {
    val context = LocalContext.current
    val placeholder = rememberBlurHashPainter(art?.blurHash)
    val request = remember(art?.url, opaque) {
        art?.url?.let {
            ImageRequest.Builder(context).data(it).crossfade(crossfadeMs).allowRgb565(opaque).build()
        }
    }
    if (request == null) {
        if (placeholder != null) {
            androidx.compose.foundation.Image(placeholder, contentDescription, modifier, contentScale = ContentScale.Crop)
        } else {
            Box(modifier.background(ReelfinColors.Surface))
        }
        return
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        placeholder = placeholder,
        error = placeholder,
        contentScale = contentScale,
        alignment = alignment,
    )
}

/**
 * Full-bleed backdrop with a slow Ken Burns drift (zoom and pan over ~24 s, back and forth).
 * Used on the billboard and details page when there's no trailer to play.
 */
@Composable
fun KenBurnsBackdrop(art: Art?, modifier: Modifier = Modifier, animate: Boolean = true) {
    val t = if (animate) {
        val transition = rememberInfiniteTransition(label = "kenburns")
        transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Reverse),
            label = "kenburns-t",
        )
    } else null
    Box(modifier.fillMaxSize()) {
        RemoteImage(
            art = art,
            contentDescription = null,
            crossfadeMs = 450,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = t?.value ?: 0f
                    val scale = 1.04f + 0.08f * p
                    scaleX = scale
                    scaleY = scale
                    translationX = -size.width * 0.025f * p
                    translationY = -size.height * 0.015f * p
                },
        )
    }
}

/**
 * Left and bottom scrims so text over artwork stays readable (billboard, details, focus hero).
 * [strength] 1 = billboard; lower values for details pages with less text.
 *
 * Drawn as one small pre-rendered bitmap stretched to the screen: two full-screen gradient
 * layers cost ~27 ms of GPU per frame on the Google TV Streamer, the bitmap about 1 ms.
 */
@Composable
fun BoxScope.HeroScrims(background: Color = ReelfinColors.Background, strength: Float = 1f) {
    val bitmap = remember(background, strength) { scrimBitmap(background, strength) }
    androidx.compose.foundation.Image(
        bitmap, contentDescription = null, modifier = Modifier.matchParentSize(),
        contentScale = ContentScale.FillBounds,
    )
}

private fun scrimBitmap(bg: Color, strength: Float): androidx.compose.ui.graphics.ImageBitmap {
    val w = 192
    val h = 108
    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint().apply { isDither = true }
    fun c(alpha: Float) = bg.copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
    paint.shader = android.graphics.LinearGradient(
        0f, 0f, w.toFloat(), 0f,
        intArrayOf(c(0.97f * strength), c(0.75f * strength), c(0f)), floatArrayOf(0f, 0.35f, 0.65f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.shader = android.graphics.LinearGradient(
        0f, 0f, 0f, h.toFloat(),
        intArrayOf(c(0f), c(0.7f), c(1f)), floatArrayOf(0.45f, 0.8f, 1f),
        android.graphics.Shader.TileMode.CLAMP,
    )
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    return bmp.asImageBitmap()
}
