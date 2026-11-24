package com.johnw.emberfin.browse

import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * Billboard rotation. The title shown stays put for the whole run (swapping it under the
 * user's focus would be jarring); the cache stores a different candidate, so the next launch
 * opens on a new title.
 */
object Billboard {
    data class Pick(val show: BaseItemDto?, val next: BaseItemDto?)

    fun pick(current: BaseItemDto?, featured: List<BaseItemDto>): Pick {
        // Keep the current title, refreshed with the server's latest copy when it's in the list.
        val show = current?.let { c -> featured.firstOrNull { it.id == c.id } ?: c } ?: featured.firstOrNull()
        val next = featured.firstOrNull { it.id != show?.id } ?: show
        return Pick(show, next)
    }
}
