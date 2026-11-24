package com.johnw.emberfin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Emberfin's own small icon set, drawn on a 24×24 grid (no icon library needed). */
object EmberfinIcons {
    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(block).build()

    private fun ImageVector.Builder.stroke(width: Float = 2.4f, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(
            stroke = SolidColor(Color.Black), strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
        )

    private fun ImageVector.Builder.fill(block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
        path(fill = SolidColor(Color.Black), pathBuilder = block)

    val Play = icon("play") { fill { moveTo(7f, 4.5f); lineTo(20f, 12f); lineTo(7f, 19.5f); close() } }

    val Plus = icon("plus") { stroke { moveTo(12f, 4.5f); lineTo(12f, 19.5f); moveTo(4.5f, 12f); lineTo(19.5f, 12f) } }

    val Check = icon("check") { stroke { moveTo(4.5f, 12.5f); lineTo(9.5f, 17.5f); lineTo(19.5f, 6.5f) } }

    val Info = icon("info") {
        stroke(2f) { moveTo(12f, 3f); arcTo(9f, 9f, 0f, true, true, 11.99f, 3f) }
        stroke { moveTo(12f, 11f); lineTo(12f, 17f) }
        fill { moveTo(12f, 6.4f); arcTo(1.4f, 1.4f, 0f, true, true, 11.99f, 6.4f); close() }
    }

    val Restart = icon("restart") {
        stroke { moveTo(5.5f, 8f); arcTo(8f, 8f, 0f, true, true, 4f, 12.5f) }
        fill { moveTo(3.2f, 3.8f); lineTo(9f, 8.6f); lineTo(3.2f, 10.4f); close() }
    }

    val Search = icon("search") {
        stroke { moveTo(10.5f, 3.5f); arcTo(7f, 7f, 0f, true, true, 10.49f, 3.5f) }
        stroke(2.8f) { moveTo(15.6f, 15.6f); lineTo(20.5f, 20.5f) }
    }

    val Settings = icon("settings") {
        stroke(2.2f) { moveTo(12f, 8.5f); arcTo(3.5f, 3.5f, 0f, true, true, 11.99f, 8.5f) }
        stroke(2.6f) {
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0)
                moveTo(12f + 6.2f * kotlin.math.cos(a).toFloat(), 12f + 6.2f * kotlin.math.sin(a).toFloat())
                lineTo(12f + 8.6f * kotlin.math.cos(a).toFloat(), 12f + 8.6f * kotlin.math.sin(a).toFloat())
            }
        }
        stroke(2f) { moveTo(12f, 5.5f); arcTo(6.5f, 6.5f, 0f, true, true, 11.99f, 5.5f) }
    }

    val Back = icon("back") { stroke { moveTo(15f, 4.5f); lineTo(7.5f, 12f); lineTo(15f, 19.5f) } }

    val Mic = icon("mic") {
        stroke(2.2f) { moveTo(9f, 6f); arcTo(3f, 3f, 0f, false, true, 15f, 6f); lineTo(15f, 11f); arcTo(3f, 3f, 0f, false, true, 9f, 11f); close() }
        stroke(2f) { moveTo(5.5f, 10.5f); arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 10.5f); moveTo(12f, 17f); lineTo(12f, 20.5f) }
    }

    val Backspace = icon("backspace") {
        stroke(2f) { moveTo(8f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(8f, 19f); lineTo(2.5f, 12f); close() }
        stroke(2.2f) { moveTo(11f, 9f); lineTo(16f, 15f); moveTo(16f, 9f); lineTo(11f, 15f) }
    }

    val Space = icon("space") { stroke(2.4f) { moveTo(4f, 11f); lineTo(4f, 15f); lineTo(20f, 15f); lineTo(20f, 11f) } }

    /** A thumb drawn in a 24 grid, scaled by [s], moved by [dx]/[dy], pointing down when [down]. */
    private fun ImageVector.Builder.thumb(s: Float = 1f, dx: Float = 0f, dy: Float = 0f, down: Boolean = false, width: Float = 2f) {
        fun x(v: Float) = dx + v * s
        fun y(v: Float) = dy + (if (down) 24f - v else v) * s
        stroke(width) {
            // Cuff.
            moveTo(x(3.5f), y(10.5f)); lineTo(x(7f), y(10.5f)); lineTo(x(7f), y(20f)); lineTo(x(3.5f), y(20f)); close()
            // Hand and thumb.
            moveTo(x(7f), y(10.5f)); lineTo(x(10.5f), y(3.5f))
            curveTo(x(12.5f), y(3.5f), x(13.5f), y(5f), x(13f), y(7f))
            lineTo(x(12.2f), y(9.5f)); lineTo(x(18.5f), y(9.5f))
            curveTo(x(20f), y(9.5f), x(21f), y(11f), x(20.5f), y(12.5f))
            lineTo(x(18.5f), y(18.5f))
            curveTo(x(18.1f), y(19.5f), x(17.4f), y(20f), x(16.4f), y(20f))
            lineTo(x(7f), y(20f))
        }
    }

    val ThumbUp = icon("thumb-up") { thumb() }
    val ThumbDown = icon("thumb-down") { thumb(down = true) }
    /** "Love this": two thumbs up. */
    val ThumbsUpDouble = icon("thumbs-up-double") { thumb(s = 0.7f, dx = 0f, dy = 6.5f, width = 1.8f); thumb(s = 0.7f, dx = 7.5f, dy = 1f, width = 1.8f) }

    val Lock = icon("lock") {
        stroke(2.2f) { moveTo(5f, 11f); lineTo(19f, 11f); lineTo(19f, 20.5f); lineTo(5f, 20.5f); close() }
        stroke(2.2f) { moveTo(8f, 11f); lineTo(8f, 8f); arcTo(4f, 4f, 0f, false, true, 16f, 8f); lineTo(16f, 11f) }
    }

    val Episodes = icon("episodes") {
        stroke(2f) { moveTo(3.5f, 6f); lineTo(20.5f, 6f); moveTo(3.5f, 12f); lineTo(20.5f, 12f); moveTo(3.5f, 18f); lineTo(14f, 18f) }
    }
}
