package com.johnw.reelfin.nav

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.johnw.reelfin.data.SessionManager
import com.johnw.reelfin.details.DetailsScreen
import com.johnw.reelfin.details.PersonScreen
import com.johnw.reelfin.player.PlayerArgs
import com.johnw.reelfin.player.PlayerScreen
import com.johnw.reelfin.profiles.AddProfileScreen
import com.johnw.reelfin.profiles.ProfilesScreen
import com.johnw.reelfin.shell.MainScreen
import com.johnw.reelfin.shell.PlaceholderScreen
import com.johnw.reelfin.ui.Reelfin
import com.johnw.reelfin.ui.Wordmark
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

/** What screens can ask the app to do. Kept small so screens don't know about routes. */
class AppNavigator(private val nav: NavHostController) {
    fun details(itemId: String) = nav.navigate(DetailsRoute(itemId))
    fun person(personId: String) = nav.navigate(PersonRoute(personId))
    fun play(itemId: String, startMs: Long = PlayerArgs.RESUME) = nav.navigate(PlayerRoute(itemId, startMs))
    fun search() = nav.navigate(SearchRoute) { launchSingleTop = true }
    fun settings() = nav.navigate(SettingsRoute) { launchSingleTop = true }
    // Main stays underneath, so Back returns to it; picking a profile rebuilds Main from scratch.
    fun profiles() = nav.navigate(ProfilesRoute) { launchSingleTop = true }
    fun addProfile() = nav.navigate(AddProfileRoute())
    fun main() = nav.navigate(MainRoute) { popUpTo(0) { inclusive = true } }
    /** Pops one screen, but never the last one (a quick double Back must not empty the stack). */
    fun back() {
        if (nav.previousBackStackEntry != null) nav.popBackStack()
    }
}

@Composable
fun ReelfinNavHost(nav: NavHostController, debugPlay: Flow<PlayerRoute>) {
    val sessions: SessionManager = koinInject()
    val app = androidx.compose.runtime.remember(nav) { AppNavigator(nav) }

    // Debug builds: `am start --es play_item_id …` plays an item once a profile is active.
    LaunchedEffect(Unit) {
        debugPlay.collect { route ->
            sessions.current.first { it != null }
            kotlinx.coroutines.delay(400) // let the start screen hand over to Main first
            nav.navigate(route)
        }
    }

    NavHost(
        navController = nav,
        startDestination = StartRoute,
        modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0E0F11)),
        enterTransition = { fadeIn(tween(220)) },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) },
    ) {
        composable<StartRoute> {
            Splash()
            LaunchedEffect(Unit) {
                val accounts = sessions.restore()
                when {
                    accounts.isEmpty() -> nav.navigate(AddProfileRoute(firstRun = true)) { popUpTo(0) { inclusive = true } }
                    sessions.current.value != null -> app.main()
                    else -> nav.navigate(ProfilesRoute) { popUpTo(0) { inclusive = true } }
                }
            }
        }
        composable<ProfilesRoute> { ProfilesScreen(onOpen = app::main, onAdd = app::addProfile) }
        composable<AddProfileRoute> { entry ->
            val r = entry.toRoute<AddProfileRoute>()
            AddProfileScreen(firstRun = r.firstRun, onDone = app::main, onBack = { if (!r.firstRun) app.back() })
        }
        composable<MainRoute> { MainScreen(app) }
        composable<DetailsRoute> { entry -> DetailsScreen(entry.toRoute<DetailsRoute>().itemId, app) }
        composable<PersonRoute> { entry -> PersonScreen(entry.toRoute<PersonRoute>().personId, app) }
        composable<PlayerRoute>(
            enterTransition = { fadeIn(tween(120)) },
            exitTransition = { fadeOut(tween(120)) },
        ) { entry ->
            val r = entry.toRoute<PlayerRoute>()
            // The player may report "exit" more than once (Back, then ON_STOP or end of media):
            // only pop while this entry is still on top, or Main gets popped too.
            PlayerScreen(PlayerArgs(r.itemId, r.startMs), onExit = { if (nav.currentBackStackEntry?.id == entry.id) nav.popBackStack() })
        }
        composable<SearchRoute> {
            PlaceholderScreen("Search", "Search with the on-screen keyboard or your remote's mic arrives in the next update.", onBack = { app.back() })
        }
        composable<SettingsRoute> {
            PlaceholderScreen("Settings", "Playback, subtitle and account settings arrive in the next update.", onBack = { app.back() })
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize().background(Reelfin.colors.background), contentAlignment = Alignment.Center) {
        Wordmark(size = 44)
    }
}
