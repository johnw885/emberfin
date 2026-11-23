package com.johnw.reelfin.playback

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.trickPlayApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.TrickplayInfoDto
import org.jellyfin.sdk.model.serializer.toUUID

/** One thumbnail inside a trickplay tile sheet. */
data class TrickplayFrame(val sheetIndex: Int, val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * Trickplay thumbnails: the server stores sheets of [TrickplayInfoDto.tileWidth] x
 * [TrickplayInfoDto.tileHeight] thumbnails, one every [TrickplayInfoDto.interval] ms.
 */
class Trickplay(val itemId: UUID, val mediaSourceId: String?, val info: TrickplayInfoDto) {

    fun frameAt(positionMs: Long): TrickplayFrame = frameAt(info, positionMs)

    fun sheetUrl(api: ApiClient, sheetIndex: Int): String =
        api.trickPlayApi.getTrickplayTileImageUrl(
            itemId = itemId,
            width = info.width,
            index = sheetIndex,
            mediaSourceId = mediaSourceId?.let { runCatching { it.toUUID() }.getOrNull() },
        )

    companion object {
        /** Picks the resolution closest to [preferredWidth] for [mediaSourceId] (or the item's first source). */
        fun from(item: BaseItemDto, mediaSourceId: String?, preferredWidth: Int = 320): Trickplay? {
            val byWidth = item.trickplay?.let { map -> mediaSourceId?.let { map[it] } ?: map.values.firstOrNull() } ?: return null
            val info = byWidth.values.minByOrNull { kotlin.math.abs(it.width - preferredWidth) } ?: return null
            if (info.thumbnailCount <= 0 || info.interval <= 0 || info.tileWidth <= 0 || info.tileHeight <= 0) return null
            return Trickplay(item.id, mediaSourceId, info)
        }

        fun frameAt(info: TrickplayInfoDto, positionMs: Long): TrickplayFrame {
            val index = (positionMs.coerceAtLeast(0) / info.interval).toInt().coerceAtMost(info.thumbnailCount - 1)
            val perSheet = info.tileWidth * info.tileHeight
            val inSheet = index % perSheet
            return TrickplayFrame(
                sheetIndex = index / perSheet,
                x = (inSheet % info.tileWidth) * info.width,
                y = (inSheet / info.tileWidth) * info.height,
                width = info.width,
                height = info.height,
            )
        }
    }
}
