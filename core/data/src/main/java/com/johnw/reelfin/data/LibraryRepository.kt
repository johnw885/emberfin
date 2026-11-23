package com.johnw.reelfin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
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
}
