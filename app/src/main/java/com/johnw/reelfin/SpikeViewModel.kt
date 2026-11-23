package com.johnw.reelfin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.johnw.reelfin.data.HomeRow
import com.johnw.reelfin.data.QuickConnectState
import com.johnw.reelfin.data.StoredSession
import com.johnw.reelfin.player.PlayerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.serializer.toUUID

sealed interface Screen {
    data class Server(val url: String, val busy: Boolean = false, val error: String? = null) : Screen
    data class QuickConnect(val serverUrl: String, val serverName: String, val code: String? = null, val error: String? = null) : Screen
    data class Home(val row: HomeRow? = null, val error: String? = null) : Screen
    data class Player(val session: PlayerSession) : Screen
}

/** Drives the spike flow: server -> Quick Connect -> one row -> player. */
class SpikeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ReelfinApp

    // Declared before _screen: initialScreen() may set them.
    private var quickConnectJob: Job? = null
    private var api: ApiClient? = null
    private var session: StoredSession? = null

    private val _screen = MutableStateFlow(initialScreen())
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    init {
        if (_screen.value is Screen.Home) viewModelScope.launch { loadHome() }
    }

    private fun initialScreen(): Screen {
        val saved = app.auth.currentSession
        if (saved != null) {
            useSession(saved)
            return Screen.Home()
        }
        val url = app.store.lastServerUrl ?: BuildConfig.DEV_SERVER_URL
        return Screen.Server(url)
    }

    private fun useSession(s: StoredSession) {
        session = s
        api = app.clients.create(s.serverUrl, s.accessToken)
    }

    fun connect(input: String) {
        _screen.value = Screen.Server(input, busy = true)
        viewModelScope.launch {
            app.servers.check(input)
                .onSuccess { info ->
                    app.store.lastServerUrl = info.url
                    startQuickConnect(info.url, "${info.name} (${info.version})")
                }
                .onFailure { _screen.value = Screen.Server(input, error = "Can't reach server: ${it.message}") }
        }
    }

    private fun startQuickConnect(serverUrl: String, serverName: String) {
        _screen.value = Screen.QuickConnect(serverUrl, serverName)
        quickConnectJob?.cancel()
        quickConnectJob = viewModelScope.launch {
            try {
                app.auth.quickConnect(serverUrl).collect { state ->
                    when (state) {
                        is QuickConnectState.Code ->
                            _screen.value = Screen.QuickConnect(serverUrl, serverName, code = state.code)
                        is QuickConnectState.SignedIn -> {
                            useSession(state.session)
                            _screen.value = Screen.Home()
                            loadHome()
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _screen.value = Screen.QuickConnect(
                    serverUrl, serverName,
                    error = "Quick Connect failed: ${e.message}. Is Quick Connect enabled on the server?",
                )
            }
        }
    }

    fun backToServer() {
        quickConnectJob?.cancel()
        _screen.value = Screen.Server(app.store.lastServerUrl ?: BuildConfig.DEV_SERVER_URL)
    }

    fun signOut() {
        app.auth.signOut()
        api = null
        session = null
        backToServer()
    }

    private suspend fun loadHome() {
        val api = api ?: return
        val s = session ?: return
        val result = try {
            Screen.Home(row = app.library.firstRow(api, s.userId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Screen.Home(error = "Couldn't load your library: ${e.message}")
        }
        // Don't yank the user out of the player if it opened while the row was loading.
        if (_screen.value is Screen.Home) _screen.value = result
    }

    fun imageUrl(item: BaseItemDto, width: Int): String? =
        api?.let { com.johnw.reelfin.data.ImageUrls.landscape(it, item, width) }

    fun play(item: BaseItemDto) =
        start(item.id, displayTitle(item), (item.userData?.playbackPositionTicks ?: 0L) / 10_000)

    /** Debug hook (see MainActivity.handleDebugIntent). */
    fun playById(itemId: String, startMs: Long) {
        if (session == null) return
        (_screen.value as? Screen.Player)?.session?.release()
        start(itemId.toUUID(), "Item $itemId", startMs)
    }

    private fun start(itemId: UUID, title: String, startMs: Long) {
        val api = api ?: return
        val s = session ?: return
        val row = (_screen.value as? Screen.Home)?.row
        viewModelScope.launch {
            try {
                val plan = app.negotiator.plan(api, s.userId, itemId)
                val player = PlayerSession(
                    context = app,
                    appScope = app.appScope,
                    api = api,
                    authorizationHeader = app.clients.authorizationHeader(s.accessToken),
                    plan = plan,
                    title = title,
                    startPositionMs = startMs,
                )
                _screen.value = Screen.Player(player)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _screen.value = Screen.Home(row = row, error = "Can't play: ${e.message}")
            }
        }
    }

    fun closePlayer() {
        (_screen.value as? Screen.Player)?.session?.release()
        _screen.value = Screen.Home()
        viewModelScope.launch { loadHome() }
    }

    override fun onCleared() {
        (_screen.value as? Screen.Player)?.session?.release()
    }

    companion object {
        fun displayTitle(item: BaseItemDto): String {
            val series = item.seriesName ?: return item.name ?: ""
            val s = item.parentIndexNumber
            val e = item.indexNumber
            val code = if (s != null && e != null) "S$s:E$e " else ""
            return "$series · $code${item.name ?: ""}"
        }
    }
}
