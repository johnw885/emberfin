package com.johnw.emberfin.player

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import coil3.toBitmap
import com.johnw.emberfin.playback.Trickplay
import org.jellyfin.sdk.api.client.ApiClient

/**
 * Keeps the last few tile sheets in memory so scrubbing back and forth doesn't refetch. A
 * 10x10 sheet of 320 px thumbnails is about 11 MB in RGB_565, so only a few are kept.
 */
class TrickplaySheets {
    val cache = LruCache<Int, Bitmap>(3)
}

/**
 * The scrub preview: loads the tile sheet holding [positionMs] (Coil, with the auth header the
 * trickplay endpoint needs) and draws just that thumbnail.
 */
@Composable
fun TrickplayThumb(trickplay: Trickplay, positionMs: Long, api: ApiClient, authorizationHeader: String, sheets: TrickplaySheets, width: Dp = 320.dp) {
    val context = LocalContext.current
    val frame = trickplay.frameAt(positionMs)
    val sheet by produceState(sheets.cache.get(frame.sheetIndex), frame.sheetIndex) {
        sheets.cache.get(frame.sheetIndex)?.let { value = it; return@produceState }
        val request = ImageRequest.Builder(context)
            .data(trickplay.sheetUrl(api, frame.sheetIndex))
            .httpHeaders(NetworkHeaders.Builder().set("Authorization", authorizationHeader).build())
            .allowHardware(false)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val bitmap = SingletonImageLoader.get(context).execute(request).image?.toBitmap()
        if (bitmap != null) {
            sheets.cache.put(frame.sheetIndex, bitmap)
            value = bitmap
        }
    }
    val image = remember(sheet) { sheet?.asImageBitmap() }
    val height = width * frame.height / frame.width
    Canvas(
        Modifier
            .size(width, height)
            .border(2.dp, Color.White, RoundedCornerShape(4.dp)),
    ) {
        val img = image ?: return@Canvas
        drawImage(
            img,
            srcOffset = IntOffset(frame.x, frame.y),
            srcSize = IntSize(frame.width.coerceAtMost(img.width - frame.x), frame.height.coerceAtMost(img.height - frame.y)),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}
