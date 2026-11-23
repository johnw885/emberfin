package com.johnw.reelfin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.serializer.toUUID

data class HomeRow(val title: String, val items: List<BaseItemDto>)

class LibraryRepository {
    private val imageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP)

    /** Continue Watching, or Recently Added when nothing is in progress (spike: one row). */
    suspend fun firstRow(api: ApiClient, userId: String): HomeRow = withContext(Dispatchers.IO) {
        val uid = userId.toUUID()
        val resume = api.libraryApi.getResumeItems(
            userId = uid,
            limit = 20,
            mediaTypes = listOf(MediaType.VIDEO),
            enableUserData = true,
            enableImageTypes = imageTypes,
            imageTypeLimit = 1,
        ).content.items
        if (resume.isNotEmpty()) return@withContext HomeRow("Continue Watching", resume)

        val latest = api.libraryApi.getLatestMedia(
            userId = uid,
            limit = 20,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
            enableUserData = true,
            enableImageTypes = imageTypes,
            imageTypeLimit = 1,
            groupItems = false,
        ).content
        HomeRow("Recently Added", latest)
    }

    /** One item with everything the player needs (user data, media sources, trickplay). */
    suspend fun item(api: ApiClient, userId: UUID, itemId: UUID): BaseItemDto = withContext(Dispatchers.IO) {
        api.libraryApi.getItem(itemId = itemId, userId = userId).content
    }

    /** The episode after [episode] in its series (across seasons), or null for the last one or a non-episode. */
    suspend fun nextEpisode(api: ApiClient, userId: UUID, episode: BaseItemDto): BaseItemDto? = withContext(Dispatchers.IO) {
        if (episode.type != BaseItemKind.EPISODE) return@withContext null
        val seriesId = episode.seriesId ?: return@withContext null
        val items = api.showApi.getEpisodes(
            seriesId = seriesId,
            userId = userId,
            startItemId = episode.id,
            limit = 2,
            enableUserData = true,
            enableImages = true,
            enableImageTypes = imageTypes,
            imageTypeLimit = 1,
        ).content.items
        items.dropWhile { it.id == episode.id }.firstOrNull()
    }
}
