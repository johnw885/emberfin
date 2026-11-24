package com.johnw.reelfin.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnw.reelfin.data.ActiveSession
import com.johnw.reelfin.data.LibraryRepository
import com.johnw.reelfin.data.isUnreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** One key of the on-screen keyboard. */
sealed interface SearchKey {
    data class Char(val c: kotlin.Char) : SearchKey
    data object Space : SearchKey
    data object Delete : SearchKey
    data object Clear : SearchKey
}

/** Netflix-style keyboard: a–z then 1–0 in rows of six, and what each key does to the query. Pure, so it's unit-tested. */
object SearchKeyboard {
    const val COLUMNS = 6
    val letters: List<SearchKey.Char> = (('a'..'z') + ('1'..'9') + '0').map { SearchKey.Char(it) }

    fun apply(query: String, key: SearchKey): String = when (key) {
        is SearchKey.Char -> (query + key.c).take(MAX_LENGTH)
        SearchKey.Space -> if (query.isEmpty() || query.endsWith(' ')) query else (query + ' ').take(MAX_LENGTH)
        SearchKey.Delete -> query.dropLast(1)
        SearchKey.Clear -> ""
    }

    /** What the server is asked for: the trimmed query, or null (show suggestions) when it's blank. */
    fun term(query: String): String? = query.trim().replace(Regex("\\s+"), " ").ifEmpty { null }

    const val MAX_LENGTH = 40
}

data class SearchState(
    val query: String = "",
    /** Movies and series (posters). */
    val titles: List<BaseItemDto> = emptyList(),
    /** Episodes and home videos (stills). */
    val episodes: List<BaseItemDto> = emptyList(),
    /** True while nothing is typed: [titles] are "Recently added" suggestions. */
    val suggestions: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
)

/** Search with a 300 ms debounce; an empty query shows recently added titles. */
@OptIn(FlowPreview::class)
class SearchViewModel(private val library: LibraryRepository, val session: ActiveSession) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** Where focus was when the user left for Details, so Back lands on the same tile. */
    var lastResult: String? = null

    init {
        viewModelScope.launch {
            query.map { SearchKeyboard.term(it) }
                .distinctUntilChanged()
                .debounce { if (it == null) 0L else DEBOUNCE_MS }
                .collectLatest { term -> load(term) }
        }
    }

    fun press(key: SearchKey) = setQuery(SearchKeyboard.apply(query.value, key))

    fun setQuery(text: String) {
        val q = text.take(SearchKeyboard.MAX_LENGTH)
        query.value = q
        _state.update { it.copy(query = q) }
    }

    fun retry() = viewModelScope.launch { load(SearchKeyboard.term(query.value)) }

    private suspend fun load(term: String?) {
        _state.update { it.copy(loading = true, error = null) }
        try {
            if (term == null) {
                val recent = library.recentlyAdded(session)
                _state.update { it.copy(titles = recent, episodes = emptyList(), suggestions = true, loading = false) }
            } else {
                val found = library.search(session, term)
                val (titles, others) = found.partition { it.type == BaseItemKind.MOVIE || it.type == BaseItemKind.SERIES }
                _state.update { it.copy(titles = titles, episodes = others, suggestions = false, loading = false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = if (e.isUnreachable()) "Can't reach the server." else "Search failed (${e.message})."
            _state.update { it.copy(loading = false, error = msg) }
        }
    }

    companion object {
        const val DEBOUNCE_MS = 300L
    }
}
