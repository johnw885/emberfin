package com.johnw.emberfin.data

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CollectionsTest {
    private fun movie(n: Long) = BaseItemDto(id = UUID(0L, n), name = "Movie $n", type = BaseItemKind.MOVIE)
    private fun set(n: Long) = BaseItemDto(id = UUID(1L, n), name = "Set $n", type = BaseItemKind.BOX_SET)
    private fun names(items: List<BaseItemDto>) = items.map { it.name }

    // --- Date window -------------------------------------------------------------------------

    @Test fun `window opens on November 15`() {
        assertFalse(ChristmasSeason.isOn(LocalDate.of(2026, 11, 14)))
        assertTrue(ChristmasSeason.isOn(LocalDate.of(2026, 11, 15)))
    }

    @Test fun `window closes after January 1`() {
        assertTrue(ChristmasSeason.isOn(LocalDate.of(2027, 1, 1)))
        assertFalse(ChristmasSeason.isOn(LocalDate.of(2027, 1, 2)))
    }

    @Test fun `window spans the year boundary`() {
        assertTrue(ChristmasSeason.isOn(LocalDate.of(2026, 12, 1)))
        assertTrue(ChristmasSeason.isOn(LocalDate.of(2026, 12, 25)))
        assertTrue(ChristmasSeason.isOn(LocalDate.of(2026, 12, 31)))
        assertTrue(ChristmasSeason.isOn(LocalDate.of(2028, 1, 1)))
        assertFalse(ChristmasSeason.isOn(LocalDate.of(2027, 1, 31)))
        assertFalse(ChristmasSeason.isOn(LocalDate.of(2027, 7, 4)))
        assertFalse(ChristmasSeason.isOn(LocalDate.of(2026, 10, 31)))
        assertFalse(ChristmasSeason.isOn(LocalDate.of(2026, 11, 1)))
    }

    // --- Grouping ----------------------------------------------------------------------------

    @Test fun `members collapse into the collection at the first member's place`() {
        val a = set(1)
        val membership = mapOf(movie(2).id to a, movie(4).id to a)
        val out = CollectionGrouping.collapse(listOf(movie(1), movie(2), movie(3), movie(4), movie(5)), membership)
        assertEquals(listOf("Movie 1", "Set 1", "Movie 3", "Movie 5"), names(out))
    }

    @Test fun `titles outside any collection are untouched`() {
        val items = listOf(movie(1), movie(2), movie(3))
        assertEquals(items, CollectionGrouping.collapse(items, mapOf(movie(9).id to set(1))))
        assertEquals(items, CollectionGrouping.collapse(items, emptyMap()))
    }

    @Test fun `a single member still shows as its collection by default`() {
        val out = CollectionGrouping.collapse(listOf(movie(1), movie(2)), mapOf(movie(2).id to set(1)))
        assertEquals(listOf("Movie 1", "Set 1"), names(out))
    }

    @Test fun `minMembers keeps a lone member as itself`() {
        val a = set(1)
        val b = set(2)
        val membership = mapOf(movie(1).id to a, movie(2).id to b, movie(3).id to b)
        val out = CollectionGrouping.collapse(listOf(movie(1), movie(2), movie(3), movie(4)), membership, minMembers = 2)
        assertEquals(listOf("Movie 1", "Set 2", "Movie 4"), names(out))
    }

    @Test fun `the collection itself and its members give one tile`() {
        val a = set(1)
        val out = CollectionGrouping.collapse(listOf(a, movie(1), movie(1)), mapOf(movie(1).id to a))
        assertEquals(listOf("Set 1"), names(out))
    }

    @Test fun `several collections in one row`() {
        val a = set(1)
        val b = set(2)
        val membership = mapOf(movie(1).id to a, movie(2).id to b, movie(3).id to a, movie(4).id to b)
        val out = CollectionGrouping.collapse((1L..5L).map(::movie), membership)
        assertEquals(listOf("Set 1", "Set 2", "Movie 5"), names(out))
    }
}
