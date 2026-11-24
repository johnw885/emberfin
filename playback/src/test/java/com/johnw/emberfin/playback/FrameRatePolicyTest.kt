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
}
