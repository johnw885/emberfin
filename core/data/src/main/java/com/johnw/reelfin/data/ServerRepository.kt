package com.johnw.reelfin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.UserDto

data class ServerInfo(val url: String, val id: String, val name: String, val version: String)

class ServerRepository(private val factory: JellyfinClientFactory) {
    /** Normalizes [input] (adds http://, strips a trailing slash) and checks `/System/Info/Public`. */
    suspend fun check(input: String): Result<ServerInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = normalize(input)
            val info = factory.create(url).systemApi.getPublicSystemInfo().content
            ServerInfo(url = url, id = info.id ?: url, name = info.serverName ?: "Jellyfin", version = info.version ?: "?")
        }
    }

    /** Users the server shows on its login screen (often none: admins usually hide them). */
    suspend fun publicUsers(url: String): List<UserDto> = withContext(Dispatchers.IO) {
        runCatching { factory.create(url).userApi.getPublicUsers().content }.getOrDefault(emptyList())
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
