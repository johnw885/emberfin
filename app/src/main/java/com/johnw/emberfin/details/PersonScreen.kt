package com.johnw.emberfin.details

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.johnw.emberfin.nav.AppNavigator
import com.johnw.emberfin.ui.Dimens
import com.johnw.emberfin.ui.ItemFormat
import com.johnw.emberfin.ui.PosterTile
import com.johnw.emberfin.ui.Emberfin
import com.johnw.emberfin.ui.EmberfinType
import com.johnw.emberfin.ui.RemoteImage
import com.johnw.emberfin.ui.avatarColor
import com.johnw.emberfin.ui.initials
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** A person: photo, name, birth details, biography, and their titles in this library. */
@Composable
fun PersonScreen(personId: String, nav: AppNavigator, vm: PersonViewModel = koinViewModel(key = "person/$personId") { parametersOf(personId) }) {
    val person by vm.person.collectAsStateWithLifecycle()
    val titles by vm.titles.collectAsStateWithLifecycle()
    val restore = remember { FocusRequester() }
    BackHandler { nav.back() }
    LaunchedEffect(titles?.isNotEmpty() == true) { if (titles?.isNotEmpty() == true) runCatching { restore.requestFocus() } }
    val api = vm.session.api

    LazyVerticalGrid(
        columns = GridCells.Fixed(7),
        modifier = Modifier.fillMaxSize().background(Emberfin.colors.background),
        contentPadding = PaddingValues(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 48.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.TileGap),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            val p = person
            Row(Modifier.padding(bottom = 18.dp)) {
                val name = p?.name.orEmpty()
                Box(
                    Modifier.size(132.dp, 176.dp).clip(RoundedCornerShape(8.dp)).background(avatarColor(name)),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = p?.let { ItemFormat.poster(api, it) }
                    if (art != null) RemoteImage(art, contentDescription = name, modifier = Modifier.fillMaxSize())
                    else Text(initials(name), style = EmberfinType.display, color = androidx.compose.ui.graphics.Color.White)
                }
                Spacer(Modifier.width(24.dp))
                Column {
                    Text(name, style = EmberfinType.headline, color = Emberfin.colors.onBackground)
                    val born = listOfNotNull(
                        p?.premiereDate?.toLocalDate()?.let { "Born $it" },
                        p?.productionLocations?.firstOrNull(),
                    ).joinToString("  ·  ")
                    if (born.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(born, style = EmberfinType.meta, color = Emberfin.colors.muted)
                    }
                    p?.overview?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, style = EmberfinType.body, color = Emberfin.colors.onBackground.copy(alpha = 0.85f), maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 600.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        titles?.let { if (it.isEmpty()) "Nothing in your library yet." else "In your library" } ?: "Loading…",
                        style = EmberfinType.rowTitle, color = Emberfin.colors.onBackground,
                    )
                }
            }
        }
        itemsIndexed(titles.orEmpty(), key = { _, it -> it.id.toString() }) { i, item ->
            PosterTile(
                art = ItemFormat.poster(api, item), title = ItemFormat.title(item), played = ItemFormat.played(item), showCaption = true,
                onClick = { nav.details(item.id.toString()) }, onFocus = { vm.lastFocused = i },
                modifier = if (i == vm.lastFocused) Modifier.focusRequester(restore) else Modifier,
            )
        }
    }
}
