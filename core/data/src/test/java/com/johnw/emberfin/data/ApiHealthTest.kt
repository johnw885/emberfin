package com.johnw.emberfin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiHealthTest {
    @Test fun `401 with a token means the sign-in was rejected`() {
        assertEquals(ApiHealth.Verdict.AuthRejected, ApiHealth.classify(401, "abc"))
    }

    @Test fun `401 without a token is just a failed sign-in attempt`() {
        assertEquals(ApiHealth.Verdict.Ok, ApiHealth.classify(401, null))
        assertEquals(ApiHealth.Verdict.Ok, ApiHealth.classify(401, ""))
    }

    @Test fun `other statuses are not auth failures`() {
        listOf(200, 204, 403, 404, 500).forEach { assertEquals(ApiHealth.Verdict.Ok, ApiHealth.classify(it, "abc")) }
    }

    @Test fun `token is read from the MediaBrowser header`() {
        val h = "MediaBrowser Client=\"Emberfin\", Device=\"TV\", DeviceId=\"d1\", Version=\"0.1\", Token=\"t0k3n\""
        assertEquals("t0k3n", ApiHealth.tokenOf(h))
        assertNull(ApiHealth.tokenOf("MediaBrowser Client=\"Emberfin\", DeviceId=\"d1\""))
        assertNull(ApiHealth.tokenOf(null))
    }

    @Test fun `origin ignores the path but not the port`() {
        assertEquals(ApiHealth.origin("http://tv.example:8096/Items"), ApiHealth.origin("http://tv.example:8096"))
        assertNotEquals(ApiHealth.origin("http://tv.example:8096"), ApiHealth.origin("http://tv.example:8920"))
        assertNull(ApiHealth.origin("not a url"))
    }

    @Test fun `unreachable is found through causes`() {
        assertTrue(RuntimeException(java.net.ConnectException("refused")).isUnreachable())
        assertTrue(org.jellyfin.sdk.api.client.exception.TimeoutException("slow").isUnreachable())
        assertFalse(IllegalStateException("bug").isUnreachable())
    }
}
