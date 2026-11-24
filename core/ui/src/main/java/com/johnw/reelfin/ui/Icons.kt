package com.johnw.reelfin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Reelfin's own small icon set, drawn on a 24×24 grid (no icon library needed). */
object ReelfinIcons {
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

    val Episodes = icon("episodes") {
        stroke(2f) { moveTo(3.5f, 6f); lineTo(20.5f, 6f); moveTo(3.5f, 12f); lineTo(20.5f, 12f); moveTo(3.5f, 18f); lineTo(14f, 18f) }
    }
}
