package com.johnw.reelfin.nav

import kotlinx.serialization.Serializable

/** Type-safe Navigation Compose routes. */
@Serializable data object StartRoute
@Serializable data object ProfilesRoute
/** [expiredUser]: set when the server rejected a saved sign-in, so the screen can say why it's here. */
@Serializable data class AddProfileRoute(val firstRun: Boolean = false, val expiredUser: String? = null)
@Serializable data object MainRoute
@Serializable data class DetailsRoute(val itemId: String)
@Serializable data class PersonRoute(val personId: String)

/** [startMs] -1 = resume from the server's position, 0 = from the start. */
@Serializable data class PlayerRoute(val itemId: String, val startMs: Long = -1)

@Serializable data object SearchRoute
@Serializable data object SettingsRoute
@Serializable data object LicensesRoute
