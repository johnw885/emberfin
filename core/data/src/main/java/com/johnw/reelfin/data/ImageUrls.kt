package com.johnw.reelfin.data

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType

/** Sized image URLs, so the server resizes instead of sending full-resolution art. */
object ImageUrls {
    private const val QUALITY = 90

    /** A 16:9 image for landscape tiles: thumb, episode still, backdrop, then the parent's backdrop. */
    fun landscape(api: ApiClient, item: BaseItemDto, width: Int): String? {
        val images = api.imageApi
        item.imageTags?.get(ImageType.THUMB)?.let { tag ->
            return images.getItemImageUrl(item.id, ImageType.THUMB, fillWidth = width, quality = QUALITY, tag = tag)
        }
        if (item.type == BaseItemKind.EPISODE) {
            item.imageTags?.get(ImageType.PRIMARY)?.let { tag ->
                return images.getItemImageUrl(item.id, ImageType.PRIMARY, fillWidth = width, quality = QUALITY, tag = tag)
            }
        }
        item.backdropImageTags?.firstOrNull()?.let { tag ->
            return images.getItemImageUrl(item.id, ImageType.BACKDROP, fillWidth = width, quality = QUALITY, tag = tag)
        }
        val parent = item.parentBackdropItemId
        val parentTag = item.parentBackdropImageTags?.firstOrNull()
        if (parent != null && parentTag != null) {
            return images.getItemImageUrl(parent, ImageType.BACKDROP, fillWidth = width, quality = QUALITY, tag = parentTag)
        }
        return null
    }
}
