package com.johnw.reelfin.browse

import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnw.reelfin.data.ActiveSession
import com.johnw.reelfin.data.LibraryRepository
import com.johnw.reelfin.data.isUnreachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * What every browse screen shares: the hero's full-item lookups (for badges), My List toggling
 * and the saved focus position.
 */
abstract class BrowseViewModel(protected val library: LibraryRepository, val session: ActiveSession) : ViewModel() {
    val focus = BrowseFocus()

    /** Full items (media streams for badges), fetched once per item the hero shows. */
    private val detailCache = mutableStateMapOf<String, BaseItemDto>()
    private val pending = mutableSetOf<String>()

    /** Overrides for My List after a toggle, until the next refresh. */
    private val listOverrides = mutableStateMapOf<String, Boolean>()

    fun details(item: BaseItemDto): BaseItemDto? {
        val key = item.id.toString()
        detailCache[key]?.let { return it }
        if (pending.add(key)) {
            viewModelScope.launch {
                runCatching { library.item(session, item.id) }.onSuccess { detailCache[key] = it }
                pending.remove(key)
            }
        }
        return null
    }

    fun inMyList(item: BaseItemDto): Boolean =
        listOverrides[item.id.toString()] ?: detailCache[item.id.toString()]?.userData?.isFavorite ?: item.userData?.isFavorite ?: false

    open fun toggleMyList(item: BaseItemDto) {
        val key = item.id.toString()
        val target = !inMyList(item)
        listOverrides[key] = target
        viewModelScope.launch {
            runCatching { library.setFavorite(session, item.id, target) }
                .onSuccess { listOverrides[key] = it; onMyListChanged() }
                .onFailure { if (it !is CancellationException) listOverrides[key] = !target }
        }
    }

    protected open fun onMyListChanged() {}

    protected fun launchSafely(block: suspend () -> Unit): Job = viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    protected open fun onError(e: Exception) {}

    /**
     * A part of a screen that may fail on its own (one row, the billboard): [default] on an
     * ordinary error. A lost connection is rethrown so the whole refresh fails and keeps what's on
     * screen and in the cache, instead of replacing it with empty rows.
     */
    protected suspend fun <T> optional(default: T, block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isUnreachable()) throw e
        default
    }
}
