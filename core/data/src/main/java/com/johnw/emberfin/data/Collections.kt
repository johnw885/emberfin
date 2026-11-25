package com.johnw.emberfin.data

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDate
import java.time.Month

/**
 * Collections (box sets) shown as one tile, the way Jellyfin's "group movies into collections"
 * option works: a row's members of a collection are replaced by the collection, at the first
 * member's place. Titles outside any collection are left alone.
 */
object CollectionGrouping {
    /**
     * [membership] maps a title's id to the collection it belongs to. A collection replaces its
     * members only when at least [minMembers] of them are in [items]; otherwise they stay as they
     * are. Duplicates (a collection reached twice, or a title listed twice) are dropped.
     */
    fun collapse(items: List<BaseItemDto>, membership: Map<UUID, BaseItemDto>, minMembers: Int = 1): List<BaseItemDto> {
        if (membership.isEmpty()) return items.distinctBy { it.id }
        val counts = items.distinctBy { it.id }.mapNotNull { membership[it.id]?.id }.groupingBy { it }.eachCount()
        val seen = HashSet<UUID>()
        return items.mapNotNull { item ->
            val set = membership[item.id]
            val shown = if (set != null && (counts[set.id] ?: 0) >= minMembers) set else item
            shown.takeIf { seen.add(it.id) }
        }
    }
}

/**
 * The seasonal "Christmas Movies" row on Home.
 *
 * When: November 15 through January 1, inclusive, by the TV's local date.
 *
 * What: movies and series tagged `christmas` (Jellyfin stores TMDB keywords as tags, and TMDB
 * gives real Christmas films that keyword) or with a "Christmas" genre (other metadata sources,
 * or a tag/genre added by hand in Jellyfin's metadata editor to include a title TMDB misses).
 * Both matches are case-insensitive on the server.
 *
 * Collections: a collection shows as one tile only when at least two of its members are
 * Christmas titles; a lone Christmas title from a franchise (one Christmas sequel, say) shows on
 * its own, so the row doesn't pull in the franchise's other films.
 */
object ChristmasSeason {
    const val TITLE = "Christmas Movies"
    const val TAG = "christmas"
    const val GENRE = "Christmas"
    const val MIN_COLLECTION_MEMBERS = 2

    fun isOn(date: LocalDate): Boolean = when (date.month) {
        Month.NOVEMBER -> date.dayOfMonth >= 15
        Month.DECEMBER -> true
        Month.JANUARY -> date.dayOfMonth == 1
        else -> false
    }
}
