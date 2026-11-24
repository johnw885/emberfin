package com.johnw.emberfin.playback

import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.junit.Assert.assertEquals
import org.junit.Test

class TrickplayTest {
    // 320x172 thumbnails, 10x10 per sheet, one every 10 s, 758 in total.
    private val info = TrickplayInfoDto(width = 320, height = 172, tileWidth = 10, tileHeight = 10, thumbnailCount = 758, interval = 10_000, bandwidth = 1)

    @Test fun `first frame`() = assertEquals(TrickplayFrame(0, 0, 0, 320, 172), Trickplay.frameAt(info, 0))

    @Test fun `inside the first sheet`() =
        // 125 s -> thumbnail 12 -> column 2, row 1.
        assertEquals(TrickplayFrame(0, 640, 172, 320, 172), Trickplay.frameAt(info, 125_000))

    @Test fun `second sheet starts at thumbnail 100`() =
        assertEquals(TrickplayFrame(1, 0, 0, 320, 172), Trickplay.frameAt(info, 1_005_000))

    @Test fun `past the end clamps to the last thumbnail`() =
        // 757 -> sheet 7, position 57 -> column 7, row 5.
        assertEquals(TrickplayFrame(7, 2240, 860, 320, 172), Trickplay.frameAt(info, 99_000_000))

    @Test fun `negative positions clamp to zero`() =
        assertEquals(TrickplayFrame(0, 0, 0, 320, 172), Trickplay.frameAt(info, -5_000))
}
