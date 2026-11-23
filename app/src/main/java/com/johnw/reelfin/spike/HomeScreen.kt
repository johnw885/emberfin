package com.johnw.reelfin.spike

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.johnw.reelfin.Screen
import com.johnw.reelfin.SpikeViewModel
import com.johnw.reelfin.ui.ReelfinColors
import org.jellyfin.sdk.model.api.BaseItemDto

private const val TILE_WIDTH_PX = 640

@Composable
fun HomeScreen(
    state: Screen.Home,
    imageUrl: (BaseItemDto, Int) -> String?,
    onPlay: (BaseItemDto) -> Unit,
    onSignOut: () -> Unit,
) {
    val firstTile = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(top = 48.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Reelfin", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = ReelfinColors.Accent)
            Spacer(Modifier.weight(1f))
            Button(onClick = onSignOut) { Text("Sign out") }
        }
        Spacer(Modifier.height(40.dp))
        state.error?.let {
            Text(it, color = Color(0xFFFF8A80), fontSize = 18.sp, modifier = Modifier.padding(horizontal = 48.dp))
            Spacer(Modifier.height(16.dp))
        }
        val row = state.row
        if (row == null) {
            if (state.error == null) {
                Text("Loading...", color = ReelfinColors.Muted, fontSize = 20.sp, modifier = Modifier.padding(horizontal = 48.dp))
            }
            return@Column
        }
        Text(
            row.title,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = ReelfinColors.OnBackground,
            modifier = Modifier.padding(horizontal = 48.dp),
        )
        Spacer(Modifier.height(12.dp))
        if (row.items.isEmpty()) {
            Text("Nothing here yet.", color = ReelfinColors.Muted, modifier = Modifier.padding(horizontal = 48.dp))
            return@Column
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 16.dp)) {
            itemsIndexed(row.items, key = { _, item -> item.id }) { index, item ->
                Tile(
                    item = item,
                    imageUrl = imageUrl(item, TILE_WIDTH_PX),
                    onClick = { onPlay(item) },
                    modifier = Modifier
                        .padding(end = 20.dp)
                        .then(if (index == 0) Modifier.focusRequester(firstTile) else Modifier),
                )
            }
        }
        LaunchedEffect(row) { runCatching { firstTile.requestFocus() } }
    }
}

@Composable
private fun Tile(item: BaseItemDto, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(320.dp)) {
        Card(
            onClick = onClick,
            scale = CardDefaults.scale(focusedScale = 1.08f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = RoundedCornerShape(8.dp)),
            ),
            shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        ) {
            Box(Modifier.fillMaxSize().background(ReelfinColors.Surface)) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = item.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                val pct = item.userData?.playedPercentage
                if (pct != null && pct > 0) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0x66FFFFFF))) {
                        Box(Modifier.fillMaxWidth((pct / 100).toFloat().coerceIn(0f, 1f)).height(4.dp).background(ReelfinColors.Accent))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            SpikeViewModel.displayTitle(item),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 16.sp,
            color = ReelfinColors.OnBackground,
        )
    }
}
