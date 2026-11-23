package com.johnw.reelfin.player

import android.content.Context
import com.johnw.reelfin.playback.PlaybackSettings

/**
 * Player settings on disk. Session 4 builds the settings screen on top of this; for now only
 * the quality cap is stored (debug builds can set it from adb, see MainActivity).
 */
class PlaybackPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var maxStreamingBitrate: Int
        get() = prefs.getInt(KEY_MAX_BITRATE, PlaybackSettings.DEFAULT_MAX_BITRATE)
        set(value) = prefs.edit().putInt(KEY_MAX_BITRATE, value).apply()

    fun settings() = PlaybackSettings(maxStreamingBitrate = maxStreamingBitrate)

    private companion object {
        const val FILE = "reelfin_playback"
        const val KEY_MAX_BITRATE = "max_bitrate"
    }
}
