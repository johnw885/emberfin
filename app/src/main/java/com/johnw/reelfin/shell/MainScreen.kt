package com.johnw.reelfin.shell

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.johnw.reelfin.data.ApiHealth
import com.johnw.reelfin.ui.ActionButton
import com.johnw.reelfin.ui.Reelfin
import com.johnw.reelfin.ui.ReelfinType
import org.koin.compose.koinInject
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
    val health: ApiHealth = koinInject()
    val unreachable by health.unreachable.collectAsStateWithLifecycle()
    // Bumped to make the visible tab reload (Retry, or the server coming back).
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(unreachable) {
        if (unreachable) {
            // Keep trying quietly; any answer from the server clears the banner.
            while (true) {
                delay(RETRY_MS)
                reload++
            }
        }
    }
    var wasUnreachable by remember { mutableStateOf(false) }
    LaunchedEffect(unreachable) {
        if (wasUnreachable && !unreachable) reload++
        wasUnreachable = unreachable
    }

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
                    ReloadOn(reload, home::refresh)
                    HomeScreen(home, actions.withList(home::toggleMyList))
                }
                Tab.Shows, Tab.Movies -> {
                    val lib: LibraryTabViewModel = koinViewModel(key = current.name) { parametersOf(current) }
                    RefreshOnReturn(lib::refresh)
                    ReloadOn(reload, lib::refresh)
                    LibraryTabScreen(lib, actions.withList(lib::toggleMyList))
                }
                Tab.MyList, Tab.HomeVideos -> {
                    val kind = if (current == Tab.MyList) GridKind.MyList else GridKind.HomeVideos
                    val grid: GridViewModel = koinViewModel(key = kind.name) { parametersOf(kind) }
                    RefreshOnReturn(grid::refresh)
                    ReloadOn(reload, grid::refresh)
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
        if (unreachable) {
            OfflineBanner(vm.session.account.serverName, onRetry = { reload++ }, modifier = Modifier.align(Alignment.BottomCenter))
        }
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

@Composable
private fun ReloadOn(tick: Int, refresh: () -> Unit) {
    LaunchedEffect(tick) { if (tick > 0) refresh() }
}

/** "Can't reach the server" with a Retry button; the rows already on screen (or cached) stay usable. */
@Composable
private fun OfflineBanner(server: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .padding(bottom = 22.dp)
            .background(Color(0xF01B1D22), RoundedCornerShape(10.dp))
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(Reelfin.colors.error, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("Can't reach $server. Showing what's saved; retrying…", style = ReelfinType.bodyStrong, color = Reelfin.colors.onBackground)
        Spacer(Modifier.width(14.dp))
        ActionButton("Retry", onClick = onRetry)
    }
}

private const val RETRY_MS = 15_000L

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
