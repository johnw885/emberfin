package com.johnw.emberfin.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillWatchingPolicyTest {
    private var now = 0L
    private fun policy(enabled: Boolean = true) = StillWatchingPolicy(enabled, now = { now })

    @Test fun `asks after the third episode in a row without input`() {
        val p = policy()
        now += 45 * 60_000
        assertFalse(p.shouldAskBeforeAutoplay())
        now += 45 * 60_000
        assertFalse(p.shouldAskBeforeAutoplay())
        now += 20 * 60_000
        assertTrue(p.shouldAskBeforeAutoplay())
    }

    @Test fun `a key press starts the count again`() {
        val p = policy()
        p.shouldAskBeforeAutoplay()
        p.shouldAskBeforeAutoplay()
        p.onInput()
        assertFalse(p.shouldAskBeforeAutoplay())
        assertFalse(p.shouldAskBeforeAutoplay())
        assertTrue(p.shouldAskBeforeAutoplay())
    }

    @Test fun `asks after two hours without input even before three episodes`() {
        val p = policy()
        now += 70 * 60_000
        assertFalse(p.shouldAskBeforeAutoplay())
        now += 55 * 60_000
        assertTrue(p.shouldAskBeforeAutoplay())
    }

    @Test fun `never asks when the setting is off`() {
        val p = policy(enabled = false)
        now += 10 * 60 * 60_000
        repeat(10) { assertFalse(p.shouldAskBeforeAutoplay()) }
    }
}
