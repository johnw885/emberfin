package com.johnw.reelfin.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jellyfin.sdk.model.api.BaseItemDto

/** One cached row: its key, title, tile style name and items. */
data class CachedRow(val key: String, val title: String, val style: String, val items: List<BaseItemDto>)

data class CachedScreen(val billboard: BaseItemDto?, val rows: List<CachedRow>, val savedAt: Long)

/** Last-seen rows per screen and profile, so Home appears instantly on a warm start. */
interface RowCache {
    suspend fun load(key: String): CachedScreen?
    suspend fun save(key: String, billboard: BaseItemDto?, rows: List<CachedRow>)
    suspend fun clear(prefix: String)
}

/** Shared JSON encoding of cached rows (the SDK's models are kotlinx-serializable). */
object RowCodec {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val itemList = ListSerializer(BaseItemDto.serializer())

    fun encodeItems(items: List<BaseItemDto>): String = json.encodeToString(itemList, items)
    fun decodeItems(text: String): List<BaseItemDto> = json.decodeFromString(itemList, text)
    fun encodeItem(item: BaseItemDto): String = json.encodeToString(BaseItemDto.serializer(), item)
    fun decodeItem(text: String): BaseItemDto = json.decodeFromString(BaseItemDto.serializer(), text)

    fun encodeScreen(billboard: BaseItemDto?, rows: List<CachedRow>, savedAt: Long): String = buildJsonObject {
        put("savedAt", savedAt)
        if (billboard != null) put("billboard", json.encodeToJsonElement(BaseItemDto.serializer(), billboard))
        put("rows", buildJsonArray {
            rows.forEach { r ->
                add(buildJsonObject {
                    put("key", r.key)
                    put("title", r.title)
                    put("style", r.style)
                    put("items", json.encodeToJsonElement(itemList, r.items))
                })
            }
        })
    }.toString()

    fun decodeScreen(text: String): CachedScreen {
        val o = json.parseToJsonElement(text).jsonObject
        val billboard = (o["billboard"] as? JsonObject)?.let { json.decodeFromJsonElement(BaseItemDto.serializer(), it) }
        val rows = o["rows"]!!.jsonArray.map { e ->
            val r = e.jsonObject
            CachedRow(
                key = r["key"]!!.jsonPrimitive.content,
                title = r["title"]!!.jsonPrimitive.content,
                style = r["style"]!!.jsonPrimitive.content,
                items = json.decodeFromJsonElement(itemList, r["items"]!!),
            )
        }
        return CachedScreen(billboard, rows, o["savedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0)
    }
}
