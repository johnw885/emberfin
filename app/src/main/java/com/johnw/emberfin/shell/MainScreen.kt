package com.johnw.emberfin.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.johnw.emberfin.data.ApiHealth
import com.johnw.emberfin.ui.ActionButton
import com.johnw.emberfin.ui.Emberfin
import com.johnw.emberfin.ui.EmberfinType
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.johnw.emberfin.browse.BrowseActions
import com.johnw.emberfin.browse.LocalAutoFocus
import com.johnw.emberfin.data.ActiveSession
import com.johnw.emberfin.data.ImageUrls
import com.johnw.emberfin.data.LibraryRepository
import com.johnw.emberfin.home.HomeScreen
import com.johnw.emberfin.home.HomeViewModel
import com.johnw.emberfin.library.GridKind
import com.johnw.emberfin.library.GridScreen
import com.johnw.emberfin.library.GridViewModel
import com.johnw.emberfin.library.LibraryTabScreen
import com.johnw.emberfin.library.LibraryTabViewModel
import com.johnw.emberfin.nav.AppNavigator
import com.johnw.emberfin.ui.Art
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.johnw.emberfin.data.SessionManager
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Which tabs exist depends on the libraries the profile can see. */
class MainViewModel(private val library: LibraryRepository, val session: ActiveSession, sessions: SessionManager) : ViewModel() {
    private val _tabs = MutableStateFlow(listOf(Tab.Home, Tab.Shows, Tab.Movies, Tab.MyList))
    /** Kids profiles get a simpler shell: no Home Videos tab and no Settings. */
    val kids: StateFlow<Boolean> = sessions.lock(session.account).map { it.kids }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val tabs: StateFlow<List<Tab>> = combine(_tabs, kids) { t, k -> if (k) t - Tab.HomeVideos else t }
        .stateIn(viewModelScope, SharingStarted.Eagerly, _tabs.value)

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
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun MainScreen(nav: AppNavigator, vm: MainViewModel = koinViewModel()) {
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val kids by vm.kids.collectAsStateWithLifecycle()
    var current by rememberSaveable { mutableStateOf(Tab.Home) }
    // Kids mode turned on while on a tab it hides.
    LaunchedEffect(tabs) { if (current !in tabs) current = Tab.Home }
    var navFocused by remember { mutableStateOf(false) }
    val navRequester = remember { FocusRequester() }
    val holder = rememberSaveableStateHolder()
    val activity = LocalActivity.current
    val health: ApiHealth = koinInject()
    val unreachable by health.unreachable.collectAsStateWithLifecycle()
    // Bumped to make the visible tab reload (Retry, or the server coming back).
    var reload by remember { mutableIntStateOf(0) }
    // Watched state, resume points or My List changed on the server (a phone, Jellyfin web, another
    // TV): reload the visible tab, a few seconds later so a burst of changes reloads once. Under the
    // player or Details the tab is stopped, and RefreshOnReturn reloads it on the way back.
    val remote: com.johnw.emberfin.remote.RemoteControl = koinInject()
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(remote) {
        remote.userDataChanged.debounce(USER_DATA_DEBOUNCE_MS).collect {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) reload++
        }
    }
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
            play = { item -> if (item.type in ContainerKinds) nav.details(item.id.toString()) else nav.play(item.id.toString()) },
            toggleList = {},
        )
    }

    var contentFocused by remember { mutableStateOf(false) }
    // A quick fade-in when switching tabs (the first tab just appears).
    val tabAlpha = remember { Animatable(1f) }
    var shownTab by remember { mutableStateOf(current) }
    LaunchedEffect(current) {
        if (current != shownTab) {
            shownTab = current
            tabAlpha.snapTo(0f)
            tabAlpha.animateTo(1f, tween(260))
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = tabAlpha.value }.onFocusChanged { contentFocused = it.hasFocus }) {
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
            onSettings = nav::settings.takeUnless { kids },
            kids = kids,
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

/** Things with nothing to play directly: their page picks what to play. */
private val ContainerKinds = setOf(
    org.jellyfin.sdk.model.api.BaseItemKind.SERIES,
    org.jellyfin.sdk.model.api.BaseItemKind.BOX_SET,
    org.jellyfin.sdk.model.api.BaseItemKind.PLAYLIST,
)

/** Movies and series open Details; episodes and home videos play straight away (Netflix does the same). */
fun openItem(nav: AppNavigator, item: BaseItemDto) {
    when (item.type) {
        org.jellyfin.sdk.model.api.BaseItemKind.MOVIE,
        org.jellyfin.sdk.model.api.BaseItemKind.SERIES,
        org.jellyfin.sdk.model.api.BaseItemKind.EPISODE,
        org.jellyfin.sdk.model.api.BaseItemKind.BOX_SET,
        org.jellyfin.sdk.model.api.BaseItemKind.PLAYLIST -> nav.details(item.id.toString())
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
        Box(Modifier.size(8.dp).background(Emberfin.colors.error, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text("Can't reach $server. Showing what's saved; retrying…", style = EmberfinType.bodyStrong, color = Emberfin.colors.onBackground)
        Spacer(Modifier.width(14.dp))
        ActionButton("Retry", onClick = onRetry)
    }
}

private const val RETRY_MS = 15_000L
private const val USER_DATA_DEBOUNCE_MS = 3_000L

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
