package com.johnw.emberfin.data

import org.jellyfin.sdk.model.api.RecommendationDto
import org.jellyfin.sdk.model.api.RecommendationType
import org.jellyfin.sdk.model.api.UserItemDataDto

/**
 * Netflix-style rating: not for me / I like this / love this. Stored in Jellyfin's per-user
 * `Rating` (0–10), which the server also exposes as `Likes` (rating ≥ 6.5 = liked), so other
 * clients and the server's "similar to liked" recommendations still see a like or dislike.
 * Love is kept apart from My List (favourites) on purpose: loving a title doesn't add it there.
 */
enum class Thumbs(val rating: Double?) {
    None(null), Down(1.0), Up(8.0), Love(10.0);

    companion object {
        fun of(data: UserItemDataDto?): Thumbs {
            val rating = data?.rating
            return when {
                rating != null && rating >= 9.5 -> Love
                rating != null && rating >= 6.5 -> Up
                rating != null -> Down
                data?.likes == true -> Up
                data?.likes == false -> Down
                else -> None
            }
        }
    }
}

/** Row titles for the server's movie recommendation categories. */
object Recommendations {
    fun title(r: RecommendationDto): String? {
        val name = r.baselineItemName?.takeIf { it.isNotBlank() } ?: return null
        return when (r.recommendationType) {
            RecommendationType.SIMILAR_TO_RECENTLY_PLAYED -> "Because you watched $name"
            RecommendationType.SIMILAR_TO_LIKED_ITEM -> "Because you liked $name"
            RecommendationType.HAS_DIRECTOR_FROM_RECENTLY_PLAYED, RecommendationType.HAS_LIKED_DIRECTOR -> "Directed by $name"
            RecommendationType.HAS_ACTOR_FROM_RECENTLY_PLAYED, RecommendationType.HAS_LIKED_ACTOR -> "Starring $name"
        }
    }

    /** "Because you…" categories first, then people; at most [max] rows, each with at least [minItems]. */
    fun pick(all: List<RecommendationDto>, max: Int = 2, minItems: Int = 3): List<Pair<String, RecommendationDto>> =
        all.filter { it.items.orEmpty().size >= minItems }
            .mapNotNull { r -> title(r)?.let { it to r } }
            .sortedBy { (_, r) ->
                when (r.recommendationType) {
                    RecommendationType.SIMILAR_TO_RECENTLY_PLAYED -> 0
                    RecommendationType.SIMILAR_TO_LIKED_ITEM -> 1
                    else -> 2
                }
            }
            .distinctBy { it.first }
            .take(max)
}
