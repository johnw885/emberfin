package com.johnw.emberfin.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text

/** Layout constants shared by every browse screen (960×540 dp canvas). */
object Dimens {
    val ScreenPadding = 44.dp
    val RowGap = 12.dp
    val TileGap = 12.dp
    val LandscapeTile = 204.dp
    val LandscapeTileLarge = 260.dp
    val PosterTile = 112.dp
    val PersonTile = 96.dp
    val TileCorner = 6.dp
    val FocusBorder = 2.5.dp
    const val FocusScale = 1.08f
    /** Room around a row so the scaled, glowing tile isn't clipped. */
    val RowPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 10.dp, bottom = 12.dp)
}

/** The one focus treatment: scale ~1.08, thick white border, soft white glow. */
object FocusStyle {
    @Composable fun scale() = CardDefaults.scale(focusedScale = Dimens.FocusScale)
    @Composable fun border(shape: Shape = RoundedCornerShape(Dimens.TileCorner)) = CardDefaults.border(
        focusedBorder = Border(BorderStroke(Dimens.FocusBorder, Color.White), shape = shape),
    )
    @Composable fun glow() = CardDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.28f), 10.dp))
}

/** Watched-progress bar drawn along the bottom of a tile. */
@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.fillMaxWidth().height(height).background(Color.White.copy(alpha = 0.35f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).background(Emberfin.colors.accent))
    }
}

/**
 * 16:9 tile: Continue Watching, Next Up, latest episodes, home videos. The caption under it is
 * muted and turns white on focus. [logo] is drawn bottom-left over art that has no baked-in title.
 */
@Composable
fun LandscapeTile(
    art: Art?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.LandscapeTile,
    subtitle: String? = null,
    progress: Float? = null,
    logo: Art? = null,
    showCaption: Boolean = true,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dimens.TileCorner)
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = CardDefaults.shape(shape),
            scale = FocusStyle.scale(),
            border = FocusStyle.border(shape),
            glow = FocusStyle.glow(),
            colors = CardDefaults.colors(containerColor = Emberfin.colors.surface),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) onFocus() } },
        ) {
            Box(Modifier.fillMaxSize()) {
                RemoteImage(art, contentDescription = title, modifier = Modifier.fillMaxSize())
                if (logo?.url != null) {
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)),
                        ),
                    )
                    RemoteImage(
                        logo, contentDescription = null, opaque = false, contentScale = ContentScale.Fit,
                        alignment = Alignment.BottomStart,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(0.55f).height(34.dp),
                    )
                }
                if (progress != null && progress > 0f) ProgressBar(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        if (showCaption) {
            Spacer(Modifier.height(6.dp))
            Text(
                title, style = EmberfinType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused) Emberfin.colors.onBackground else Emberfin.colors.muted,
            )
            if (subtitle != null) {
                Text(subtitle, style = EmberfinType.label, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Emberfin.colors.faint)
            }
        }
    }
}

/** 2:3 poster tile: library grids, More Like This, filmographies. */
@Composable
fun PosterTile(
    art: Art?,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.PosterTile,
    progress: Float? = null,
    played: Boolean = false,
    showCaption: Boolean = false,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dimens.TileCorner)
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(shape),
            scale = FocusStyle.scale(),
            border = FocusStyle.border(shape),
            glow = FocusStyle.glow(),
            colors = CardDefaults.colors(containerColor = Emberfin.colors.surface),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) onFocus() } },
        ) {
            Box(Modifier.fillMaxSize()) {
                RemoteImage(art, contentDescription = title, modifier = Modifier.fillMaxSize())
                if (art?.url == null) {
                    Text(
                        title, style = EmberfinType.bodyStrong, color = Emberfin.colors.onBackground,
                        modifier = Modifier.align(Alignment.Center).padding(10.dp), maxLines = 4,
                    )
                }
                if (played) WatchedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
                if (progress != null && progress > 0f) ProgressBar(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        if (showCaption) {
            Spacer(Modifier.height(6.dp))
            Text(
                title, style = EmberfinType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (focused) Emberfin.colors.onBackground else Emberfin.colors.muted,
            )
        }
    }
}

/** Episode card for the details page: still, "3. Title", runtime, two lines of synopsis. */
@Composable
fun EpisodeTile(
    art: Art?,
    title: String,
    meta: String?,
    overview: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = Dimens.LandscapeTile,
    progress: Float? = null,
    played: Boolean = false,
    onFocus: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Dimens.TileCorner)
    Column(modifier.width(width)) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(shape),
            scale = FocusStyle.scale(),
            border = FocusStyle.border(shape),
            glow = FocusStyle.glow(),
            colors = CardDefaults.colors(containerColor = Emberfin.colors.surface),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged { if (it.isFocused != focused) { focused = it.isFocused; if (it.isFocused) onFocus() } },
        ) {
            Box(Modifier.fillMaxSize()) {
                RemoteImage(art, contentDescription = title, modifier = Modifier.fillMaxSize())
                if (played) WatchedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
                if (progress != null && progress > 0f) ProgressBar(progress, Modifier.align(Alignment.BottomStart))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            title, style = EmberfinType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) Emberfin.colors.onBackground else Emberfin.colors.onBackground.copy(alpha = 0.85f),
        )
        if (meta != null) Text(meta, style = EmberfinType.label, color = Emberfin.colors.faint, maxLines = 1)
        if (overview != null) {
            Spacer(Modifier.height(2.dp))
            Text(overview, style = EmberfinType.meta, color = Emberfin.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Round headshot + name + role, for the cast row. */
@Composable
fun PersonTile(
    art: Art?,
    name: String,
    role: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Dimens.PersonTile,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(size + 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(CircleShape),
            scale = FocusStyle.scale(),
            border = FocusStyle.border(CircleShape),
            glow = FocusStyle.glow(),
            colors = CardDefaults.colors(containerColor = Emberfin.colors.surfaceRaised),
            modifier = Modifier.size(size).onFocusChanged { focused = it.isFocused },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (art?.url != null) {
                    RemoteImage(art, contentDescription = name, modifier = Modifier.fillMaxSize(), alignment = Alignment.TopCenter)
                } else {
                    Text(initials(name), style = EmberfinType.title, color = Emberfin.colors.muted)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name, style = EmberfinType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) Emberfin.colors.onBackground else Emberfin.colors.muted,
        )
        if (role != null) Text(role, style = EmberfinType.label, color = Emberfin.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Small accent check in a dark disc: "you've watched this". */
@Composable
fun WatchedMark(modifier: Modifier = Modifier) {
    Box(
        modifier.size(20.dp).background(Color.Black.copy(alpha = 0.65f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        androidx.tv.material3.Icon(EmberfinIcons.Check, contentDescription = "Watched", tint = Emberfin.colors.accent, modifier = Modifier.size(13.dp))
    }
}

fun initials(name: String): String =
    name.split(' ', '-', '_').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
