package com.johnw.reelfin.home

import androidx.lifecycle.viewModelScope
import com.johnw.reelfin.browse.Billboard
import com.johnw.reelfin.browse.BrowseViewModel
import com.johnw.reelfin.browse.RowStyle
import com.johnw.reelfin.browse.RowUi
import com.johnw.reelfin.data.ActiveSession
import com.johnw.reelfin.data.CachedRow
import com.johnw.reelfin.data.LibraryRepository
import com.johnw.reelfin.data.RowCache
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate

data class HomeState(
    val billboard: BaseItemDto? = null,
    val rows: List<RowUi> = emptyList(),
    val loading: Boolean = true,
    val fromCache: Boolean = false,
    val error: String? = null,
)

/**
 * Home: the billboard and the rows. The cached copy (if any) is shown first, then every row is
 * fetched in parallel and the screen and cache are replaced with the fresh result.
 */
class HomeViewModel(
    library: LibraryRepository,
    session: ActiveSession,
    private val cache: RowCache,
) : BrowseViewModel(library, session) {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    private val cacheKey = "home/${session.account.key}"

    init {
        viewModelScope.launch {
                cache.load(cacheKey)?.let { cached ->
                if (_state.value.rows.isEmpty()) {
                    _state.value = HomeState(
                        billboard = cached.billboard,
                        rows = cached.rows.map { RowUi(it.key, it.title, RowStyle.valueOf(it.style), it.items) },
                        loading = true, fromCache = true,
                    )
                }
            }
            refresh()
        }
    }

    fun refresh() = launchSafely {
        val (featured, rows) = load()
        val pick = Billboard.pick(_state.value.billboard, featured)
        _state.value = HomeState(billboard = pick.show, rows = rows, loading = false)
        cache.save(cacheKey, pick.next, rows.map { CachedRow(it.key, it.title.orEmpty(), it.style.name, it.items) })
    }

    override fun onError(e: Exception) {
        _state.update { it.copy(loading = false, error = if (it.rows.isEmpty()) "Couldn't load your library: ${e.message}" else null) }
    }

    override fun onMyListChanged() {
        // My List is a row on Home: refresh it quietly.
        launchSafely {
            val mine = library.favorites(session)
            _state.update { st ->
                val others = st.rows.filterNot { it.key == KEY_MY_LIST }
                val idx = st.rows.indexOfFirst { it.key == KEY_MY_LIST }.takeIf { it >= 0 } ?: minOf(2, others.size)
                val rows = if (mine.isEmpty()) others else others.toMutableList().apply { add(minOf(idx, size), RowUi(KEY_MY_LIST, "My List", RowStyle.Poster, mine)) }
                st.copy(rows = rows)
            }
        }
    }

    private suspend fun load(): Pair<List<BaseItemDto>, List<RowUi>> = coroutineScope {
        val s = session
        val views = library.views(s)
        val featured = async { runCatching { library.featured(s, listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)) }.getOrDefault(emptyList()) }
        val resume = async { row(KEY_RESUME, "Continue Watching", RowStyle.Landscape) { library.resume(s) } }
        val nextUp = async { row(KEY_NEXT_UP, "Next Up", RowStyle.Landscape) { library.nextUp(s) } }
        val myList = async { row(KEY_MY_LIST, "My List", RowStyle.Poster) { library.favorites(s) } }
        val latest = views.filter { LibraryRepository.isMovies(it) || LibraryRepository.isShows(it) || LibraryRepository.isHomeVideos(it) }
            .map { v ->
                async {
                    val style = if (LibraryRepository.isHomeVideos(v)) RowStyle.Landscape else RowStyle.Poster
                    row("latest/${v.id}", "New in ${v.name}", style) { library.latest(s, v.id) }
                }
            }
        val genres = async {
            val kinds = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
            val all = runCatching { library.genres(s, kinds) }.getOrDefault(emptyList())
            val top = all.sortedByDescending { (it.movieCount ?: 0) + (it.seriesCount ?: 0) + (it.childCount ?: 0) }.take(8)
            // A different trio each day, stable within the day.
            val seed = LocalDate.now().toEpochDay().toInt()
            top.shuffled(kotlin.random.Random(seed)).take(3).map { g ->
                async { row("genre/${g.id}", g.name.orEmpty(), RowStyle.Poster) { library.byGenre(s, g.id, kinds) } }
            }.awaitAll()
        }
        val rows = listOfNotNull(resume.await(), nextUp.await(), myList.await()) +
            latest.awaitAll().filterNotNull() + genres.await().filterNotNull()
        featured.await() to rows
    }

    private suspend fun row(key: String, title: String, style: RowStyle, fetch: suspend () -> List<BaseItemDto>): RowUi? =
        runCatching { fetch() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { RowUi(key, title, style, it) }

    companion object {
        const val KEY_RESUME = "resume"
        const val KEY_NEXT_UP = "nextup"
        const val KEY_MY_LIST = "mylist"
    }
}
