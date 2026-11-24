package com.johnw.reelfin.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.johnw.reelfin.playback.FrameRateMode
import com.johnw.reelfin.playback.PlaybackSettings
import com.johnw.reelfin.playback.SegmentAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.MediaSegmentType

/** Every user setting. No secrets, so DataStore `reelfin_settings` may be backed up. */
data class AppSettings(
    val maxBitrate: Int = PlaybackSettings.DEFAULT_MAX_BITRATE,
    val preferAtmos: Boolean = true,
    val frameRateMode: FrameRateMode = FrameRateMode.SEAMLESS,
    val segmentActions: Map<MediaSegmentType, SegmentAction> = PlaybackSettings.DEFAULT_SEGMENT_ACTIONS,
    val autoplayNext: Boolean = true,
    /** "Are you still watching?" after 3 episodes or 2 hours without input (the prompt itself is Session 5b). */
    val stillWatching: Boolean = true,
    val subtitleScale: Float = 1f,
    val subtitleBackground: Boolean = true,
    /** Muted trailers on the billboard (Session 5a plays them). */
    val billboardTrailers: Boolean = true,
) {
    fun playback() = PlaybackSettings(
        maxStreamingBitrate = maxBitrate,
        preferAtmos = preferAtmos,
        autoplayNext = autoplayNext,
        segmentActions = segmentActions,
        frameRateMode = frameRateMode,
        subtitleScale = subtitleScale,
        subtitleBackground = subtitleBackground,
    )

    companion object {
        /** The segment types the settings screen offers, in display order. */
        val SEGMENT_TYPES = listOf(MediaSegmentType.INTRO, MediaSegmentType.RECAP, MediaSegmentType.PREVIEW, MediaSegmentType.COMMERCIAL, MediaSegmentType.OUTRO)

        fun read(p: Preferences): AppSettings {
            val d = AppSettings()
            return AppSettings(
                maxBitrate = p[Keys.MAX_BITRATE]?.takeIf { it > 0 } ?: d.maxBitrate,
                preferAtmos = p[Keys.PREFER_ATMOS] ?: d.preferAtmos,
                frameRateMode = p[Keys.FRAME_RATE]?.let { v -> FrameRateMode.entries.firstOrNull { it.name == v } } ?: d.frameRateMode,
                segmentActions = SEGMENT_TYPES.associateWith { type ->
                    p[Keys.segment(type)]?.let { v -> SegmentAction.entries.firstOrNull { it.name == v } } ?: d.segmentActions[type] ?: SegmentAction.IGNORE
                },
                autoplayNext = p[Keys.AUTOPLAY_NEXT] ?: d.autoplayNext,
                stillWatching = p[Keys.STILL_WATCHING] ?: d.stillWatching,
                subtitleScale = p[Keys.SUBTITLE_SCALE] ?: d.subtitleScale,
                subtitleBackground = p[Keys.SUBTITLE_BACKGROUND] ?: d.subtitleBackground,
                billboardTrailers = p[Keys.BILLBOARD_TRAILERS] ?: d.billboardTrailers,
            )
        }

        fun write(p: MutablePreferences, s: AppSettings) {
            p[Keys.MAX_BITRATE] = s.maxBitrate
            p[Keys.PREFER_ATMOS] = s.preferAtmos
            p[Keys.FRAME_RATE] = s.frameRateMode.name
            s.segmentActions.forEach { (type, action) -> p[Keys.segment(type)] = action.name }
            p[Keys.AUTOPLAY_NEXT] = s.autoplayNext
            p[Keys.STILL_WATCHING] = s.stillWatching
            p[Keys.SUBTITLE_SCALE] = s.subtitleScale
            p[Keys.SUBTITLE_BACKGROUND] = s.subtitleBackground
            p[Keys.BILLBOARD_TRAILERS] = s.billboardTrailers
        }
    }

    object Keys {
        /** Same name and type as Session 2's SharedPreferences key, so the migration carries it over. */
        val MAX_BITRATE = intPreferencesKey("max_bitrate")
        val PREFER_ATMOS = booleanPreferencesKey("prefer_atmos")
        val FRAME_RATE = stringPreferencesKey("frame_rate_mode")
        val AUTOPLAY_NEXT = booleanPreferencesKey("autoplay_next")
        val STILL_WATCHING = booleanPreferencesKey("still_watching")
        val SUBTITLE_SCALE = floatPreferencesKey("subtitle_scale")
        val SUBTITLE_BACKGROUND = booleanPreferencesKey("subtitle_background")
        val BILLBOARD_TRAILERS = booleanPreferencesKey("billboard_trailers")
        fun segment(type: MediaSegmentType) = stringPreferencesKey("segment_${type.name.lowercase()}")
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "reelfin_settings",
    // Session 2 kept the quality cap in SharedPreferences `reelfin_playback`.
    produceMigrations = { context -> listOf(SharedPreferencesMigration(context, "reelfin_playback")) },
)

/** Settings on disk. Screens observe [settings]; the player takes a [snapshot] when it opens. */
class SettingsStore(context: Context, scope: CoroutineScope) {
    private val store = context.applicationContext.settingsDataStore

    val settings: StateFlow<AppSettings> = store.data.map(AppSettings::read)
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** The current settings, read from disk if they haven't loaded yet (DataStore caches after the first read). */
    fun snapshot(): AppSettings = runBlocking { AppSettings.read(store.data.first()) }

    suspend fun update(change: (AppSettings) -> AppSettings) {
        store.edit { p -> AppSettings.write(p, change(AppSettings.read(p))) }
    }
}
