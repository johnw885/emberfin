package com.johnw.reelfin.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.johnw.reelfin.nav.AppNavigator
import com.johnw.reelfin.ui.ChoiceDialog
import com.johnw.reelfin.ui.Dimens
import com.johnw.reelfin.ui.Reelfin
import com.johnw.reelfin.ui.ReelfinType
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/** A list dialog that's open: its title, labels, the current index and what picking does. */
private data class OpenChoice(val title: String, val labels: List<String>, val selected: Int, val onPick: (Int) -> Unit)

/**
 * Settings: one D-pad list in sections. A row shows its current value on the right; OK either
 * flips an on/off setting or opens a list of choices. Changes are saved at once and the player
 * picks them up the next time it opens.
 */
@Composable
fun SettingsScreen(nav: AppNavigator, vm: SettingsViewModel = koinViewModel()) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val server by vm.server.collectAsStateWithLifecycle()
    var choice by remember { mutableStateOf<OpenChoice?>(null) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    BackHandler { nav.back() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    fun <T> pick(title: String, options: List<Option<T>>, current: T, apply: (AppSettings, T) -> AppSettings) {
        choice = OpenChoice(title, options.map { it.label }, options.indexOfFirst { it.value == current }) { i ->
            vm.update { apply(it, options[i].value) }
            choice = null
        }
    }

    Column(Modifier.fillMaxSize().background(Reelfin.colors.background)) {
        Text(
            "Settings", style = ReelfinType.headline, color = Reelfin.colors.onBackground,
            modifier = Modifier.padding(start = Dimens.ScreenPadding, top = 36.dp, bottom = 6.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 8.dp, bottom = 60.dp),
            modifier = Modifier.widthIn(max = 720.dp),
        ) {
            section("Profile")
            item {
                SettingRow(
                    "Signed in as ${vm.account.userName}",
                    value = "Switch profile",
                    onClick = nav::profiles,
                    modifier = Modifier.focusRequester(first),
                )
            }
            item { SettingRow("Add a profile", value = null, onClick = nav::addProfile) }
            item {
                SettingRow("Sign out of ${vm.account.userName}", value = null) {
                    choice = OpenChoice("Sign out of ${vm.account.userName} on this TV?", listOf("Sign out", "Cancel"), 1) { i ->
                        choice = null
                        if (i == 0) scope.launch {
                            val others = vm.signOut()
                            if (others) nav.profilesFresh() else nav.signInFresh()
                        }
                    }
                }
            }
            item {
                val detail = listOfNotNull(server.version?.let { "Jellyfin $it" }, server.url).joinToString("  ·  ")
                SettingRow("Server: ${server.name}", value = detail, onClick = {})
            }

            section("Playback")
            item {
                SettingRow("Maximum streaming quality", SettingsOptions.bitrateLabel(s.maxBitrate)) {
                    pick("Maximum streaming quality", SettingsOptions.bitrates, s.maxBitrate) { a, v -> a.copy(maxBitrate = v) }
                }
            }
            item {
                SettingRow("Prefer Dolby Atmos", onOff(s.preferAtmos), hint = "Picks a DD+ Atmos track over plain DD+ when both exist") {
                    vm.update { it.copy(preferAtmos = !it.preferAtmos) }
                }
            }
            item {
                SettingRow("Match the video's frame rate", SettingsOptions.label(SettingsOptions.frameRates, s.frameRateMode)) {
                    pick("Match the video's frame rate", SettingsOptions.frameRates, s.frameRateMode) { a, v -> a.copy(frameRateMode = v) }
                }
            }
            item { SettingRow("Autoplay next episode", onOff(s.autoplayNext)) { vm.update { it.copy(autoplayNext = !it.autoplayNext) } } }
            item {
                SettingRow("Ask \"Are you still watching?\"", onOff(s.stillWatching), hint = "After 3 episodes or 2 hours without pressing anything") {
                    vm.update { it.copy(stillWatching = !it.stillWatching) }
                }
            }
            AppSettings.SEGMENT_TYPES.forEach { type ->
                item {
                    val options = SettingsOptions.segmentOptions(type)
                    val current = s.segmentActions[type] ?: options.first().value
                    val title = SettingsOptions.segmentTitle(type)
                    SettingRow(title, SettingsOptions.label(options, current)) {
                        pick(title, options, current) { a, v -> a.copy(segmentActions = a.segmentActions + (type to v)) }
                    }
                }
            }

            section("Subtitles")
            item {
                SettingRow("Size", SettingsOptions.label(SettingsOptions.subtitleSizes, s.subtitleScale) { "${(it * 100).toInt()} %" }) {
                    pick("Subtitle size", SettingsOptions.subtitleSizes, s.subtitleScale) { a, v -> a.copy(subtitleScale = v) }
                }
            }
            item {
                SettingRow("Dark background behind text", onOff(s.subtitleBackground)) {
                    vm.update { it.copy(subtitleBackground = !it.subtitleBackground) }
                }
            }

            section("Interface")
            item {
                SettingRow("Play trailers on the billboard", onOff(s.billboardTrailers), hint = "Muted, after a few seconds on a title") {
                    vm.update { it.copy(billboardTrailers = !it.billboardTrailers) }
                }
            }

            section("About")
            item { SettingRow("Reelfin ${vm.appVersion}", value = "A client for Jellyfin, not affiliated with the Jellyfin project", onClick = {}) }
            item { SettingRow("Open-source licences", value = null, onClick = nav::licenses) }
        }
    }

    choice?.let { c -> ChoiceDialog(c.title, c.labels, c.selected, onPick = c.onPick, onDismiss = { choice = null }) }
}

private fun onOff(v: Boolean) = if (v) "On" else "Off"

private fun LazyListScope.section(title: String) {
    item(contentType = "section") {
        Text(
            title.uppercase(), style = ReelfinType.label, color = Reelfin.colors.accent,
            modifier = Modifier.padding(start = 14.dp, top = 22.dp, bottom = 6.dp),
        )
    }
}

/** A settings row: label (+ hint) on the left, current value on the right; white when focused. */
@Composable
fun SettingRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier,
    hint: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Reelfin.colors.onBackground,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = ReelfinType.bodyStrong, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hint != null) Text(hint, style = ReelfinType.label, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
            }
            if (value != null) {
                Spacer(Modifier.width(16.dp))
                Box(Modifier.widthIn(max = 380.dp)) {
                    Text(value, style = ReelfinType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
