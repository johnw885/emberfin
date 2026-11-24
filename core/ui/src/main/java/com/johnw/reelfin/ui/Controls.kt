package com.johnw.reelfin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/**
 * Action button (Play, More Info, My List, Restart…). Unfocused it's a translucent grey chip;
 * focused it turns solid white with dark text, so focus is unmistakable from the couch.
 */
@Composable
fun ActionButton(
    text: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    primary: Boolean = false,
) {
    val shape = RoundedCornerShape(6.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.height(38.dp),
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            // Netflix-style: every button is grey until focused, so focus is never ambiguous.
            containerColor = Color.White.copy(alpha = if (primary) 0.24f else 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Color.White), shape = shape),
        ),
    ) {
        Row(
            Modifier.padding(horizontal = if (text == null) 10.dp else 18.dp).height(38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(icon, contentDescription = text, modifier = Modifier.size(18.dp))
            if (icon != null && text != null) Spacer(Modifier.width(8.dp))
            if (text != null) Text(text, style = ReelfinType.bodyStrong)
        }
    }
}

/** Quality/feature badge: 4K, HDR10, Dolby Vision, Atmos, CC, 5.1, age rating. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    val c = if (highlight) Reelfin.colors.onBackground else Reelfin.colors.muted
    Box(
        modifier
            .border(BorderStroke(1.dp, c.copy(alpha = 0.7f)), RoundedCornerShape(3.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, style = ReelfinType.label, color = c)
    }
}

/** "2024 · 2h 14m · PG-13 [4K] [DV] [Atmos]": the metadata line under a title. */
@Composable
fun MetaLine(parts: List<String>, badges: List<String>, modifier: Modifier = Modifier, match: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (match != null) Text(match, style = ReelfinType.meta.copy(fontWeight = FontWeight.Bold), color = Reelfin.colors.match)
        if (parts.isNotEmpty()) Text(parts.joinToString("  ·  "), style = ReelfinType.meta, color = Reelfin.colors.onBackground.copy(alpha = 0.85f))
        badges.forEach { Badge(it) }
    }
}

/** The wordmark: "reelfin" in the accent, heavy weight. No mark that imitates anyone else's. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: Int = 22) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Reelfin.colors.accent)) { append("reel") }
            withStyle(SpanStyle(color = Reelfin.colors.onBackground)) { append("fin") }
        },
        style = ReelfinType.display.copy(fontSize = size.sp, lineHeight = (size + 4).sp, letterSpacing = (-0.6).sp),
        modifier = modifier,
    )
}

/**
 * A top-nav item. Unfocused: muted text, the current tab white with an accent underline.
 * Focused: a white pill with dark text.
 */
@Composable
fun NavItem(
    text: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = if (selected) Reelfin.colors.onBackground else Reelfin.colors.muted,
                focusedContainerColor = Color.White,
                focusedContentColor = Color.Black,
            ),
        ) {
            Row(
                Modifier.padding(horizontal = if (text == null) 7.dp else 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) Icon(icon, contentDescription = text, modifier = Modifier.size(18.dp))
                if (icon != null && text != null) Spacer(Modifier.width(6.dp))
                if (text != null) Text(text, style = ReelfinType.nav)
            }
        }
        Box(
            Modifier.padding(top = 3.dp).width(18.dp).height(3.dp)
                .background(if (selected && !focused) Reelfin.colors.accent else Color.Transparent, RoundedCornerShape(2.dp)),
        )
    }
}

/** Circular profile avatar: an image if the server has one, otherwise initials on a colour from the name. */
@Composable
fun Avatar(name: String, art: Art?, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 30.dp) {
    Box(
        modifier.size(size).background(avatarColor(name), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (art?.url != null) {
            RemoteImage(art, contentDescription = name, modifier = Modifier.size(size).clip(CircleShape))
        } else {
            Text(initials(name), color = Color.White, style = ReelfinType.title.copy(fontSize = (size.value * 0.4f).sp))
        }
    }
}

/** Stable, pleasant colours for initials avatars (none of them the accent, so profiles stay distinct). */
fun avatarColor(name: String): Color {
    val palette = listOf(0xFF3D7BD9, 0xFF2FA36B, 0xFFD9833D, 0xFF9B59D0, 0xFFD94F70, 0xFF2CA6B8, 0xFF7A8B2E)
    return Color(palette[(name.hashCode() and 0x7fffffff) % palette.size])
}

/** Spacing used between rows of buttons on billboards and details pages. */
val ButtonRowSpacing = PaddingValues(top = 14.dp)
