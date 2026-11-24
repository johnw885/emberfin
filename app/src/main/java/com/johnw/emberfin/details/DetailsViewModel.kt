package com.johnw.emberfin.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnw.emberfin.data.ActiveSession
import com.johnw.emberfin.data.LibraryRepository
import com.johnw.emberfin.data.Thumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUID

data class DetailsState(
    /** The movie or series the page is about (for an episode, its series). */
    val title: BaseItemDto? = null,
    /** What Play/Resume plays: the movie, the episode that was opened, or the series' next episode. */
    val playTarget: BaseItemDto? = null,
    val seasons: List<BaseItemDto> = emptyList(),
    val seasonIndex: Int = 0,
    val episodes: List<BaseItemDto> = emptyList(),
    val similar: List<BaseItemDto> = emptyList(),
    val trailerId: String? = null,
    val inMyList: Boolean = false,
    val thumbs: Thumbs = Thumbs.None,
    /** What a collection or playlist holds. */
    val children: List<BaseItemDto> = emptyList(),
    val error: String? = null,
)

class DetailsViewModel(
    private val library: LibraryRepository,
    val session: ActiveSession,
    private val itemId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(DetailsState())
    val state: StateFlow<DetailsState> = _state.asStateFlow()
    private var episodesJob: Job? = null

    /** Key of the element focused last ("play", "ep:<id>", "sim:<id>", "cast:<id>"), restored on return. */
    var focusKey: String = "play"

    init { load() }

    /** Called on first load and when coming back from the player (progress and Next Up changed). */
    fun load() {
        viewModelScope.launch {
            try {
                val opened = library.item(session, itemId.toUUID())
                val title = if (opened.type == BaseItemKind.EPISODE) opened.seriesId?.let { library.item(session, it) } ?: opened else opened
                val isSeries = title.type == BaseItemKind.SERIES
                if (title.type == BaseItemKind.BOX_SET || title.type == BaseItemKind.PLAYLIST) {
                    loadContainer(title)
                    return@launch
                }
                coroutineScope {
                    val similar = async { runCatching { library.similar(session, title.id) }.getOrDefault(emptyList()) }
                    val trailers = async { if ((title.localTrailerCount ?: 0) > 0) runCatching { library.localTrailers(session, title.id) }.getOrNull() else null }
                    val seasons = if (isSeries) runCatching { library.seasons(session, title.id) }.getOrDefault(emptyList()) else emptyList()
                    val target = when {
                        !isSeries -> title
                        opened.type == BaseItemKind.EPISODE && _state.value.playTarget == null -> opened
                        else -> runCatching { library.nextUp(session, limit = 1, seriesId = title.id).firstOrNull() }.getOrNull()
                    }
                    val seasonIndex = target?.seasonId?.let { sid -> seasons.indexOfFirst { it.id == sid } }?.takeIf { it >= 0 }
                        ?: _state.value.seasonIndex.coerceAtMost((seasons.size - 1).coerceAtLeast(0))
                    _state.update {
                        it.copy(
                            title = title, seasons = seasons, seasonIndex = seasonIndex,
                            similar = similar.await(), trailerId = trailers.await()?.firstOrNull()?.id?.toString(),
                            inMyList = title.userData?.isFavorite == true, thumbs = Thumbs.of(title.userData), error = null,
                            playTarget = target ?: it.playTarget,
                        )
                    }
                    if (isSeries && seasons.isNotEmpty()) loadEpisodes(seasonIndex, pickTarget = target == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = com.johnw.emberfin.home.loadError("this title", e)) }
            }
        }
    }

    /** A collection or playlist: its items, and Play starts the first one not watched yet. */
    private suspend fun loadContainer(title: BaseItemDto) {
        val children = library.children(session, title)
        val target = children.firstOrNull { it.userData?.played != true } ?: children.firstOrNull()
        _state.update {
            it.copy(
                title = title, children = children, playTarget = target,
                inMyList = title.userData?.isFavorite == true, thumbs = Thumbs.of(title.userData), error = null,
            )
        }
    }

    fun setThumbs(thumbs: Thumbs) {
        val title = _state.value.title ?: return
        val before = _state.value.thumbs
        _state.update { it.copy(thumbs = thumbs) }
        viewModelScope.launch {
            runCatching { library.setThumbs(session, title.id, thumbs) }
                .onSuccess { data -> _state.update { it.copy(thumbs = Thumbs.of(data)) } }
                .onFailure { if (it !is CancellationException) _state.update { s -> s.copy(thumbs = before) } }
        }
    }

    fun selectSeason(index: Int) {
        if (index == _state.value.seasonIndex && _state.value.episodes.isNotEmpty()) return
        _state.update { it.copy(seasonIndex = index) }
        loadEpisodes(index, pickTarget = false)
    }

    private fun loadEpisodes(index: Int, pickTarget: Boolean) {
        val st = _state.value
        val series = st.title ?: return
        val season = st.seasons.getOrNull(index) ?: return
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            val eps = runCatching { library.episodes(session, series.id, season.id) }.getOrDefault(emptyList())
            _state.update { s ->
                if (s.seasonIndex != index) s
                else s.copy(episodes = eps, playTarget = if (pickTarget && s.playTarget == null) eps.firstOrNull() else s.playTarget)
            }
        }
    }

    fun toggleMyList() {
        val title = _state.value.title ?: return
        val target = !_state.value.inMyList
        _state.update { it.copy(inMyList = target) }
        viewModelScope.launch {
            runCatching { library.setFavorite(session, title.id, target) }
                .onSuccess { v -> _state.update { it.copy(inMyList = v) } }
                .onFailure { if (it !is CancellationException) _state.update { s -> s.copy(inMyList = !target) } }
        }
    }
}

class PersonViewModel(private val library: LibraryRepository, val session: ActiveSession, personId: String) : ViewModel() {
    private val _person = MutableStateFlow<BaseItemDto?>(null)
    val person: StateFlow<BaseItemDto?> = _person.asStateFlow()
    private val _titles = MutableStateFlow<List<BaseItemDto>?>(null)
    val titles: StateFlow<List<BaseItemDto>?> = _titles.asStateFlow()
    var lastFocused = 0

    init {
        viewModelScope.launch {
            val id = personId.toUUID()
            _person.value = runCatching { library.item(session, id) }.getOrNull()
            _titles.value = runCatching { library.filmography(session, id) }.getOrDefault(emptyList())
        }
    }
}
