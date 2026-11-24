package com.johnw.reelfin.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Watches every SDK request to the active profile's server (an OkHttp interceptor), so the app can
 * show a "can't reach the server" banner and send a revoked sign-in back to the sign-in screen
 * without every screen handling those cases itself.
 */
class ApiHealth {
    private val _unreachable = MutableStateFlow(false)
    /** True after a request to the active server failed to connect; false again after any response. */
    val unreachable: StateFlow<Boolean> = _unreachable.asStateFlow()

    private val _authRejected = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Emits the access token the server answered 401 to (revoked or expired sign-in). */
    val authRejected: SharedFlow<String> = _authRejected.asSharedFlow()

    @Volatile private var activeOrigin: String? = null

    /** Debug builds only (set from adb): requests to the active server fail as if it were down. */
    @Volatile var simulateOffline = false

    /** The server the active profile uses; requests elsewhere (adding a profile) are ignored. */
    fun setActiveServer(baseUrl: String?) {
        activeOrigin = baseUrl?.let(::origin)
        _unreachable.value = false
    }

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val watched = activeOrigin != null && origin(request.url.toString()) == activeOrigin
        val response: Response = try {
            if (watched && simulateOffline) throw java.net.ConnectException("Simulated offline (debug)")
            chain.proceed(request)
        } catch (e: IOException) {
            if (watched && !chain.call().isCanceled()) _unreachable.value = true
            throw e
        }
        if (watched) {
            _unreachable.value = false
            val token = tokenOf(request.header("Authorization"))
            if (classify(response.code, token) == Verdict.AuthRejected) _authRejected.tryEmit(token!!)
        }
        response
    }

    enum class Verdict { Ok, AuthRejected }

    companion object {
        /** A 401 only means "signed out" when the request carried a token (sign-in attempts don't). */
        fun classify(code: Int, token: String?): Verdict =
            if (code == 401 && !token.isNullOrEmpty()) Verdict.AuthRejected else Verdict.Ok

        /** The token in a `MediaBrowser Client="…", Token="…"` header, if any. */
        fun tokenOf(header: String?): String? =
            header?.let { Regex("""Token="([^"]*)"""").find(it)?.groupValues?.get(1) }?.takeIf { it.isNotEmpty() }

        /** scheme://host:port, so http://x:8096/a and http://x:8096/b match. */
        fun origin(url: String): String? = url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}:${it.port}" }
    }
}

/** "Can't reach the server" in words a viewer understands. */
fun Throwable.isUnreachable(): Boolean {
    var t: Throwable? = this
    while (t != null) {
        if (t is IOException || t is org.jellyfin.sdk.api.client.exception.TimeoutException) return true
        t = t.cause
    }
    return false
}
