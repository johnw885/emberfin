package com.johnw.emberfin.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRatePolicyTest {
    @Test fun `the decoder's rate wins`() {
        assertEquals(29.97f, FrameRatePolicy.target(29.97f, 23.976f)!!, 0.001f)
    }

    @Test fun `falls back to the server's rate when the container has none`() {
        assertEquals(23.976f, FrameRatePolicy.target(-1f, 23.976f)!!, 0.001f)
    }

    @Test fun `nothing usable means no request`() {
        assertNull(FrameRatePolicy.target(-1f, null))
        assertNull(FrameRatePolicy.target(0f, 0f))
        assertNull(FrameRatePolicy.target(1000f, null))
    }

    @Test fun `only Dolby Vision profile 7 is decoded as its base layer`() {
        assertTrue(DolbyVisionPolicy.decodeBaseLayerOnly(org.jellyfin.sdk.model.api.VideoRangeType.DOVI_WITH_EL))
        assertTrue(DolbyVisionPolicy.decodeBaseLayerOnly(org.jellyfin.sdk.model.api.VideoRangeType.DOVI_WITH_ELHDR10_PLUS))
        assertFalse(DolbyVisionPolicy.decodeBaseLayerOnly(org.jellyfin.sdk.model.api.VideoRangeType.DOVI_WITH_HDR10))
        assertFalse(DolbyVisionPolicy.decodeBaseLayerOnly(null))
    }

    @Test fun `seamless is the default mode`() {
        assertEquals(FrameRateMode.SEAMLESS, PlaybackSettings().frameRateMode)
    }

    private fun uhd(id: Int, hz: Float) = DisplayModeInfo(id, 3840, 2160, hz)
    private fun hd(id: Int, hz: Float) = DisplayModeInfo(id, 1920, 1080, hz)
    /** The LG's 4K modes as the Streamer reports them, plus a 1080p one that must never be picked. */
    private val modes = listOf(
        uhd(452, 23.976f), uhd(453, 24.000002f), uhd(454, 25f), uhd(455, 29.97f), uhd(456, 30.000002f),
        uhd(457, 60.000004f), uhd(458, 50f), uhd(461, 59.94f), hd(448, 23.976f),
    )
    private val at60 = uhd(457, 60.000004f)

    @Test fun `film gets 23_976 Hz at the same resolution`() {
        assertEquals(452, FrameRatePolicy.pickMode(modes, at60, 23.976025f))
        assertEquals(453, FrameRatePolicy.pickMode(modes, at60, 24f))
    }

    @Test fun `29_97 fps gets 59_94 Hz rather than 29_97`() {
        assertEquals(461, FrameRatePolicy.pickMode(modes, at60, 29.97003f))
    }

    @Test fun `a fitting current mode is kept so the screen does not blank`() {
        assertEquals(457, FrameRatePolicy.pickMode(modes, at60, 30f))
        assertEquals(457, FrameRatePolicy.pickMode(modes, at60, 60f))
        assertEquals(452, FrameRatePolicy.pickMode(modes, uhd(452, 23.976f), 23.976f))
    }

    @Test fun `25 fps gets 50 Hz and no fitting mode means no switch`() {
        assertEquals(458, FrameRatePolicy.pickMode(modes, at60, 25f))
        assertNull(FrameRatePolicy.pickMode(modes, at60, 21.25f))
        assertNull(FrameRatePolicy.pickMode(modes, at60, 0f))
    }
}
