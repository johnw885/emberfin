package com.johnw.emberfin.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.johnw.emberfin.playback.FrameRateMode
import com.johnw.emberfin.playback.PlaybackSettings
import com.johnw.emberfin.playback.SegmentAction
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {
    @Test fun `empty store gives the defaults`() {
        val s = AppSettings.read(mutablePreferencesOf())
        assertEquals(AppSettings(), s)
        assertEquals(PlaybackSettings.DEFAULT_SEGMENT_ACTIONS, s.segmentActions)
    }

    @Test fun `round trip keeps every value`() {
        val s = AppSettings(
            maxBitrate = 20_000_000, preferAtmos = false, frameRateMode = FrameRateMode.ALWAYS,
            segmentActions = PlaybackSettings.DEFAULT_SEGMENT_ACTIONS + (MediaSegmentType.INTRO to SegmentAction.AUTO_SKIP),
            autoplayNext = false, stillWatching = false, subtitleScale = 1.25f, subtitleBackground = false, billboardTrailers = false,
        )
        val p = mutablePreferencesOf()
        AppSettings.write(p, s)
        assertEquals(s, AppSettings.read(p))
    }

    @Test fun `the old SharedPreferences cap carries over by key name`() {
        val p = mutablePreferencesOf(AppSettings.Keys.MAX_BITRATE to 4_000_000)
        assertEquals(4_000_000, AppSettings.read(p).maxBitrate)
    }

    @Test fun `unknown stored values fall back to defaults`() {
        val p = mutablePreferencesOf(AppSettings.Keys.FRAME_RATE to "SOMETIMES", AppSettings.Keys.MAX_BITRATE to 0)
        val s = AppSettings.read(p)
        assertEquals(FrameRateMode.SEAMLESS, s.frameRateMode)
        assertEquals(PlaybackSettings.DEFAULT_MAX_BITRATE, s.maxBitrate)
    }

    @Test fun `playback settings follow the app settings`() {
        val pb = AppSettings(maxBitrate = 10_000_000, frameRateMode = FrameRateMode.OFF, subtitleScale = 0.8f).playback()
        assertEquals(10_000_000, pb.maxStreamingBitrate)
        assertEquals(FrameRateMode.OFF, pb.frameRateMode)
        assertEquals(0.8f, pb.subtitleScale)
    }

    @Test fun `every offered segment type has choices and a label`() {
        AppSettings.SEGMENT_TYPES.forEach { type ->
            val options = SettingsOptions.segmentOptions(type)
            assertTrue(options.any { it.value == PlaybackSettings.DEFAULT_SEGMENT_ACTIONS[type] })
            assertTrue(SettingsOptions.segmentTitle(type).isNotBlank())
        }
    }

    @Test fun `bitrate labels`() {
        assertEquals("Maximum (120 Mbps)", SettingsOptions.bitrateLabel(120_000_000))
        assertEquals("4 Mbps", SettingsOptions.bitrateLabel(4_000_000))
        assertEquals("2.5 Mbps", SettingsOptions.bitrateLabel(2_500_000))
    }
}
