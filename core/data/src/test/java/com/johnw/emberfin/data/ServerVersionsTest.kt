package com.johnw.emberfin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerVersionsTest {
    @Test fun `12 and newer are supported`() {
        listOf("12.0.0", "12.4.0", "12.4", "13.0.0", "12.0.0-rc1").forEach { assertTrue(it, ServerVersions.isSupported(it)) }
    }

    @Test fun `older servers are refused`() {
        listOf("10.11.5", "10.10.7", "11.9.9").forEach { assertFalse(it, ServerVersions.isSupported(it)) }
    }

    @Test fun `unknown versions are let through`() {
        assertTrue(ServerVersions.isSupported(""))
        assertTrue(ServerVersions.isSupported("unstable"))
    }

    @Test fun `parse splits dotted numbers`() {
        assertEquals(listOf(12, 4, 0), ServerVersions.parse("12.4.0"))
        assertNull(ServerVersions.parse("x.y"))
    }
}
