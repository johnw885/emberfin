package com.johnw.reelfin.shell

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.johnw.reelfin.browse.BrowseActions
import com.johnw.reelfin.browse.LocalAutoFocus
import com.johnw.reelfin.data.ActiveSession
import com.johnw.reelfin.data.ImageUrls
import com.johnw.reelfin.data.LibraryRepository
import com.johnw.reelfin.home.HomeScreen
import com.johnw.reelfin.home.HomeViewModel
import com.johnw.reelfin.library.GridKind
import com.johnw.reelfin.library.GridScreen
import com.johnw.reelfin.library.GridViewModel
import com.johnw.reelfin.library.LibraryTabScreen
import com.johnw.reelfin.library.LibraryTabViewModel
import com.johnw.reelfin.nav.AppNavigator
import com.johnw.reelfin.ui.Art
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Which tabs exist depends on the libraries the profile can see. */
class MainViewModel(private val library: LibraryRepository, val session: ActiveSession) : ViewModel() {
    private val _tabs = MutableStateFlow(listOf(Tab.Home, Tab.Shows, Tab.Movies, Tab.MyList))
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()

    val profileName: String = session.account.userName.ifBlank { "Profile" }
    val profileArt: Art? = ImageUrls.user(session.api, session.userId, session.account.imageTag)?.let { Art(it.url) }

    init {
        viewModelScope.launch {
            val views = runCatching { library.views(session) }.getOrNull() ?: return@launch
            _tabs.value = buildList {
                add(Tab.Home)
                if (views.any(LibraryRepository::isShows)) add(Tab.Shows)
                if (views.any(LibraryRepository::isMovies)) add(Tab.Movies)
                add(Tab.MyList)
                if (views.any(LibraryRepository::isHomeVideos)) add(Tab.HomeVideos)
            }
        }
    }
}

/**
 * The signed-in shell: top nav over the current tab. Each tab keeps its ViewModel, scroll and
 * focus while you move between tabs. Back from content goes to the nav; Back on another tab
 * goes Home; Back on Home's nav leaves the app.
 */
@Composable
fun MainScreen(nav: AppNavigator, vm: MainViewModel = koinViewModel()) {
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    var current by rememberSaveable { mutableStateOf(Tab.Home) }
    var navFocused by remember { mutableStateOf(false) }
    val navRequester = remember { FocusRequester() }
    val holder = rememberSaveableStateHolder()
    val activity = LocalContext.current as? Activity

    BackHandler {
        when {
            !navFocused -> runCatching { navRequester.requestFocus() }
            current != Tab.Home -> current = Tab.Home
            else -> activity?.finish()
        }
    }

    val actions = remember(nav) {
        BrowseActions(
            open = { item -> openItem(nav, item) },
            // A series has nothing to play directly: its page picks the next episode.
            play = { item -> if (item.type == org.jellyfin.sdk.model.api.BaseItemKind.SERIES) nav.details(item.id.toString()) else nav.play(item.id.toString()) },
            toggleList = {},
        )
    }

    var contentFocused by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().onFocusChanged { contentFocused = it.hasFocus }) {
        CompositionLocalProvider(LocalAutoFocus provides { !navFocused }) {
        holder.SaveableStateProvider(current.name) {
            when (current) {
                Tab.Home -> {
                    val home: HomeViewModel = koinViewModel(key = "home")
                    RefreshOnReturn(home::refresh)
                    HomeScreen(home, actions.withList(home::toggleMyList))
                }
                Tab.Shows, Tab.Movies -> {
                    val lib: LibraryTabViewModel = koinViewModel(key = current.name) { parametersOf(current) }
                    RefreshOnReturn(lib::refresh)
                    LibraryTabScreen(lib, actions.withList(lib::toggleMyList))
                }
                Tab.MyList, Tab.HomeVideos -> {
                    val kind = if (current == Tab.MyList) GridKind.MyList else GridKind.HomeVideos
                    val grid: GridViewModel = koinViewModel(key = kind.name) { parametersOf(kind) }
                    RefreshOnReturn(grid::refresh)
                    GridScreen(grid, onOpen = actions.open, onPlay = actions.play)
                }
            }
        }
        }
        }
        TopNav(
            current = current,
            tabs = tabs,
            profileName = vm.profileName,
            profileArt = vm.profileArt,
            onTab = { current = it },
            onSearch = nav::search,
            onProfile = nav::profiles,
            onSettings = nav::settings,
            currentTabRequester = navRequester,
            modifier = Modifier.onFocusChanged { navFocused = it.hasFocus },
        )
    }
    // Nothing focusable yet (still loading, or an empty tab): park focus on the nav.
    LaunchedEffect(current) {
        delay(700)
        if (!navFocused && !contentFocused) runCatching { navRequester.requestFocus() }
    }
}

/** Movies and series open Details; episodes and home videos play straight away (Netflix does the same). */
fun openItem(nav: AppNavigator, item: BaseItemDto) {
    when (item.type) {
        org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
        org.jellyfin.sdk.model.api.BaseItemKind.SERIES,
        org.jellyfin.sdk.model.api.BaseItemKind.EPISODE -> nav.details(item.id.toString())
        else -> nav.play(item.id.toString())
    }
}

private fun BrowseActions.withList(toggle: (BaseItemDto) -> Unit) = BrowseActions(open, play, toggle)

/** Refresh a tab when coming back from Details or the player (resume points, My List). */
@Composable
private fun RefreshOnReturn(refresh: () -> Unit) {
    var stopped by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stopped = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (stopped) {
            stopped = false
            refresh()
        }
    }
}
