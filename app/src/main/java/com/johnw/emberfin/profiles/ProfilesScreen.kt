package com.johnw.emberfin.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.johnw.emberfin.data.Account
import com.johnw.emberfin.data.ImageUrls
import com.johnw.emberfin.data.JellyfinClientFactory
import com.johnw.emberfin.data.ProfileLock
import com.johnw.emberfin.ui.ActionButton
import com.johnw.emberfin.ui.ChoiceDialog
import androidx.compose.foundation.layout.padding
import com.johnw.emberfin.data.SessionManager
import com.johnw.emberfin.ui.Art
import com.johnw.emberfin.ui.FocusStyle
import com.johnw.emberfin.ui.Emberfin
import com.johnw.emberfin.ui.EmberfinIcons
import com.johnw.emberfin.ui.EmberfinType
import com.johnw.emberfin.ui.RemoteImage
import com.johnw.emberfin.ui.Wordmark
import com.johnw.emberfin.ui.avatarColor
import com.johnw.emberfin.ui.initials
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUID
import org.koin.compose.viewmodel.koinViewModel

class ProfilesViewModel(private val sessions: SessionManager, private val clients: JellyfinClientFactory) : ViewModel() {
    /** Saved profiles, most recently used first. Null until loaded. */
    val accounts: StateFlow<List<Account>?> = sessions.accounts
        .map { list -> list.sortedByDescending { it.lastUsed } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val locks: StateFlow<Map<String, ProfileLock>> = sessions.locks.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun lock(account: Account): ProfileLock = locks.value[account.key] ?: ProfileLock()

    fun avatar(account: Account): Art? =
        ImageUrls.user(clients.create(account.serverUrl), account.userId.toUUID(), account.imageTag)?.let { Art(it.url) }

    suspend fun open(account: Account) = sessions.activate(account)

    fun setLock(account: Account, lock: ProfileLock) {
        viewModelScope.launch { sessions.setLock(account, lock) }
    }
}

/** What the picker is asking for right now. */
private sealed interface Prompt {
    /** The profile's PIN, then [then]. */
    data class Unlock(val account: Account, val then: () -> Unit) : Prompt
    /** Kids mode and PIN options for a profile. */
    data class Options(val account: Account) : Prompt
    /** A new PIN: typed once, then [first] is set and it's typed again to confirm. */
    data class NewPin(val account: Account, val first: String? = null) : Prompt
}

/**
 * "Who's watching?": every profile saved on this TV, plus Add profile. A profile with a PIN asks
 * for it before opening. "Manage profiles" switches the tiles to editing kids mode and the PIN
 * (behind the profile's own PIN, if it has one).
 */
@Composable
fun ProfilesScreen(onOpen: () -> Unit, onAdd: () -> Unit, vm: ProfilesViewModel = koinViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val locks by vm.locks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    var manage by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    val list = accounts ?: return
    LaunchedEffect(list.isNotEmpty()) { runCatching { first.requestFocus() } }

    fun guarded(account: Account, then: () -> Unit) {
        if (vm.lock(account).hasPin) prompt = Prompt.Unlock(account, then) else then()
    }

    Column(
        Modifier.fillMaxSize().background(Emberfin.colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(size = 26)
        Spacer(Modifier.height(36.dp))
        Text(
            if (manage) "Manage profiles" else "Who's watching?",
            style = EmberfinType.headline.copy(fontSize = 34.sp), color = Emberfin.colors.onBackground,
        )
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            list.forEachIndexed { i, account ->
                val lock = locks[account.key] ?: ProfileLock()
                ProfileTile(
                    name = account.userName.ifBlank { "Profile" },
                    detail = listOfNotNull("Kids".takeIf { lock.kids }, account.serverName).joinToString(" · "),
                    art = vm.avatar(account),
                    locked = lock.hasPin,
                    editing = manage,
                    onClick = {
                        if (manage) guarded(account) { prompt = Prompt.Options(account) }
                        else guarded(account) { scope.launch { vm.open(account); onOpen() } }
                    },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
            if (!manage) {
                ProfileTile(
                    name = "Add profile", detail = null, art = null, add = true, onClick = onAdd,
                    modifier = if (list.isEmpty()) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        if (list.isNotEmpty()) {
            Spacer(Modifier.height(40.dp))
            ActionButton(if (manage) "Done" else "Manage profiles", onClick = { manage = !manage })
        }
    }

    when (val p = prompt) {
        is Prompt.Unlock -> PinPad(
            title = "Enter the PIN for ${p.account.userName}",
            onComplete = { pin ->
                if (vm.lock(p.account).checkPin(pin)) { prompt = null; p.then(); null } else "Wrong PIN. Try again."
            },
            onDismiss = { prompt = null },
        )
        is Prompt.Options -> {
            val lock = vm.lock(p.account)
            val kidsLabel = if (lock.kids) "Kids profile: On (turn off)" else "Kids profile: Off (turn on)"
            val pinLabel = if (lock.hasPin) "Change the PIN" else "Set a PIN"
            val labels = listOfNotNull(kidsLabel, pinLabel, "Remove the PIN".takeIf { lock.hasPin }, "Done")
            ChoiceDialog(
                title = p.account.userName.ifBlank { "Profile" },
                options = labels,
                selected = -1,
                onPick = { i ->
                    when (labels[i]) {
                        kidsLabel -> { vm.setLock(p.account, lock.copy(kids = !lock.kids)); prompt = null }
                        pinLabel -> prompt = Prompt.NewPin(p.account)
                        "Remove the PIN" -> { vm.setLock(p.account, lock.withPin(null)); prompt = null }
                        else -> prompt = null
                    }
                },
                onDismiss = { prompt = null },
            )
        }
        is Prompt.NewPin -> PinPad(
            title = if (p.first == null) "Choose a 4-digit PIN" else "Enter the PIN again",
            subtitle = "For ${p.account.userName}. This TV asks for it before opening the profile.",
            onComplete = { pin ->
                when {
                    p.first == null -> { prompt = p.copy(first = pin); null }
                    pin != p.first -> { prompt = p.copy(first = null); "The PINs didn't match. Choose one again." }
                    else -> { vm.setLock(p.account, vm.lock(p.account).withPin(pin)); prompt = null; null }
                }
            },
            onDismiss = { prompt = null },
        )
        null -> Unit
    }
}

@Composable
private fun ProfileTile(
    name: String,
    detail: String?,
    art: Art?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    add: Boolean = false,
    locked: Boolean = false,
    editing: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.width(128.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(shape),
            scale = FocusStyle.scale(),
            border = FocusStyle.border(shape),
            glow = FocusStyle.glow(),
            colors = CardDefaults.colors(containerColor = if (add) Emberfin.colors.surfaceRaised else avatarColor(name)),
            modifier = Modifier.size(112.dp).onFocusChanged { focused = it.isFocused },
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    add -> Icon(EmberfinIcons.Plus, contentDescription = null, tint = Emberfin.colors.muted, modifier = Modifier.size(44.dp))
                    art != null -> RemoteImage(art, contentDescription = name, modifier = Modifier.fillMaxSize())
                    else -> Text(initials(name), style = EmberfinType.display.copy(fontSize = 44.sp), color = androidx.compose.ui.graphics.Color.White)
                }
                if (editing) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        Text("Edit", style = EmberfinType.title, color = androidx.compose.ui.graphics.Color.White)
                    }
                } else if (locked) {
                    Icon(
                        EmberfinIcons.Lock, contentDescription = "PIN", tint = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(18.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            name, style = EmberfinType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) Emberfin.colors.onBackground else Emberfin.colors.muted,
        )
        if (!detail.isNullOrEmpty()) Text(detail, style = EmberfinType.label, color = Emberfin.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
