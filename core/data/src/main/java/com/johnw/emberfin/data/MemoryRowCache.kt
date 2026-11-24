package com.johnw.emberfin.data

import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.concurrent.ConcurrentHashMap

/** Process-lifetime cache (tests and fallback). The app uses the on-disk [RowCache]. */
class MemoryRowCache : RowCache {
    private val map = ConcurrentHashMap<String, CachedScreen>()
    override suspend fun load(key: String): CachedScreen? = map[key]
    override suspend fun save(key: String, billboard: BaseItemDto?, rows: List<CachedRow>) {
        map[key] = CachedScreen(billboard, rows, System.currentTimeMillis())
    }
    override suspend fun clear(prefix: String) { map.keys.removeAll { it.startsWith(prefix) } }
}
