package com.johnw.emberfin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/**
 * Emberfin's accent. Marquee (amber) was picked from rendered TV options; the others stay for comparison
 * and a possible setting. It's used sparingly, Netflix-style: the wordmark, progress bars, the selected
 * nav tab and small highlights. Focus itself is always white so it reads on any artwork.
 */
enum class Accent(val label: String, val color: Color, val onColor: Color) {
    Lagoon("Lagoon (teal)", Color(0xFF2EC4B6), Color(0xFF00201D)),
    Marquee("Marquee (amber)", Color(0xFFFFB627), Color(0xFF2A1A00)),
    Iris("Iris (violet)", Color(0xFF8C7BFF), Color(0xFF14093D)),
}

/** The colour tokens every screen uses. Dark only: it's a TV app. */
@Immutable
data class EmberfinPalette(
    val accent: Color,
    val onAccent: Color,
    val background: Color = Color(0xFF0E0F11),
    val surface: Color = Color(0xFF1A1C20),
    val surfaceRaised: Color = Color(0xFF26292E),
    val onBackground: Color = Color(0xFFF4F5F6),
    val muted: Color = Color(0xFFA3A8AF),
    val faint: Color = Color(0xFF6B7078),
    val focus: Color = Color.White,
    val error: Color = Color(0xFFFF8A80),
    val match: Color = Color(0xFF46D369),
)

val LocalPalette = staticCompositionLocalOf { EmberfinPalette(Accent.Marquee.color, Accent.Marquee.onColor) }

/** Shortcut: `Emberfin.colors.accent`, `Emberfin.type.title`. */
object Emberfin {
    val colors: EmberfinPalette @Composable get() = LocalPalette.current
    val type: EmberfinType get() = EmberfinType
}

/** Back-compat for code written against the spike's palette. */
object EmberfinColors {
    val Background = Color(0xFF0E0F11)
    val Surface = Color(0xFF1A1C20)
    val OnBackground = Color(0xFFF4F5F6)
    val Muted = Color(0xFFA3A8AF)
    val Accent: Color @Composable get() = LocalPalette.current.accent
}

/**
 * 10-foot type scale. Google TV lays out at 960×540 dp (1 dp = 2 px at 1080p), viewed from ~3 m.
 * The family is [EmberfinFonts.Sans] (Inter).
 */
object EmberfinType {
    private val f get() = EmberfinFonts.Sans
    val display = TextStyle(fontFamily = f, fontSize = 40.sp, fontWeight = FontWeight.Black, lineHeight = 44.sp, letterSpacing = (-0.5).sp)
    val headline = TextStyle(fontFamily = f, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
    val title = TextStyle(fontFamily = f, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 26.sp)
    val rowTitle = TextStyle(fontFamily = f, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val body = TextStyle(fontFamily = f, fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 20.sp)
    val bodyStrong = TextStyle(fontFamily = f, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
    val meta = TextStyle(fontFamily = f, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp)
    val label = TextStyle(fontFamily = f, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 14.sp, letterSpacing = 0.3.sp)
    val nav = TextStyle(fontFamily = f, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
}

/** Inter (SIL OFL 1.1, bundled in res/font; licence text in res/raw/inter_ofl.txt). */
object EmberfinFonts {
    val Sans: FontFamily = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
        Font(R.font.inter_bold, FontWeight.Bold),
        Font(R.font.inter_black, FontWeight.Black),
    )
}

@Composable
fun EmberfinTheme(accent: Accent = Accent.Marquee, content: @Composable () -> Unit) {
    val palette = EmberfinPalette(accent = accent.color, onAccent = accent.onColor)
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = palette.accent,
            onPrimary = palette.onAccent,
            background = palette.background,
            surface = palette.surface,
            surfaceVariant = palette.surfaceRaised,
            onBackground = palette.onBackground,
            onSurface = palette.onBackground,
            onSurfaceVariant = palette.muted,
            border = palette.focus,
            error = palette.error,
        ),
        typography = Typography(
            displayLarge = EmberfinType.display,
            headlineLarge = EmberfinType.headline,
            titleLarge = EmberfinType.title,
            titleMedium = EmberfinType.rowTitle,
            bodyLarge = EmberfinType.body,
            bodyMedium = EmberfinType.meta,
            labelLarge = EmberfinType.label,
        ),
    ) {
        CompositionLocalProvider(LocalPalette provides palette, content = content)
    }
}
