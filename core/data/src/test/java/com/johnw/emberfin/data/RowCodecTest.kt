package com.johnw.emberfin.data

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowCodecTest {
    private fun item(n: Long) = BaseItemDto(
        id = UUID(0L, n), type = BaseItemKind.MOVIE, name = "Synthetic $n", productionYear = 2000 + n.toInt(),
        imageTags = mapOf(ImageType.PRIMARY to "tag$n"),
        imageBlurHashes = mapOf(ImageType.PRIMARY to mapOf("tag$n" to "LEHV6nWB2yk8pyo0adR*.7kCMdnj")),
    )

    @Test fun screenRoundTrip() {
        val rows = listOf(
            CachedRow("resume", "Continue Watching", "Landscape", listOf(item(1), item(2))),
            CachedRow("genre/x", "Drama", "Poster", listOf(item(3))),
        )
        val text = RowCodec.encodeScreen(item(9), rows, savedAt = 1234L)
        val back = RowCodec.decodeScreen(text)
        assertEquals(1234L, back.savedAt)
        assertEquals(item(9).id, back.billboard?.id)
        assertEquals(rows.map { it.key }, back.rows.map { it.key })
        assertEquals(rows.map { it.title }, back.rows.map { it.title })
        assertEquals(rows.map { it.style }, back.rows.map { it.style })
        assertEquals(listOf(item(1).id, item(2).id), back.rows[0].items.map { it.id })
        assertEquals("LEHV6nWB2yk8pyo0adR*.7kCMdnj", back.rows[0].items[0].imageBlurHashes?.get(ImageType.PRIMARY)?.get("tag1"))
    }

    @Test fun screenWithoutBillboard() {
        val back = RowCodec.decodeScreen(RowCodec.encodeScreen(null, emptyList(), 0))
        assertNull(back.billboard)
        assertEquals(0, back.rows.size)
    }

    @Test fun accountKeyIsServerAndUser() {
        val a = Account("srv", "http://example.invalid:8096", "Server", "user-1", "Someone", "not-a-real-token")
        assertEquals("srv/user-1", a.key)
    }

    @Test fun normalizeServerAddress() {
        assertEquals("http://example.invalid:8096", ServerRepository.normalize(" example.invalid:8096/ "))
        assertEquals("https://example.invalid", ServerRepository.normalize("https://example.invalid/"))
    }
}
