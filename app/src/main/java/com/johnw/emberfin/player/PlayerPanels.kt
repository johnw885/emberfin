package com.johnw.emberfin.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.johnw.emberfin.ui.Art
import com.johnw.emberfin.ui.EmberfinColors
import com.johnw.emberfin.ui.ProgressBar
import com.johnw.emberfin.ui.RemoteImage

private val PanelScrim = Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000), Color(0xF2000000)))

/** Which row of the Episodes panel the D-pad is on. */
enum class EpisodesRow { SEASONS, EPISODES }

/**
 * The Episodes panel, over the bottom of the playing video: season tabs, then the season's
 * episodes (still, number, title, length, progress). The video keeps playing above it.
 */
@Composable
fun BoxScope.EpisodesPanelView(panel: EpisodesPanel, row: EpisodesRow, episode: Int) {
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .background(PanelScrim)
            .padding(top = 80.dp, bottom = 28.dp),
    ) {
        Text("Episodes", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 56.dp))
        Spacer(Modifier.height(10.dp))
        if (panel.seasons.size > 1) {
            Row(Modifier.padding(start = 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                panel.seasons.forEachIndexed { i, s ->
                    val selected = i == panel.season
                    val focused = selected && row == EpisodesRow.SEASONS
                    Box(
                        Modifier
                            .background(if (focused) Color.White else if (selected) Color(0x44FFFFFF) else Color.Transparent, RoundedCornerShape(6.dp))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(s.name, color = if (focused) Color.Black else if (selected) Color.White else EmberfinColors.Muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        val listState = rememberLazyListState()
        LaunchedEffect(episode, panel.episodes.size) {
            if (panel.episodes.isNotEmpty()) listState.animateScrollToItem((episode - 1).coerceAtLeast(0))
        }
        if (panel.loading && panel.episodes.isEmpty()) {
            Text("Loading…", color = EmberfinColors.Muted, fontSize = 16.sp, modifier = Modifier.padding(start = 56.dp, top = 40.dp, bottom = 60.dp))
        } else {
            LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(panel.episodes, key = { _, e -> e.id }) { i, e ->
                    EpisodeCardView(e, focused = row == EpisodesRow.EPISODES && i == episode)
                }
            }
        }
    }
}

@Composable
private fun EpisodeCardView(e: EpisodeCard, focused: Boolean) {
    Column(Modifier.width(232.dp)) {
        Thumb(e.art, focused, width = 232.dp) {
            e.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomStart).fillMaxWidth()) }
            if (e.current) Badge("Now playing", Modifier.align(Alignment.TopStart))
            else if (e.played) Badge("Watched", Modifier.align(Alignment.TopStart))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            listOf(e.number, e.title).filter { it.isNotBlank() }.joinToString(". "),
            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        e.runtime?.let { Text(it, color = EmberfinColors.Muted, fontSize = 13.sp) }
        if (focused) {
            e.overview?.let { Text(it, color = EmberfinColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** A 16:9 still with the player's own focus look (white border, slight zoom). */
@Composable
private fun Thumb(art: Art?, focused: Boolean, width: Dp, content: @Composable BoxScope.() -> Unit = {}) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .width(width)
            .aspectRatio(16f / 9f)
            .scale(if (focused) 1.06f else 1f)
            .clip(shape)
            .background(Color(0xFF1B1D22))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape),
    ) {
        RemoteImage(art, contentDescription = null, modifier = Modifier.fillMaxSize())
        content()
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.padding(6.dp).background(Color(0xCC000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Post-play "More like this". Over the credits it sits at the bottom with the credits still
 * playing (Back hides it); after the end it fills the screen. OK opens the focused title's page.
 */
@Composable
fun BoxScope.PostPlayView(post: PostPlay, focus: Int) {
    val listState = rememberLazyListState()
    LaunchedEffect(focus) { listState.animateScrollToItem((focus - 1).coerceAtLeast(0)) }
    Column(
        Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .then(if (post.ended) Modifier.fillMaxSize().background(Color(0xFF0E0F11)) else Modifier.background(PanelScrim))
            .padding(top = if (post.ended) 0.dp else 80.dp, bottom = 32.dp),
        verticalArrangement = if (post.ended) Arrangement.Center else Arrangement.Top,
    ) {
        if (post.ended) {
            Text("You've finished", color = EmberfinColors.Muted, fontSize = 18.sp, modifier = Modifier.padding(start = 56.dp))
            Text(post.title, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.padding(start = 56.dp))
            Spacer(Modifier.height(28.dp))
        }
        Text("More like this", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 56.dp))
        Spacer(Modifier.height(14.dp))
        LazyRow(state = listState, contentPadding = PaddingValues(horizontal = 56.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(post.items, key = { _, c -> c.id }) { i, c ->
                val focused = i == focus
                Column(Modifier.width(232.dp)) {
                    Thumb(c.art, focused, width = 232.dp)
                    Spacer(Modifier.height(8.dp))
                    Text(c.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    c.meta?.let { Text(it, color = EmberfinColors.Muted, fontSize = 13.sp) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            if (post.ended) "OK to see details  ·  Back to browse" else "OK to see details  ·  Back to watch the credits",
            color = EmberfinColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 56.dp),
        )
    }
}

/** "Are you still watching?": playback is paused until the viewer answers. */
@Composable
fun BoxScope.StillWatchingView(name: String, focus: Int) {
    Box(Modifier.fillMaxSize().background(Color(0xEB000000)))
    Column(Modifier.align(Alignment.CenterStart).padding(start = 96.dp, end = 96.dp)) {
        Text("Are you still watching", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("$name?", color = EmberfinColors.Accent, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PromptButton("Continue watching", focus == 0)
            PromptButton("Back to browse", focus == 1)
        }
    }
}

@Composable
private fun PromptButton(label: String, focused: Boolean) {
    Box(
        Modifier
            .background(if (focused) Color.White else Color(0x33FFFFFF), RoundedCornerShape(6.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}
