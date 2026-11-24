package com.johnw.reelfin.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.johnw.reelfin.browse.Billboard
import com.johnw.reelfin.browse.BrowseActions
import com.johnw.reelfin.browse.BrowseLayout
import com.johnw.reelfin.browse.BrowseViewModel
import com.johnw.reelfin.browse.RowStyle
import com.johnw.reelfin.browse.RowUi
import com.johnw.reelfin.data.ActiveSession
import com.johnw.reelfin.data.CachedRow
import com.johnw.reelfin.data.LibraryQuery
import com.johnw.reelfin.data.LibraryRepository
import com.johnw.reelfin.data.LibrarySort
import com.johnw.reelfin.data.RowCache
import com.johnw.reelfin.shell.Tab
import com.johnw.reelfin.ui.ActionButton
import com.johnw.reelfin.ui.ChoiceDialog
import com.johnw.reelfin.ui.Dimens
import com.johnw.reelfin.ui.Reelfin
import com.johnw.reelfin.ui.ReelfinType
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto

data class Genre(val id: UUID, val name: String)

data class GridState(
    val query: LibraryQuery? = null,
    val items: List<BaseItemDto> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val genres: List<Genre> = emptyList(),
)

data class LibraryTabState(
    val title: String = "",
    val billboard: BaseItemDto? = null,
    val rows: List<RowUi> = emptyList(),
    val grid: GridState = GridState(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * Shows or Movies: a billboard from that library, "New" and genre rows, then an "All" grid with
 * sort and filters that pages in as focus nears its end.
 */
class LibraryTabViewModel(
    library: LibraryRepository,
    session: ActiveSession,
    private val cache: RowCache,
    val tab: Tab,
) : BrowseViewModel(library, session) {
    private val _state = MutableStateFlow(LibraryTabState(title = tab.label))
    val state: StateFlow<LibraryTabState> = _state.asStateFlow()
    private val kinds = if (tab == Tab.Shows) LibraryRepository.ShowKinds else LibraryRepository.MovieKinds
    private val cacheKey = "tab/${tab.name}/${session.account.key}"
    private var pageJob: Job? = null

    init {
        viewModelScope.launch {
            cache.load(cacheKey)?.let { c ->
                _state.update {
                    if (it.rows.isNotEmpty()) it
                    else it.copy(billboard = c.billboard, rows = c.rows.map { r -> RowUi(r.key, r.title, RowStyle.valueOf(r.style), r.items) })
                }
            }
            refresh()
        }
    }

    fun refresh() = launchSafely {
        val view = library.views(session).firstOrNull { if (tab == Tab.Shows) LibraryRepository.isShows(it) else LibraryRepository.isMovies(it) }
        val parent = view?.id
        coroutineScope {
            val featured = async { optional(emptyList()) { library.featured(session, kinds, parent) } }
            val latest = async { parent?.let { p -> optional(null) { library.latest(session, p) } }.orEmpty() }
            val genres = optional(emptyList()) { library.genres(session, kinds, parent) }
            val genreList = genres.mapNotNull { g -> g.name?.let { Genre(g.id, it) } }
            val top = genres.sortedByDescending { (it.movieCount ?: 0) + (it.seriesCount ?: 0) + (it.childCount ?: 0) }.take(6)
            val genreRows = top.map { g ->
                async {
                    optional(null) { library.byGenre(session, g.id, kinds, parent) }
                        ?.takeIf { it.size >= 3 }?.let { RowUi("genre/${g.id}", g.name.orEmpty(), RowStyle.Poster, it) }
                }
            }
            val rows = listOfNotNull(latest.await().takeIf { it.isNotEmpty() }?.let { RowUi("latest", "New in ${view?.name ?: tab.label}", RowStyle.Poster, it) }) +
                genreRows.awaitAll().filterNotNull()
            val pick = Billboard.pick(_state.value.billboard, featured.await())
            _state.update {
                val q = it.grid.query ?: LibraryQuery(parentId = parent, kinds = kinds)
                it.copy(billboard = pick.show, rows = rows, loading = false, error = null, grid = it.grid.copy(query = q, genres = genreList))
            }
            cache.save(cacheKey, pick.next, rows.map { CachedRow(it.key, it.title.orEmpty(), it.style.name, it.items) })
        }
        reloadGrid()
    }

    override fun onError(e: Exception) {
        _state.update { it.copy(loading = false, error = if (it.rows.isEmpty()) com.johnw.reelfin.home.loadError(tab.label, e) else null) }
    }

    fun setSort(sort: LibrarySort) = updateQuery { it.copy(sort = sort) }
    fun setUnwatched(on: Boolean) = updateQuery { it.copy(unwatchedOnly = on) }
    fun setGenre(genre: Genre?) = updateQuery { it.copy(genreId = genre?.id) }

    private fun updateQuery(change: (LibraryQuery) -> LibraryQuery) {
        val q = _state.value.grid.query ?: return
        _state.update { it.copy(grid = it.grid.copy(query = change(q), items = emptyList(), total = 0)) }
        reloadGrid()
    }

    private fun reloadGrid() {
        pageJob?.cancel()
        _state.update { it.copy(grid = it.grid.copy(items = emptyList(), total = 0)) }
        loadMore()
    }

    /** Next page of the "All" grid (no-op while one is loading or when everything is in). */
    fun loadMore() {
        val g = _state.value.grid
        val q = g.query ?: return
        if (g.loading || (g.total > 0 && g.items.size >= g.total)) return
        _state.update { it.copy(grid = it.grid.copy(loading = true)) }
        pageJob = launchSafely {
            val page = library.page(session, q, start = g.items.size, limit = PAGE)
            _state.update { st ->
                if (st.grid.query != q) st
                else st.copy(grid = st.grid.copy(items = st.grid.items + page.items, total = page.total, loading = false))
            }
        }
    }

    companion object {
        const val PAGE = 70
        const val COLUMNS = 7
    }
}

@Composable
fun LibraryTabScreen(vm: LibraryTabViewModel, actions: BrowseActions) {
    val state by vm.state.collectAsStateWithLifecycle()
    val grid = state.grid
    val gridRows = remember(grid.items) {
        grid.items.chunked(LibraryTabViewModel.COLUMNS).mapIndexed { i, chunk -> RowUi("grid/$i", null, RowStyle.Poster, chunk) }
    }
    BrowseLayout(
        api = vm.session.api,
        billboard = state.billboard,
        rows = state.rows,
        focus = vm.focus,
        actions = actions,
        details = vm::details,
        inMyList = vm::inMyList,
        emptyText = state.error,
        gridHeader = { GridHeader(vm, state) },
        gridRows = gridRows,
        onNearGridEnd = vm::loadMore,
    )
}

@Composable
private fun GridHeader(vm: LibraryTabViewModel, state: LibraryTabState) {
    val q = state.grid.query ?: return
    var dialog by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().padding(start = Dimens.ScreenPadding, top = 18.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("All ${state.title}", style = ReelfinType.title, color = Reelfin.colors.onBackground)
        val count = state.grid.total
        if (count > 0) Text("$count", style = ReelfinType.meta, color = Reelfin.colors.faint)
        Spacer(Modifier.width(12.dp))
        ActionButton("Sort: ${q.sort.label}", onClick = { dialog = "sort" })
        ActionButton(if (q.unwatchedOnly) "Unwatched" else "All titles", onClick = { vm.setUnwatched(!q.unwatchedOnly) })
        val genreName = state.grid.genres.firstOrNull { it.id == q.genreId }?.name ?: "All genres"
        ActionButton(genreName, onClick = { dialog = "genre" })
    }
    when (dialog) {
        "sort" -> ChoiceDialog(
            title = "Sort by",
            options = LibrarySort.entries.map { it.label },
            selected = q.sort.ordinal,
            onPick = { vm.setSort(LibrarySort.entries[it]); dialog = null },
            onDismiss = { dialog = null },
        )
        "genre" -> {
            val genres = state.grid.genres
            ChoiceDialog(
                title = "Genre",
                options = listOf("All genres") + genres.map { it.name },
                selected = genres.indexOfFirst { it.id == q.genreId } + 1,
                onPick = { vm.setGenre(if (it == 0) null else genres[it - 1]); dialog = null },
                onDismiss = { dialog = null },
            )
        }
    }
}
