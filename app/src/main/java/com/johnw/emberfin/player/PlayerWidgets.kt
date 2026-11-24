package com.johnw.emberfin.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.johnw.emberfin.playback.SegmentUi
import com.johnw.emberfin.ui.EmberfinColors
import org.jellyfin.sdk.model.api.MediaSegmentType

private val Scrim = Color(0xCC000000)
private val FocusFill = Color.White
private val IdleFill = Color(0x33FFFFFF)

@Composable
fun BoxScope.PlayerOverlay(
    state: PlayerUiState,
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    scrubbing: Boolean,
    focus: PlayerFocus,
    trickplay: @Composable (Long) -> Unit,
) {
    // Title, top-left.
    Column(
        Modifier
            .align(Alignment.TopStart)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Scrim, Color.Transparent)))
            .padding(start = 56.dp, end = 56.dp, top = 32.dp, bottom = 48.dp),
    ) {
        Text(state.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.subtitle?.let { Text(it, color = EmberfinColors.Muted, fontSize = 18.sp, maxLines = 1) }
    }

    if (!playing && !scrubbing) {
        Box(Modifier.align(Alignment.Center).size(96.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
            Text("❚❚", color = Color.White, fontSize = 36.sp)
        }
    }

    // Seek bar and buttons, bottom.
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Scrim)))
            .padding(start = 56.dp, end = 56.dp, top = 64.dp, bottom = 32.dp),
    ) {
        SeekBar(positionMs, durationMs, focused = focus == PlayerFocus.SEEK_BAR, scrubbing = scrubbing, trickplay = trickplay)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OverlayButton("Audio & Subtitles", focus == PlayerFocus.AUDIO_SUBS)
            if (state.nextTitle != null) OverlayButton("Next Episode", focus == PlayerFocus.NEXT_EPISODE)
            Spacer(Modifier.weight(1f))
            Text(state.methodLabel, color = EmberfinColors.Muted, fontSize = 14.sp)
        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, focused: Boolean, scrubbing: Boolean, trickplay: @Composable (Long) -> Unit) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val barWidth = maxWidth - 220.dp
        if (scrubbing) {
            // Thumbnail above the playhead, kept on screen.
            val thumbX = (100.dp + barWidth * fraction - 160.dp).coerceIn(0.dp, maxWidth - 320.dp)
            Box(Modifier.offset(x = thumbX, y = (-200).dp)) { trickplay(positionMs) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(positionMs), color = Color.White, fontSize = 18.sp, modifier = Modifier.width(100.dp))
            Box(Modifier.width(barWidth).height(if (focused) 8.dp else 4.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)).background(Color(0x55FFFFFF)))
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(EmberfinColors.Accent))
                if (focused) {
                    Box(Modifier.offset(x = barWidth * fraction - 9.dp).size(18.dp).background(Color.White, CircleShape))
                }
            }
            Text(
                "-" + formatTime((durationMs - positionMs).coerceAtLeast(0)),
                color = Color.White,
                fontSize = 18.sp,
                modifier = Modifier.width(120.dp).padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun OverlayButton(label: String, focused: Boolean) {
    Box(
        Modifier
            .background(if (focused) FocusFill else IdleFill, RoundedCornerShape(6.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Skip Intro / Recap pill and the next-episode card, bottom-right. */
@Composable
fun BoxScope.SegmentPills(state: PlayerUiState, focused: Boolean, overlayVisible: Boolean) {
    val bottom = if (overlayVisible) 150.dp else 64.dp
    when (val seg = state.segment) {
        is SegmentUi.SkipButton -> {
            val label = when (seg.segment.type) {
                MediaSegmentType.INTRO -> "Skip Intro"
                MediaSegmentType.RECAP -> "Skip Recap"
                MediaSegmentType.PREVIEW -> "Skip Preview"
                MediaSegmentType.OUTRO -> "Skip Credits"
                MediaSegmentType.COMMERCIAL -> "Skip Ad"
                else -> "Skip"
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 56.dp, bottom = bottom)
                    .background(if (focused) Color.White else Color(0xCC1B2127), RoundedCornerShape(6.dp))
                    .border(2.dp, Color.White, RoundedCornerShape(6.dp))
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) {
                Text(label, color = if (focused) Color.Black else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        is SegmentUi.NextUp -> {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 56.dp, bottom = bottom)
                    .widthIn(max = 420.dp)
                    .background(Color(0xE61B2127), RoundedCornerShape(8.dp))
                    .padding(20.dp),
            ) {
                Text("Next Episode", color = EmberfinColors.Muted, fontSize = 16.sp)
                state.nextTitle?.let { Text(it, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(14.dp))
                val remaining = seg.remainingMs
                val label = if (remaining != null) "Playing in ${(remaining + 999) / 1000}" else "Play Next Episode"
                Box(
                    Modifier
                        .background(if (focused) Color.White else IdleFill, RoundedCornerShape(6.dp))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(label, color = if (focused) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                if (remaining != null) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                        Box(Modifier.fillMaxWidth(1f - remaining.toFloat() / seg.totalMs).fillMaxHeight().background(EmberfinColors.Accent))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Back to keep watching", color = EmberfinColors.Muted, fontSize = 14.sp)
            }
        }
        SegmentUi.None -> Unit
    }
}

/** Two-column audio / subtitle panel on the right. TrueHD/DTS tracks never appear (filtered by AudioTrackPolicy). */
@Composable
fun BoxScope.TrackMenu(state: PlayerUiState, subtitleOptions: List<TrackOption?>, column: Int, row: Int) {
    Row(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(760.dp)
            .background(Color(0xF2101418))
            .padding(horizontal = 40.dp, vertical = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        TrackColumn("Audio", state.audioTracks.map { it.label }, state.audioTracks.indexOfFirst { it.index == state.selectedAudio }, if (column == 0) row else -1)
        TrackColumn(
            "Subtitles",
            subtitleOptions.map { it?.label ?: "Off" },
            subtitleOptions.indexOfFirst { it?.index == state.selectedSubtitle },
            if (column == 1) row else -1,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TrackColumn(title: String, labels: List<String>, selected: Int, focused: Int) {
    val listState = rememberLazyListState()
    LaunchedEffect(focused) { if (focused >= 0) listState.animateScrollToItem((focused - 3).coerceAtLeast(0)) }
    Column(Modifier.weight(1f)) {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (labels.isEmpty()) Text("None available", color = EmberfinColors.Muted, fontSize = 16.sp)
        LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(labels) { i, label ->
                val isFocused = i == focused
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (isFocused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (i == selected) "✓ " else "   ", color = if (isFocused) Color.Black else EmberfinColors.Accent, fontSize = 16.sp)
                    Text(label, color = if (isFocused) Color.Black else Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun BoxScope.LoadingBadge(initial: Boolean) {
    Box(Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 24.dp, vertical = 14.dp)) {
        Text(if (initial) "Loading…" else "Buffering…", color = Color.White, fontSize = 20.sp)
    }
}

@Composable
fun BoxScope.Notice(text: String) {
    Box(Modifier.align(Alignment.TopEnd).padding(32.dp).background(Color(0xE61B2127), RoundedCornerShape(8.dp)).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(text, color = Color.White, fontSize = 18.sp)
    }
}

@Composable
fun BoxScope.ErrorPanel(text: String) {
    Column(
        Modifier.align(Alignment.Center).widthIn(max = 800.dp).background(Color(0xE61B2127), RoundedCornerShape(8.dp)).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color(0xFFFF8A80), fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text("OK to try again  ·  Back to exit", color = EmberfinColors.Muted, fontSize = 16.sp)
    }
}

fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
