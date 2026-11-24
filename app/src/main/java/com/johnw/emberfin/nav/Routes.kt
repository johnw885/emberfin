package com.johnw.emberfin.nav

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose routes. */
@Serializable data object StartRoute
@Serializable data object ProfilesRoute
/** [expiredUser]: set when the server rejected a saved sign-in, so the screen can say why it's here. */
@Serializable data class AddProfileRoute(val firstRun: Boolean = false, val expiredUser: String? = null)
@Serializable data object MainRoute
@Serializable data class DetailsRoute(val itemId: String)
@Serializable data class PersonRoute(val personId: String)

/**
 * [startMs] -1 = resume from the server's position, 0 = from the start. [queue] (optional) is the
 * whole play list with [itemId] in it, e.g. from a phone's "play on". [audio]/[subtitle] are stream
 * indexes asked for by a remote client ([NO_INDEX] = choose as usual, -1 subtitle = off).
 */
@Serializable data class PlayerRoute(
    val itemId: String,
    val startMs: Long = -1,
    val queue: List<String> = emptyList(),
    val audio: Int = NO_INDEX,
    val subtitle: Int = NO_INDEX,
) {
    companion object {
        const val NO_INDEX = -100
    }
}

@Serializable data object SearchRoute
@Serializable data object SettingsRoute
@Serializable data object LicensesRoute
