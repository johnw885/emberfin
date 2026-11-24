package com.johnw.emberfin.data

import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.serializer.toUUID

/** A signed-in user on one server, as saved on this device (the "Who's watching?" profiles). */
@Serializable
data class Account(
    val serverId: String,
    val serverUrl: String,
    val serverName: String,
    val userId: String,
    val userName: String,
    val accessToken: String,
    val imageTag: String? = null,
    val lastUsed: Long = 0,
) {
    /** Unique per device: the same user can't be saved twice for one server. */
    val key: String get() = "$serverId/$userId"
}

/** The profile in use: its account and an SDK client already holding its token. */
class ActiveSession(val account: Account, val api: ApiClient) {
    val userId: UUID = account.userId.toUUID()
}
