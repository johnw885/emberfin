package com.johnw.reelfin.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurHashTest {
    // The example hash from the BlurHash spec (a warm photo: browns and reds).
    private val sample = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"

    @Test fun decodesToRequestedSize() {
        val px = BlurHash.decode(sample, 8, 6)
        assertNotNull(px)
        assertEquals(48, px!!.size)
        assertTrue(px.all { it ushr 24 == 0xFF })
    }

    @Test fun averageColourMatchesDcComponent() {
        // The AC terms are cosines that average out, so a large decode averages to the DC colour,
        // which for this hash is #979695 ("HV6n" in base 83).
        val px = BlurHash.decode(sample, 64, 64)!!
        fun avg(shift: Int) = px.map { (it shr shift) and 255 }.average()
        assertEquals(0x97.toDouble(), avg(16), 25.0)
        assertEquals(0x96.toDouble(), avg(8), 25.0)
        assertEquals(0x95.toDouble(), avg(0), 25.0)
    }

    @Test fun flatHashIsUniform() {
        // 1x1 components, DC only: every pixel is the same colour.
        val flat = "00TI7]" // sizeFlag 0, quant 0, DC = some colour
        val px = BlurHash.decode(flat, 4, 4)!!
        assertTrue(px.all { it == px[0] })
    }

    @Test fun rejectsBadInput() {
        assertNull(BlurHash.decode("", 4, 4))
        assertNull(BlurHash.decode("LEHV6n", 4, 4)) // wrong length for its size flag
        assertNull(BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdné", 4, 4)) // bad character
    }
}
