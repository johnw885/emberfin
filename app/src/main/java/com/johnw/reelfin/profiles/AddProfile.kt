package com.johnw.reelfin.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Text
import com.johnw.reelfin.BuildConfig
import com.johnw.reelfin.data.AuthRepository
import com.johnw.reelfin.data.QuickConnectState
import com.johnw.reelfin.data.ServerInfo
import com.johnw.reelfin.data.ServerRepository
import com.johnw.reelfin.data.SessionManager
import com.johnw.reelfin.ui.ActionButton
import com.johnw.reelfin.ui.Reelfin
import com.johnw.reelfin.ui.ReelfinType
import com.johnw.reelfin.ui.Wordmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

sealed interface AddStep {
    data object Server : AddStep
    data class Method(val server: ServerInfo, val users: List<String>) : AddStep
    data class QuickConnect(val server: ServerInfo, val code: String? = null) : AddStep
    data class Password(val server: ServerInfo, val username: String = "") : AddStep
    data object Done : AddStep
}

data class AddState(val step: AddStep = AddStep.Server, val url: String = "", val busy: Boolean = false, val error: String? = null)

/** Add a profile: server address → Quick Connect or username/password → saved and made active. */
class AddProfileViewModel(
    private val servers: ServerRepository,
    private val auth: AuthRepository,
    private val sessions: SessionManager,
) : ViewModel() {
    private val _state = MutableStateFlow(AddState())
    val state: StateFlow<AddState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val url = sessions.lastServerUrl.first() ?: BuildConfig.DEV_SERVER_URL
            _state.update { if (it.url.isEmpty()) it.copy(url = url) else it }
        }
    }

    fun connect(input: String) {
        _state.update { it.copy(url = input, busy = true, error = null) }
        job?.cancel()
        job = viewModelScope.launch {
            servers.check(input)
                .onSuccess { info ->
                    sessions.rememberServer(info.url)
                    val users = servers.publicUsers(info.url).mapNotNull { it.name }
                    _state.update { it.copy(step = AddStep.Method(info, users), busy = false) }
                }
                .onFailure { e -> _state.update { it.copy(busy = false, error = "Can't reach that server (${e.message}).") } }
        }
    }

    fun quickConnect(server: ServerInfo) {
        _state.update { it.copy(step = AddStep.QuickConnect(server), error = null) }
        job?.cancel()
        job = viewModelScope.launch {
            try {
                auth.quickConnect(server).collect { s ->
                    when (s) {
                        is QuickConnectState.Code -> _state.update { it.copy(step = AddStep.QuickConnect(server, s.code)) }
                        is QuickConnectState.SignedIn -> {
                            sessions.add(s.account)
                            _state.update { it.copy(step = AddStep.Done) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "Quick Connect failed (${e.message}). Is it turned on in the server's dashboard?") }
            }
        }
    }

    fun password(server: ServerInfo, username: String = "") =
        _state.update { it.copy(step = AddStep.Password(server, username), error = null) }

    fun signIn(server: ServerInfo, username: String, password: String) {
        _state.update { it.copy(busy = true, error = null) }
        job?.cancel()
        job = viewModelScope.launch {
            try {
                sessions.add(auth.signIn(server, username.trim(), password))
                _state.update { it.copy(step = AddStep.Done, busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "Sign-in failed. Check the username and password.") }
            }
        }
    }

    /** Back: one step at a time. Returns false at the first step so the screen can leave. */
    fun back(): Boolean {
        job?.cancel()
        val s = _state.value.step
        _state.update {
            when (s) {
                is AddStep.QuickConnect -> it.copy(step = AddStep.Method(s.server, emptyList()), error = null, busy = false)
                is AddStep.Password -> it.copy(step = AddStep.Method(s.server, emptyList()), error = null, busy = false)
                is AddStep.Method -> it.copy(step = AddStep.Server, error = null, busy = false)
                else -> return false
            }
        }
        return true
    }
}

@Composable
fun AddProfileScreen(firstRun: Boolean, onDone: () -> Unit, onBack: () -> Unit, vm: AddProfileViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    BackHandler { if (!vm.back()) onBack() }
    LaunchedEffect(state.step) { if (state.step == AddStep.Done) onDone() }

    Column(
        Modifier.fillMaxSize().background(Reelfin.colors.background).padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(size = 30)
        Spacer(Modifier.height(28.dp))
        when (val step = state.step) {
            AddStep.Server -> ServerStep(state, firstRun, vm::connect)
            is AddStep.Method -> MethodStep(step, vm)
            is AddStep.QuickConnect -> QuickConnectStep(step)
            is AddStep.Password -> PasswordStep(step, state.busy) { u, p -> vm.signIn(step.server, u, p) }
            AddStep.Done -> Unit
        }
        state.error?.let {
            Spacer(Modifier.height(18.dp))
            Text(it, style = ReelfinType.body, color = Reelfin.colors.error)
        }
    }
}

@Composable
private fun ServerStep(state: AddState, firstRun: Boolean, onConnect: (String) -> Unit) {
    var url by remember(state.url) { mutableStateOf(state.url) }
    val button = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { button.requestFocus() } }
    Text(if (firstRun) "Welcome" else "Add a profile", style = ReelfinType.headline, color = Reelfin.colors.onBackground)
    Spacer(Modifier.height(6.dp))
    Text("Enter your Jellyfin server's address", style = ReelfinType.body, color = Reelfin.colors.muted)
    Spacer(Modifier.height(24.dp))
    TvTextField(url, { url = it }, placeholder = "http://server:8096", keyboard = KeyboardType.Uri, onDone = { onConnect(url) })
    Spacer(Modifier.height(18.dp))
    ActionButton(if (state.busy) "Connecting…" else "Connect", onClick = { onConnect(url) }, modifier = Modifier.focusRequester(button))
}

@Composable
private fun MethodStep(step: AddStep.Method, vm: AddProfileViewModel) {
    val first = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { first.requestFocus() } }
    Text("Sign in to ${step.server.name}", style = ReelfinType.headline, color = Reelfin.colors.onBackground)
    Spacer(Modifier.height(6.dp))
    Text("Jellyfin ${step.server.version}", style = ReelfinType.body, color = Reelfin.colors.muted)
    Spacer(Modifier.height(26.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton("Quick Connect", onClick = { vm.quickConnect(step.server) }, modifier = Modifier.focusRequester(first))
        ActionButton("Username & password", onClick = { vm.password(step.server) })
    }
    if (step.users.isNotEmpty()) {
        Spacer(Modifier.height(22.dp))
        Text("Or pick a user", style = ReelfinType.meta, color = Reelfin.colors.muted)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            step.users.forEach { name -> ActionButton(name, onClick = { vm.password(step.server, name) }) }
        }
    }
}

@Composable
private fun QuickConnectStep(step: AddStep.QuickConnect) {
    Text("Sign in with Quick Connect", style = ReelfinType.headline, color = Reelfin.colors.onBackground)
    Spacer(Modifier.height(28.dp))
    val code = step.code
    if (code == null) {
        Text("Getting a code…", style = ReelfinType.title, color = Reelfin.colors.muted)
    } else {
        Text(code, style = ReelfinType.display.copy(fontSize = 72.sp, letterSpacing = 14.sp), color = Reelfin.colors.accent)
        Spacer(Modifier.height(22.dp))
        Text(
            "On your phone or computer, open Jellyfin, go to your profile → Quick Connect, and enter this code.",
            style = ReelfinType.body, color = Reelfin.colors.onBackground,
        )
    }
    Spacer(Modifier.height(18.dp))
    Text("Press Back to choose another way", style = ReelfinType.meta, color = Reelfin.colors.faint)
}

@Composable
private fun PasswordStep(step: AddStep.Password, busy: Boolean, onSignIn: (String, String) -> Unit) {
    var user by remember(step) { mutableStateOf(step.username) }
    var pass by remember(step) { mutableStateOf("") }
    val userField = remember { FocusRequester() }
    LaunchedEffect(step) { runCatching { userField.requestFocus() } }
    Text("Sign in to ${step.server.name}", style = ReelfinType.headline, color = Reelfin.colors.onBackground)
    Spacer(Modifier.height(24.dp))
    TvTextField(user, { user = it }, placeholder = "Username", modifier = Modifier.focusRequester(userField), imeAction = ImeAction.Next)
    Spacer(Modifier.height(12.dp))
    TvTextField(pass, { pass = it }, placeholder = "Password", keyboard = KeyboardType.Password, secret = true, onDone = { onSignIn(user, pass) })
    Spacer(Modifier.height(18.dp))
    ActionButton(if (busy) "Signing in…" else "Sign in", onClick = { onSignIn(user, pass) })
}

/** A D-pad friendly text field: white border when focused; OK opens the on-screen keyboard. */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    secret: Boolean = false,
    onDone: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = ReelfinType.title.copy(color = Reelfin.colors.onBackground, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
        cursorBrush = SolidColor(Reelfin.colors.accent),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
        modifier = modifier
            .width(460.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(Reelfin.colors.surface, shape)
            .border(if (focused) 2.5.dp else 1.dp, if (focused) Color.White else Reelfin.colors.faint, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        decorationBox = { inner ->
            androidx.compose.foundation.layout.Box {
                if (value.isEmpty()) Text(placeholder, style = ReelfinType.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = Reelfin.colors.faint)
                inner()
            }
        },
    )
}
