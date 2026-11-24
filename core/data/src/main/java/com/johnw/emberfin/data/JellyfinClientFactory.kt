package com.johnw.emberfin.data

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo

/** Builds SDK clients that identify as Emberfin with a stable per-install device id. */
class JellyfinClientFactory(context: Context, store: SessionStore, appVersion: String, health: ApiHealth) {
    private val client = ClientInfo(name = "Emberfin", version = appVersion)
    private val device = DeviceInfo(id = store.deviceId, name = Build.MODEL ?: "Android TV")

    // One OkHttp base for every SDK client, with ApiHealth watching for unreachable servers and 401s.
    private val http = OkHttpFactory(OkHttpClient.Builder().addInterceptor(health.interceptor).build())

    val jellyfin: Jellyfin = createJellyfin {
        this.context = context.applicationContext
        clientInfo = client
        deviceInfo = device
        apiClientFactory = http
        socketConnectionFactory = http
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
