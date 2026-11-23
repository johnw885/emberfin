package com.johnw.reelfin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.systemApi

data class ServerInfo(val url: String, val name: String, val version: String)

class ServerRepository(private val factory: JellyfinClientFactory) {
    /** Normalizes [input] (adds http://, strips a trailing slash) and checks `/System/Info/Public`. */
    suspend fun check(input: String): Result<ServerInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalize(input)
            val info = factory.create(url).systemApi.getPublicSystemInfo().content
            ServerInfo(url = url, name = info.serverName ?: "Jellyfin", version = info.version ?: "?")
        }
    }

    companion object {
        fun normalize(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Enter a server address" }
            return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed
            else "http://$trimmed"
        }
    }
}
