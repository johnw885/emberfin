package com.johnw.reelfin.data

import android.content.Context
import android.os.Build
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/** Builds SDK clients that identify as Reelfin with a stable per-install device id. */
class JellyfinClientFactory(context: Context, store: SessionStore, appVersion: String) {
    private val client = ClientInfo(name = "Reelfin", version = appVersion)
    private val device = DeviceInfo(id = store.deviceId, name = Build.MODEL ?: "Android TV")

    val jellyfin: Jellyfin = createJellyfin {
        this.context = context.applicationContext
        clientInfo = client
        deviceInfo = device
    }

    fun create(baseUrl: String, accessToken: String? = null): ApiClient =
        jellyfin.createApi(baseUrl = baseUrl, accessToken = accessToken)

    /** `Authorization: MediaBrowser ...` value for requests made outside the SDK (ExoPlayer). */
    fun authorizationHeader(accessToken: String?): String = AuthorizationHeaderBuilder.buildHeader(
        clientName = client.name,
        clientVersion = client.version,
        deviceId = device.id,
        deviceName = device.name,
        accessToken = accessToken,
    )
}
