package com.johnw.emberfin.playback

import org.jellyfin.sdk.model.api.MediaSegmentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentControllerTest {
    private val recap = Segment(MediaSegmentType.RECAP, 0, 40_000)
    private val intro = Segment(MediaSegmentType.INTRO, 60_000, 120_000)
    private val ad = Segment(MediaSegmentType.COMMERCIAL, 600_000, 660_000)
    private val outro = Segment(MediaSegmentType.OUTRO, 2_500_000, 2_560_000)
    private val duration = 2_600_000L

    private fun controller(
        segments: List<Segment> = listOf(recap, intro, ad, outro),
        hasNext: Boolean = true,
        settings: PlaybackSettings = PlaybackSettings(),
    ) = SegmentController(segments, duration, hasNext, settings)

    @Test fun `skip button shows inside intro and seeks to its end`() {
        val c = controller()
        assertNull(c.update(50_000))
        assertEquals(SegmentUi.None, c.ui)
        assertNull(c.update(61_000))
        assertEquals(SegmentUi.SkipButton(intro), c.ui)
        assertEquals(SegmentCommand.SeekTo(120_000), c.onSkipPressed())
        assertEquals(SegmentUi.None, c.ui)
    }

    @Test fun `recap gets a skip button too`() {
        val c = controller()
        c.update(5_000)
        assertEquals(SegmentUi.SkipButton(recap), c.ui)
    }

    @Test fun `skip button hides in the segment's last second`() {
        val c = controller()
        c.update(119_500)
        assertEquals(SegmentUi.None, c.ui)
    }

    @Test fun `back dismisses the skip button until the segment is left`() {
        val c = controller()
        c.update(61_000)
        assertTrue(c.onBack())
        c.update(70_000)
        assertEquals(SegmentUi.None, c.ui)
        c.update(30_000) // rewound out of the intro (into the recap)
        c.update(65_000) // and back in
        assertEquals(SegmentUi.SkipButton(intro), c.ui)
    }

    @Test fun `back with nothing showing is not consumed`() {
        val c = controller()
        c.update(200_000)
        assertFalse(c.onBack())
    }

    @Test fun `no skip button while the user is scrubbing`() {
        val c = controller()
        c.update(61_000, userSeeking = true)
        assertEquals(SegmentUi.None, c.ui)
        assertNull(c.onSkipPressed())
    }

    @Test fun `commercials are skipped once`() {
        val c = controller()
        assertEquals(SegmentCommand.SeekTo(660_000), c.update(601_000))
        // The user seeks back into the ad on purpose: leave them there.
        assertNull(c.update(610_000))
    }

    @Test fun `no auto-skip while scrubbing`() {
        val c = controller()
        assertNull(c.update(601_000, userSeeking = true))
        assertEquals(SegmentCommand.SeekTo(660_000), c.update(602_000))
    }

    @Test fun `outro starts a countdown that plays the next episode`() {
        val c = controller()
        assertEquals(2_500_000L, c.nextUpStartMs)
        assertNull(c.update(2_501_000))
        assertEquals(SegmentUi.NextUp(10_000, 10_000), c.ui)
        assertNull(c.update(2_506_000))
        assertEquals(SegmentUi.NextUp(5_000, 10_000), c.ui)
        assertEquals(SegmentCommand.PlayNext, c.update(2_511_000))
        // Fires once.
        assertNull(c.update(2_512_000))
        assertNull(c.onEnded())
    }

    @Test fun `paused playback freezes the countdown`() {
        val c = controller()
        c.update(2_501_000)
        repeat(5) { assertNull(c.update(2_503_000)) }
        assertEquals(SegmentUi.NextUp(8_000, 10_000), c.ui)
    }

    @Test fun `back cancels the countdown so the credits play`() {
        val c = controller()
        c.update(2_501_000)
        assertTrue(c.onBack())
        assertNull(c.update(2_520_000))
        assertEquals(SegmentUi.None, c.ui)
        assertNull(c.onEnded())
    }

    @Test fun `rewinding before the credits re-arms the countdown`() {
        val c = controller()
        c.update(2_501_000)
        c.onBack()
        c.update(2_400_000)
        c.update(2_500_500)
        assertEquals(SegmentUi.NextUp(10_000, 10_000), c.ui)
    }

    @Test fun `play now skips the countdown`() {
        val c = controller()
        c.update(2_501_000)
        assertEquals(SegmentCommand.PlayNext, c.onPlayNextPressed())
        assertNull(c.onPlayNextPressed())
    }

    @Test fun `without an outro the countdown starts 30 s before the end`() {
        val c = controller(segments = listOf(intro))
        assertEquals(duration - 30_000, c.nextUpStartMs)
        c.update(duration - 31_000)
        assertEquals(SegmentUi.None, c.ui)
        c.update(duration - 29_000)
        assertTrue(c.ui is SegmentUi.NextUp)
    }

    @Test fun `no next episode means no countdown and no autoplay`() {
        val c = controller(hasNext = false)
        assertNull(c.nextUpStartMs)
        assertNull(c.update(2_540_000))
        assertEquals(SegmentUi.None, c.ui)
        assertNull(c.onEnded())
        assertNull(c.onPlayNextPressed())
    }

    @Test fun `end of file plays next when the countdown never finished`() {
        val c = controller()
        c.update(2_595_000) // jumped straight to the last seconds
        assertEquals(SegmentCommand.PlayNext, c.onEnded())
    }

    @Test fun `autoplay off shows the card without counting down`() {
        val c = controller(settings = PlaybackSettings(autoplayNext = false))
        assertNull(c.update(2_501_000))
        assertNull(c.update(2_559_000))
        assertEquals(SegmentUi.NextUp(null, 10_000), c.ui)
        assertNull(c.onEnded())
        assertEquals(SegmentCommand.PlayNext, c.onPlayNextPressed())
    }

    @Test fun `configurable actions - outro as skip credits, intro ignored`() {
        val actions = PlaybackSettings.DEFAULT_SEGMENT_ACTIONS +
            mapOf(MediaSegmentType.OUTRO to SegmentAction.SKIP_BUTTON, MediaSegmentType.INTRO to SegmentAction.IGNORE)
        val c = controller(settings = PlaybackSettings(segmentActions = actions, nextUpBeforeEndMs = 30_000))
        c.update(61_000)
        assertEquals(SegmentUi.None, c.ui)
        c.update(2_501_000)
        assertEquals(SegmentUi.SkipButton(outro), c.ui)
    }

    @Test fun `tiny segments are ignored`() {
        val c = controller(segments = listOf(Segment(MediaSegmentType.INTRO, 10_000, 11_000)), hasNext = false)
        c.update(10_200)
        assertEquals(SegmentUi.None, c.ui)
    }

    @Test fun `ticks convert to ms`() {
        val dto = org.jellyfin.sdk.model.api.MediaSegmentDto(
            id = org.jellyfin.sdk.model.UUID(0, 1), itemId = org.jellyfin.sdk.model.UUID(0, 2),
            type = MediaSegmentType.OUTRO, startTicks = 25_072_130_000, endTicks = 25_686_550_000,
        )
        assertEquals(Segment(MediaSegmentType.OUTRO, 2_507_213, 2_568_655), Segment.from(dto))
    }
}
