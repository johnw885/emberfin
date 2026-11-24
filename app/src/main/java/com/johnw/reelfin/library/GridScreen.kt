package com.johnw.reelfin.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.johnw.reelfin.browse.BrowseViewModel
import com.johnw.reelfin.browse.LocalAutoFocus
import com.johnw.reelfin.data.ActiveSession
import com.johnw.reelfin.data.LibraryQuery
import com.johnw.reelfin.data.LibraryRepository
import com.johnw.reelfin.data.LibrarySort
import com.johnw.reelfin.ui.Dimens
import com.johnw.reelfin.ui.ItemFormat
import com.johnw.reelfin.ui.LandscapeTile
import com.johnw.reelfin.ui.PosterTile
import com.johnw.reelfin.ui.Reelfin
import com.johnw.reelfin.ui.ReelfinType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.model.api.BaseItemDto

enum class GridKind(val title: String) { MyList("My List"), HomeVideos("Home Videos") }

data class SimpleGridState(val items: List<BaseItemDto> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** My List (favourites, posters) or Home Videos (newest first, 16:9 stills). */
class GridViewModel(library: LibraryRepository, session: ActiveSession, val kind: GridKind) : BrowseViewModel(library, session) {
    private val _state = MutableStateFlow(SimpleGridState())
    val state: StateFlow<SimpleGridState> = _state.asStateFlow()
    var lastFocused = 0

    init { refresh() }

    fun refresh() = launchSafely {
        val items = when (kind) {
            GridKind.MyList -> library.favorites(session, limit = 300)
            GridKind.HomeVideos -> {
                val view = library.views(session).firstOrNull(LibraryRepository::isHomeVideos)
                if (view == null) emptyList()
                else library.page(
                    session,
                    LibraryQuery(parentId = view.id, kinds = LibraryRepository.HomeVideoKinds, sort = LibrarySort.Released),
                    start = 0, limit = 500,
                ).items
            }
        }
        _state.value = SimpleGridState(items, loading = false)
    }

    override fun onError(e: Exception) {
        _state.update { it.copy(loading = false, error = "Couldn't load ${kind.title}: ${e.message}") }
    }
}

@Composable
fun GridScreen(vm: GridViewModel, onOpen: (BaseItemDto) -> Unit, onPlay: (BaseItemDto) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val landscape = vm.kind == GridKind.HomeVideos
    val restore = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(state.items.isNotEmpty()) {
        if (state.items.isNotEmpty() && autoFocus()) runCatching { restore.requestFocus() }
    }
    Column(Modifier.fillMaxSize().padding(top = 74.dp)) {
        Text(vm.kind.title, style = ReelfinType.headline, color = Reelfin.colors.onBackground, modifier = Modifier.padding(start = Dimens.ScreenPadding))
        when {
            state.error != null -> Message(state.error!!)
            !state.loading && state.items.isEmpty() -> Message(
                if (vm.kind == GridKind.MyList) "Nothing in My List yet. Add titles with the + My List button." else "No home videos yet.",
            )
        }
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(if (landscape) 4 else 7),
            contentPadding = PaddingValues(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 14.dp, bottom = 60.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.TileGap),
            verticalArrangement = Arrangement.spacedBy(if (landscape) 14.dp else 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(state.items, key = { _, it -> it.id.toString() }) { i, item ->
                val m = if (i == vm.lastFocused.coerceAtMost(state.items.lastIndex)) Modifier.focusRequester(restore) else Modifier
                if (landscape) {
                    LandscapeTile(
                        art = ItemFormat.landscape(vm.session.api, item), title = item.name.orEmpty(),
                        subtitle = item.premiereDate?.toLocalDate()?.toString(), progress = ItemFormat.progress(item),
                        onClick = { onPlay(item) }, onFocus = { vm.lastFocused = i }, modifier = m,
                    )
                } else {
                    PosterTile(
                        art = ItemFormat.poster(vm.session.api, item), title = ItemFormat.title(item),
                        progress = ItemFormat.progress(item), played = ItemFormat.played(item), showCaption = true,
                        onClick = { onOpen(item) }, onFocus = { vm.lastFocused = i }, modifier = m,
                    )
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.padding(start = Dimens.ScreenPadding, top = 16.dp)) {
        Text(text, style = ReelfinType.body, color = Reelfin.colors.muted)
    }
}
