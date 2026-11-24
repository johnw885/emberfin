package com.johnw.emberfin.home

import androidx.lifecycle.viewModelScope
import com.johnw.emberfin.browse.Billboard
import com.johnw.emberfin.browse.BrowseViewModel
import com.johnw.emberfin.browse.RowStyle
import com.johnw.emberfin.browse.RowUi
import com.johnw.emberfin.data.ActiveSession
import com.johnw.emberfin.data.CachedRow
import com.johnw.emberfin.data.LibraryRepository
import com.johnw.emberfin.data.Recommendations
import com.johnw.emberfin.data.SessionManager
import kotlinx.coroutines.flow.first
import com.johnw.emberfin.data.RowCache
import com.johnw.emberfin.data.isUnreachable
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
    private val sessions: SessionManager,
) : BrowseViewModel(library, session) {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()
    /** Kids mode (read on every refresh, so turning it on or off in Settings applies on return). */
    private var kids = false
    private val cacheKey get() = "home/${session.account.key}" + if (kids) "/kids" else ""

    init {
        viewModelScope.launch {
            kids = sessions.lock(session.account).first().kids
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
        val wasKids = kids
        kids = sessions.lock(session.account).first().kids
        if (kids != wasKids) _state.value = HomeState()
        val (featured, rows) = if (kids) loadKids() else load()
        val pick = Billboard.pick(_state.value.billboard, featured)
        _state.value = HomeState(billboard = pick.show, rows = rows, loading = false)
        cache.save(cacheKey, pick.next, rows.map { CachedRow(it.key, it.title.orEmpty(), it.style.name, it.items) })
    }

    override fun onError(e: Exception) {
        _state.update { it.copy(loading = false, error = if (it.rows.isEmpty()) loadError("your library", e) else null) }
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
        val featured = async { optional(emptyList()) { library.featured(s, listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)) } }
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
        val genres = async { genreRows { true } }
        // "Because you watched…": the server's movie recommendations, and titles like the series played last.
        val recs = async {
            Recommendations.pick(optional(emptyList()) { library.movieRecommendations(s) }).map { (title, r) ->
                RowUi("rec/${r.categoryId}", title, RowStyle.Poster, r.items.orEmpty())
            }
        }
        val seriesLike = async {
            optional(null) { library.lastPlayedSeries(s) }?.let { (id, name) ->
                row("because/$id", "Because you watched $name", RowStyle.Poster) { library.similar(s, id).filter { it.id != id } }
                    ?.takeIf { it.items.size >= 3 }
            }
        }
        val collections = async { row(KEY_COLLECTIONS, "Collections", RowStyle.Poster) { library.collections(s) } }
        val playlists = async { row(KEY_PLAYLISTS, "Your Playlists", RowStyle.Poster) { library.playlists(s) } }
        val latestRows = latest.awaitAll().filterNotNull()
        val recRows = recs.await()
        // Netflix-style mix: personal rows between the "New in…" rows rather than all at the end.
        val rows = buildList {
            addAll(listOfNotNull(resume.await(), nextUp.await(), myList.await()))
            recRows.firstOrNull()?.let(::add)
            latestRows.firstOrNull()?.let(::add)
            seriesLike.await()?.let(::add)
            addAll(latestRows.drop(1))
            addAll(recRows.drop(1))
            addAll(listOfNotNull(collections.await(), playlists.await()))
            addAll(genres.await())
        }
        featured.await() to rows
    }

    /**
     * Kids mode: a simpler Home with no home videos and no recommendations, and only family
     * genres. The server's parental rating limit for the user is what actually filters titles.
     */
    private suspend fun loadKids(): Pair<List<BaseItemDto>, List<RowUi>> = coroutineScope {
        val s = session
        val views = library.views(s).filter { LibraryRepository.isMovies(it) || LibraryRepository.isShows(it) }
        val featured = async { optional(emptyList()) { library.featured(s, listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)) } }
        val resume = async { row(KEY_RESUME, "Continue Watching", RowStyle.Landscape) { library.resume(s) } }
        val myList = async { row(KEY_MY_LIST, "My List", RowStyle.Poster) { library.favorites(s) } }
        val latest = views.map { v -> async { row("latest/${v.id}", "New in ${v.name}", RowStyle.Poster) { library.latest(s, v.id) } } }
        val genres = async { genreRows { name -> KidsGenres.any { name.contains(it, ignoreCase = true) } } }
        val rows = listOfNotNull(resume.await(), myList.await()) + latest.awaitAll().filterNotNull() + genres.await()
        featured.await() to rows
    }

    /** Three genre rows from the eight biggest genres that pass [allow], a different trio each day. */
    private suspend fun genreRows(allow: (String) -> Boolean): List<RowUi> = coroutineScope {
        val kinds = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
        val all = optional(emptyList()) { library.genres(session, kinds) }.filter { allow(it.name.orEmpty()) }
        val top = all.sortedByDescending { (it.movieCount ?: 0) + (it.seriesCount ?: 0) + (it.childCount ?: 0) }.take(8)
        val seed = LocalDate.now().toEpochDay().toInt()
        top.shuffled(kotlin.random.Random(seed)).take(3).map { g ->
            async { row("genre/${g.id}", g.name.orEmpty(), RowStyle.Poster) { library.byGenre(session, g.id, kinds) } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun row(key: String, title: String, style: RowStyle, fetch: suspend () -> List<BaseItemDto>): RowUi? =
        optional(null) { fetch() }?.takeIf { it.isNotEmpty() }?.let { RowUi(key, title, style, it) }

    companion object {
        const val KEY_RESUME = "resume"
        const val KEY_NEXT_UP = "nextup"
        const val KEY_MY_LIST = "mylist"
        const val KEY_COLLECTIONS = "collections"
        const val KEY_PLAYLISTS = "playlists"
        val KidsGenres = listOf("Animation", "Family", "Kids", "Children", "Adventure", "Fantasy", "Comedy")
    }
}

/** The message a screen shows when its first load fails. */
fun loadError(what: String, e: Exception): String =
    if (e.isUnreachable()) "Can't reach the server, so $what can't load right now."
    else "Couldn't load $what (${e.message ?: e.javaClass.simpleName})."
