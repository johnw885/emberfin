package com.johnw.emberfin.data

import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.RecommendationDto
import org.jellyfin.sdk.model.api.RecommendationType
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbsAndLocksTest {
    private fun data(rating: Double? = null, likes: Boolean? = null) = UserItemDataDto(
        rating = rating, likes = likes, playbackPositionTicks = 0, playCount = 0, isFavorite = false,
        played = false, key = "k", itemId = UUID.randomUUID(),
    )

    @Test fun `thumbs read from the rating, then likes`() {
        assertEquals(Thumbs.None, Thumbs.of(null))
        assertEquals(Thumbs.None, Thumbs.of(data()))
        assertEquals(Thumbs.Love, Thumbs.of(data(rating = 10.0, likes = true)))
        assertEquals(Thumbs.Up, Thumbs.of(data(rating = 8.0, likes = true)))
        assertEquals(Thumbs.Down, Thumbs.of(data(rating = 1.0, likes = false)))
        // Another client's plain like/dislike without a rating.
        assertEquals(Thumbs.Up, Thumbs.of(data(likes = true)))
        assertEquals(Thumbs.Down, Thumbs.of(data(likes = false)))
    }

    @Test fun `thumbs ratings round trip and match the server's like threshold`() {
        for (t in Thumbs.entries) {
            val r = t.rating
            assertEquals(t, Thumbs.of(data(rating = r, likes = r?.let { it >= 6.5 })))
        }
        assertTrue(Thumbs.Up.rating!! >= 6.5)
        assertTrue(Thumbs.Down.rating!! < 6.5)
    }

    private fun movie(name: String) = BaseItemDto(id = UUID.randomUUID(), name = name, type = BaseItemKind.MOVIE)

    private fun rec(type: RecommendationType, name: String?, count: Int) = RecommendationDto(
        items = List(count) { movie("m$it") }, recommendationType = type, baselineItemName = name, categoryId = UUID.randomUUID(),
    )

    @Test fun `recommendation rows put because-you-watched first and skip thin ones`() {
        val all = listOf(
            rec(RecommendationType.HAS_ACTOR_FROM_RECENTLY_PLAYED, "Sam Neill", 8),
            rec(RecommendationType.SIMILAR_TO_RECENTLY_PLAYED, "Dune", 2),
            rec(RecommendationType.SIMILAR_TO_LIKED_ITEM, "Jurassic Park", 6),
            rec(RecommendationType.SIMILAR_TO_RECENTLY_PLAYED, null, 9),
        )
        val picked = Recommendations.pick(all).map { it.first }
        assertEquals(listOf("Because you liked Jurassic Park", "Starring Sam Neill"), picked)
    }

    @Test fun `recommendation titles`() {
        assertEquals("Because you watched Dune", Recommendations.title(rec(RecommendationType.SIMILAR_TO_RECENTLY_PLAYED, "Dune", 3)))
        assertEquals("Directed by Ridley Scott", Recommendations.title(rec(RecommendationType.HAS_LIKED_DIRECTOR, "Ridley Scott", 3)))
        assertEquals(null, Recommendations.title(rec(RecommendationType.HAS_LIKED_ACTOR, " ", 3)))
    }

    @Test fun `pin is checked against a salted hash`() {
        val lock = ProfileLock(kids = true).withPin("4821")
        assertTrue(lock.hasPin)
        assertTrue(lock.kids)
        assertTrue(lock.checkPin("4821"))
        assertFalse(lock.checkPin("4822"))
        assertFalse(lock.pinHash!!.contains("4821"))
        // Same PIN, different salt, different hash.
        assertNotEquals(lock.pinHash, ProfileLock().withPin("4821").pinHash)
        val removed = lock.withPin(null)
        assertFalse(removed.hasPin)
        assertFalse(removed.checkPin("4821"))
        assertFalse(ProfileLock().checkPin("0000"))
    }

    @Test fun `pin format`() {
        assertTrue(Pin.isValid("0000"))
        assertFalse(Pin.isValid("123"))
        assertFalse(Pin.isValid("12a4"))
        assertFalse(Pin.isValid("12345"))
    }

    @Test fun `locks survive the json round trip`() {
        val lock = ProfileLock(kids = true).withPin("1234")
        val text = RowCodec.json.encodeToString(ProfileLock.serializer(), lock)
        val back = RowCodec.json.decodeFromString(ProfileLock.serializer(), text)
        assertEquals(lock, back)
        assertTrue(back.checkPin("1234"))
    }
}
