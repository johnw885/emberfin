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
            val version = info.version.orEmpty()
            if (!ServerVersions.isSupported(version)) throw UnsupportedServerException(version)
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

class UnsupportedServerException(val version: String) :
    Exception("this server runs Jellyfin $version; Reelfin needs ${ServerVersions.MINIMUM} or newer")

/** Reelfin supports Jellyfin 12.0 and newer (jellyfin-sdk-kotlin 1.9's own minimum; decided 2026-11-23). */
object ServerVersions {
    const val MINIMUM = "12.0.0"

    /** "12.4.0" → [12, 4, 0]; null for anything that isn't a dotted version. */
    fun parse(version: String): List<Int>? {
        val parts = version.trim().substringBefore('-').split('.')
        if (parts.isEmpty() || parts.any { it.toIntOrNull() == null }) return null
        return parts.map { it.toInt() }
    }

    /** Unknown versions are let through: better to try than to lock someone out. */
    fun isSupported(version: String): Boolean {
        val v = parse(version) ?: return true
        val min = parse(MINIMUM)!!
        for (i in 0 until maxOf(v.size, min.size)) {
            val a = v.getOrElse(i) { 0 }
            val b = min.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return true
    }
}
