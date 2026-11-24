package com.johnw.emberfin.data

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType

/** A sized image URL and the BlurHash Jellyfin sent for that exact image tag. */
data class ImageRef(val url: String, val blurHash: String?)

/**
 * Sized image URLs, so the server resizes instead of sending full-resolution art.
 * Widths are in pixels: the TV renders at 1920×1080, so a 204 dp tile wants ~420 px.
 */
object ImageUrls {
    private const val QUALITY = 90

    /** Pixel widths for each use. Tile widths are 2× their dp size (1080p = 2 px per dp). */
    object Width {
        const val LANDSCAPE = 440
        const val LANDSCAPE_LARGE = 560
        const val POSTER = 280
        const val PERSON = 220
        const val BACKDROP = 1920
        const val LOGO = 640
        const val AVATAR = 200
    }

    private fun url(api: ApiClient, id: UUID, type: ImageType, tag: String, width: Int, index: Int? = null): String =
        api.imageApi.getItemImageUrl(id, type, fillWidth = width, quality = QUALITY, tag = tag, imageIndex = index)

    private fun blur(map: Map<ImageType, Map<String, String>>?, type: ImageType, tag: String): String? = map?.get(type)?.get(tag)

    /** 16:9 for landscape tiles: thumb, episode still, backdrop, then the parent's thumb or backdrop. */
    fun landscape(api: ApiClient, item: BaseItemDto, width: Int = Width.LANDSCAPE): ImageRef? {
        val blurs = item.imageBlurHashes
        item.imageTags?.get(ImageType.THUMB)?.let { return ImageRef(url(api, item.id, ImageType.THUMB, it, width), blur(blurs, ImageType.THUMB, it)) }
        if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.VIDEO) {
            item.imageTags?.get(ImageType.PRIMARY)?.let { return ImageRef(url(api, item.id, ImageType.PRIMARY, it, width), blur(blurs, ImageType.PRIMARY, it)) }
        }
        item.backdropImageTags?.firstOrNull()?.let { return ImageRef(url(api, item.id, ImageType.BACKDROP, it, width, 0), blur(blurs, ImageType.BACKDROP, it)) }
        val thumbParent = item.parentThumbItemId
        val thumbTag = item.parentThumbImageTag
        if (thumbParent != null && thumbTag != null) return ImageRef(url(api, thumbParent, ImageType.THUMB, thumbTag, width), blur(blurs, ImageType.THUMB, thumbTag))
        return parentBackdrop(api, item, width)
    }

    /** Whether [landscape] would return art that already shows the title (a Thumb usually does). */
    fun landscapeHasTitle(item: BaseItemDto): Boolean =
        item.imageTags?.containsKey(ImageType.THUMB) == true || item.parentThumbImageTag != null

    /** 2:3 poster: the item's primary, or the series' primary for episodes and seasons. */
    fun poster(api: ApiClient, item: BaseItemDto, width: Int = Width.POSTER): ImageRef? {
        val blurs = item.imageBlurHashes
        if (item.type != BaseItemKind.EPISODE) {
            item.imageTags?.get(ImageType.PRIMARY)?.let { return ImageRef(url(api, item.id, ImageType.PRIMARY, it, width), blur(blurs, ImageType.PRIMARY, it)) }
        }
        val sid = item.seriesId
        val stag = item.seriesPrimaryImageTag
        if (sid != null && stag != null) return ImageRef(url(api, sid, ImageType.PRIMARY, stag, width), blur(blurs, ImageType.PRIMARY, stag))
        item.imageTags?.get(ImageType.PRIMARY)?.let { return ImageRef(url(api, item.id, ImageType.PRIMARY, it, width), blur(blurs, ImageType.PRIMARY, it)) }
        return null
    }

    /** Full-bleed backdrop for the billboard, hero and details page. */
    fun backdrop(api: ApiClient, item: BaseItemDto, width: Int = Width.BACKDROP): ImageRef? {
        val blurs = item.imageBlurHashes
        item.backdropImageTags?.firstOrNull()?.let { return ImageRef(url(api, item.id, ImageType.BACKDROP, it, width, 0), blur(blurs, ImageType.BACKDROP, it)) }
        parentBackdrop(api, item, width)?.let { return it }
        if (item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.VIDEO) {
            item.imageTags?.get(ImageType.PRIMARY)?.let { return ImageRef(url(api, item.id, ImageType.PRIMARY, it, width), blur(blurs, ImageType.PRIMARY, it)) }
        }
        return null
    }

    private fun parentBackdrop(api: ApiClient, item: BaseItemDto, width: Int): ImageRef? {
        val parent = item.parentBackdropItemId ?: return null
        val tag = item.parentBackdropImageTags?.firstOrNull() ?: return null
        return ImageRef(url(api, parent, ImageType.BACKDROP, tag, width, 0), blur(item.imageBlurHashes, ImageType.BACKDROP, tag))
    }

    /** Clear logo (transparent PNG title treatment): the item's, or its series'. */
    fun logo(api: ApiClient, item: BaseItemDto, width: Int = Width.LOGO): ImageRef? {
        item.imageTags?.get(ImageType.LOGO)?.let { return ImageRef(url(api, item.id, ImageType.LOGO, it, width), null) }
        val parent = item.parentLogoItemId
        val tag = item.parentLogoImageTag
        if (parent != null && tag != null) return ImageRef(url(api, parent, ImageType.LOGO, tag, width), null)
        return null
    }

    fun person(api: ApiClient, person: BaseItemPerson, width: Int = Width.PERSON): ImageRef? {
        val tag = person.primaryImageTag ?: return null
        return ImageRef(url(api, person.id, ImageType.PRIMARY, tag, width), blur(person.imageBlurHashes, ImageType.PRIMARY, tag))
    }

    fun user(api: ApiClient, userId: UUID, tag: String?, width: Int = Width.AVATAR): ImageRef? {
        if (tag == null) return null
        return ImageRef(api.imageApi.getUserImageUrl(userId = userId, tag = tag) + "&fillWidth=$width&quality=$QUALITY", null)
    }
}
