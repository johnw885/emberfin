package com.johnw.emberfin.browse

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillboardTest {
    private fun item(n: Int, name: String = "Title $n") =
        BaseItemDto(id = UUID(0L, n.toLong()), type = BaseItemKind.MOVIE, name = name)

    @Test fun firstRunShowsFirstFeaturedAndCachesAnother() {
        val pick = Billboard.pick(null, listOf(item(1), item(2), item(3)))
        assertEquals(item(1).id, pick.show?.id)
        assertEquals(item(2).id, pick.next?.id)
    }

    @Test fun keepsCurrentTitleDuringARun() {
        val pick = Billboard.pick(item(7), listOf(item(1), item(2)))
        assertEquals(item(7).id, pick.show?.id)
        assertEquals(item(1).id, pick.next?.id)
    }

    @Test fun refreshesCurrentTitleWithServerCopy() {
        val fresh = item(2, name = "Renamed")
        val pick = Billboard.pick(item(2), listOf(item(1), fresh))
        assertEquals("Renamed", pick.show?.name)
        assertEquals(item(1).id, pick.next?.id)
    }

    @Test fun singleCandidateIsBothShownAndNext() {
        val pick = Billboard.pick(null, listOf(item(4)))
        assertEquals(item(4).id, pick.show?.id)
        assertEquals(item(4).id, pick.next?.id)
    }

    @Test fun nothingFeatured() {
        val pick = Billboard.pick(null, emptyList())
        assertNull(pick.show)
        assertNull(pick.next)
    }
}
