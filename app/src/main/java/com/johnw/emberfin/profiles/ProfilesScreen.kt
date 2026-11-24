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

    fun avatar(account: Account): Art? =
        ImageUrls.user(clients.create(account.serverUrl), account.userId.toUUID(), account.imageTag)?.let { Art(it.url) }

    suspend fun open(account: Account) = sessions.activate(account)
}

/** "Who's watching?": every profile saved on this TV, plus Add profile. */
@Composable
fun ProfilesScreen(onOpen: () -> Unit, onAdd: () -> Unit, vm: ProfilesViewModel = koinViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    val list = accounts ?: return
    LaunchedEffect(list.isNotEmpty()) { runCatching { first.requestFocus() } }

    Column(
        Modifier.fillMaxSize().background(Emberfin.colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Wordmark(size = 26)
        Spacer(Modifier.height(36.dp))
        Text("Who's watching?", style = EmberfinType.headline.copy(fontSize = 34.sp), color = Emberfin.colors.onBackground)
        Spacer(Modifier.height(36.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            list.forEachIndexed { i, account ->
                ProfileTile(
                    name = account.userName.ifBlank { "Profile" },
                    detail = account.serverName,
                    art = vm.avatar(account),
                    onClick = { scope.launch { vm.open(account); onOpen() } },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
            ProfileTile(
                name = "Add profile", detail = null, art = null, add = true, onClick = onAdd,
                modifier = if (list.isEmpty()) Modifier.focusRequester(first) else Modifier,
            )
        }
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
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            name, style = EmberfinType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) Emberfin.colors.onBackground else Emberfin.colors.muted,
        )
        if (detail != null) Text(detail, style = EmberfinType.label, color = Emberfin.colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
