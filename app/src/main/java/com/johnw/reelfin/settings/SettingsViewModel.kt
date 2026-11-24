package com.johnw.reelfin.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.johnw.reelfin.BuildConfig
import com.johnw.reelfin.data.Account
import com.johnw.reelfin.data.ActiveSession
import com.johnw.reelfin.data.SessionManager
import com.johnw.reelfin.playback.FrameRateMode
import com.johnw.reelfin.playback.PlaybackSettings
import com.johnw.reelfin.playback.SegmentAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.api.MediaSegmentType

/** One choice in a settings list: what's stored and what the viewer reads. */
data class Option<T>(val value: T, val label: String)

/** The option lists and labels the settings screen shows. Pure, so it's unit-tested. */
object SettingsOptions {
    val bitrates: List<Option<Int>> = PlaybackSettings.BITRATE_OPTIONS.map { bps ->
        Option(bps, if (bps == PlaybackSettings.DEFAULT_MAX_BITRATE) "Maximum (${bps / 1_000_000} Mbps)" else "${bps / 1_000_000} Mbps")
    }

    val frameRates = listOf(
        Option(FrameRateMode.OFF, "Off"),
        Option(FrameRateMode.SEAMLESS, "Seamless only"),
        Option(FrameRateMode.ALWAYS, "Always (screen may blank briefly)"),
    )

    val subtitleSizes: List<Option<Float>> = PlaybackSettings.SUBTITLE_SCALES.map { (label, scale) -> Option(scale, label) }

    fun segmentOptions(type: MediaSegmentType): List<Option<SegmentAction>> = when (type) {
        MediaSegmentType.OUTRO -> listOf(
            Option(SegmentAction.NEXT_UP, "Next-episode countdown"),
            Option(SegmentAction.SKIP_BUTTON, "Show a Skip button"),
            Option(SegmentAction.IGNORE, "Do nothing"),
        )
        else -> listOf(
            Option(SegmentAction.SKIP_BUTTON, "Show a Skip button"),
            Option(SegmentAction.AUTO_SKIP, "Skip automatically"),
            Option(SegmentAction.IGNORE, "Do nothing"),
        )
    }

    fun segmentTitle(type: MediaSegmentType): String = when (type) {
        MediaSegmentType.INTRO -> "Intros"
        MediaSegmentType.RECAP -> "Recaps"
        MediaSegmentType.PREVIEW -> "Previews"
        MediaSegmentType.COMMERCIAL -> "Commercials"
        MediaSegmentType.OUTRO -> "Credits"
        else -> type.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    /** The label for [value], or the value itself when it isn't one of the [options] (an old or adb-set value). */
    fun <T> label(options: List<Option<T>>, value: T, fallback: (T) -> String = { it.toString() }): String =
        options.firstOrNull { it.value == value }?.label ?: fallback(value)

    fun bitrateLabel(bps: Int) = label(bitrates, bps) { "${it / 1_000_000.0} Mbps" }
}

data class ServerLine(val name: String, val version: String?, val url: String)

class SettingsViewModel(
    private val store: SettingsStore,
    private val sessions: SessionManager,
    val session: ActiveSession,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = store.settings
    val appVersion: String = BuildConfig.VERSION_NAME
    val account: Account = session.account

    private val _server = MutableStateFlow(ServerLine(account.serverName, null, account.serverUrl))
    val server: StateFlow<ServerLine> = _server.asStateFlow()

    init {
        viewModelScope.launch {
            val v = withContext(Dispatchers.IO) { runCatching { session.api.systemApi.getPublicSystemInfo().content.version }.getOrNull() }
            _server.value = _server.value.copy(version = v)
        }
    }

    fun update(change: (AppSettings) -> AppSettings) {
        viewModelScope.launch { store.update(change) }
    }

    /** Signs this profile out. Returns whether other profiles remain on the device. */
    suspend fun signOut(): Boolean {
        sessions.remove(account)
        return sessions.accounts.first().isNotEmpty()
    }
}
