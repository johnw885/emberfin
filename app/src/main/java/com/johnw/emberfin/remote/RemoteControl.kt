package com.johnw.emberfin.remote

import android.util.Log
import com.johnw.emberfin.data.ActiveSession
import com.johnw.emberfin.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.api.sockets.subscribeGeneralCommands
import org.jellyfin.sdk.model.api.ClientCapabilitiesDto
import org.jellyfin.sdk.model.api.GeneralCommand
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMessage
import org.jellyfin.sdk.model.api.PlayRequest
import org.jellyfin.sdk.model.api.PlaystateMessage
import org.jellyfin.sdk.model.api.PlaystateRequest
import org.jellyfin.sdk.model.api.UserDataChangedMessage

/** A "send message" from another Jellyfin client, shown over whatever is on screen. */
data class RemoteMessage(val header: String?, val text: String, val timeoutMs: Long, val id: Long = System.nanoTime())

/**
 * Makes the Streamer a target for "play on" in other Jellyfin apps (the phone app's cast button,
 * Jellyfin web's "Play on"): tells the server what this client accepts
 * (`POST /Sessions/Capabilities/Full`) and listens on the server's WebSocket while the app is in
 * the foreground with a profile active. Commands come out as flows; the navigation host handles
 * [play], the player handles [playstate], [commands] and [enqueue], browse screens refresh on
 * [userDataChanged].
 */
class RemoteControl(private val sessions: SessionManager, private val scope: CoroutineScope) {
    private val foreground = MutableStateFlow(false)

    private val _play = MutableSharedFlow<PlayRequest>(extraBufferCapacity = 4)
    /** "Play now" (or play next/last when nothing plays) from another client. */
    val play: SharedFlow<PlayRequest> = _play.asSharedFlow()

    private val _enqueue = MutableSharedFlow<PlayRequest>(extraBufferCapacity = 4)
    /** "Play next" / "Play last" while the player is open: added to its queue. */
    val enqueue: SharedFlow<PlayRequest> = _enqueue.asSharedFlow()

    private val _playstate = MutableSharedFlow<PlaystateRequest>(extraBufferCapacity = 16)
    val playstate: SharedFlow<PlaystateRequest> = _playstate.asSharedFlow()

    private val _commands = MutableSharedFlow<GeneralCommand>(extraBufferCapacity = 16)
    /** SetAudioStreamIndex / SetSubtitleStreamIndex for the player. */
    val commands: SharedFlow<GeneralCommand> = _commands.asSharedFlow()

    private val _message = MutableStateFlow<RemoteMessage?>(null)
    val message: StateFlow<RemoteMessage?> = _message.asStateFlow()

    private val _userDataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Watched state, resume points, favourites or ratings changed on the server (any client). */
    val userDataChanged: SharedFlow<Unit> = _userDataChanged.asSharedFlow()

    fun start() {
        scope.launch {
            combine(sessions.current, foreground) { session, fg -> session.takeIf { fg } }
                .distinctUntilChanged()
                .collectLatest { session -> if (session != null) listen(session) }
        }
    }

    /** MainActivity's onStart / onStop. The socket only stays open while the app is on screen. */
    fun setForeground(value: Boolean) {
        foreground.value = value
    }

    fun forward(request: PlayRequest) = _enqueue.tryEmit(request)

    fun dismissMessage(id: Long) {
        if (_message.value?.id == id) _message.value = null
    }

    private suspend fun listen(session: ActiveSession) = coroutineScope {
        val socket = session.api.webSocket
        Log.i(TAG, "Listening for remote control")
        // Subscribing opens the socket; capabilities go out on every (re)connect, because the
        // server only offers a session as a cast target while it has both.
        launch { socket.state.collect { if (it is SocketApiState.Connected) postCapabilities(session) } }
        launch { socket.subscribe<PlayMessage>().collect { m -> m.data?.let { Log.i(TAG, "Play ${it.playCommand}"); _play.tryEmit(it) } } }
        launch { socket.subscribe<PlaystateMessage>().collect { m -> m.data?.let { Log.i(TAG, "Playstate ${it.command}"); _playstate.tryEmit(it) } } }
        launch {
            socket.subscribeGeneralCommands(SUPPORTED_COMMANDS).collect { m ->
                val command = m.data ?: return@collect
                Log.i(TAG, "Command ${command.name}")
                if (command.name == GeneralCommandType.DISPLAY_MESSAGE) showMessage(command) else _commands.tryEmit(command)
            }
        }
        launch { socket.subscribe<UserDataChangedMessage>().collect { _userDataChanged.tryEmit(Unit) } }
    }

    private fun showMessage(command: GeneralCommand) {
        val args = command.arguments.orEmpty()
        val text = args["Text"]?.takeIf { it.isNotBlank() } ?: return
        val timeout = args["TimeoutMs"]?.toLongOrNull()?.takeIf { it in 1_000..60_000 } ?: DEFAULT_MESSAGE_MS
        _message.value = RemoteMessage(args["Header"]?.takeIf { it.isNotBlank() }, text, timeout)
    }

    private suspend fun postCapabilities(session: ActiveSession) = withContext(Dispatchers.IO) {
        runCatching {
            session.api.sessionApi.postFullCapabilities(
                data = ClientCapabilitiesDto(
                    playableMediaTypes = listOf(MediaType.VIDEO),
                    supportedCommands = SUPPORTED_COMMANDS.toList(),
                    supportsMediaControl = true,
                    supportsPersistentIdentifier = true,
                ),
            )
        }.onSuccess { Log.i(TAG, "Capabilities posted") }.onFailure { Log.w(TAG, "Couldn't post capabilities", it) }
    }

    companion object {
        private const val TAG = "RemoteControl"
        private const val DEFAULT_MESSAGE_MS = 6_000L
        val SUPPORTED_COMMANDS = setOf(
            GeneralCommandType.DISPLAY_MESSAGE,
            GeneralCommandType.SET_AUDIO_STREAM_INDEX,
            GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
        )
    }
}
