package com.johnw.reelfin.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Placeholder palette for the spike. Session 3 builds the real design system here. */
object ReelfinColors {
    val Background = Color(0xFF101418)
    val Surface = Color(0xFF1B2127)
    val Accent = Color(0xFF2EC4B6)
    val OnBackground = Color(0xFFF2F4F5)
    val Muted = Color(0xFF9AA5AE)
}

@Composable
fun ReelfinTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = ReelfinColors.Accent,
            background = ReelfinColors.Background,
            surface = ReelfinColors.Surface,
            onBackground = ReelfinColors.OnBackground,
            onSurface = ReelfinColors.OnBackground,
        ),
        content = content,
    )
}
