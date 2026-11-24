package com.johnw.reelfin.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi

/**
 * Which profile is in use. Screens read [current]; the profile picker and sign-in flow call
 * [activate] / [add]. Switching profiles builds a fresh SDK client with that account's token.
 */
class SessionManager(
    private val factory: JellyfinClientFactory,
    private val store: AccountStore,
    private val legacy: SessionStore,
    private val health: ApiHealth,
    scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<ActiveSession?>(null)
    val current: StateFlow<ActiveSession?> = _current.asStateFlow()

    private val _expired = MutableSharedFlow<Account>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** A profile whose sign-in the server no longer accepts; the app sends the user to sign in again. */
    val expired: SharedFlow<Account> = _expired.asSharedFlow()

    init {
        scope.launch {
            health.authRejected.collect { token ->
                val active = _current.value?.account ?: return@collect
                if (active.accessToken == token) expire(active)
            }
        }
    }

    /** The server rejected [account]'s token: forget the token (the profile must sign in again). */
    private suspend fun expire(account: Account) {
        store.remove(account.key)
        _current.value = null
        health.setActiveServer(null)
        _expired.tryEmit(account)
    }

    /** The app has shown the "sign in again" screen for the last expired profile. */
    fun clearExpired() = _expired.resetReplayCache()

    val accounts: Flow<List<Account>> = store.accounts
    val lastServerUrl: Flow<String?> = store.lastServerUrl

    /** The active session; only call from screens that are behind sign-in. */
    fun require(): ActiveSession = current.value ?: error("No active profile")

    /**
     * Startup: migrates the spike's saved session, then returns the saved accounts. With exactly
     * one saved profile it's activated straight away; with several, the picker asks.
     */
    suspend fun restore(): List<Account> {
        migrateLegacy()
        val (accounts, _) = store.snapshot()
        if (accounts.size == 1) activate(accounts.first())
        return accounts
    }

    suspend fun activate(account: Account) {
        health.setActiveServer(account.serverUrl)
        _current.value = ActiveSession(account, factory.create(account.serverUrl, account.accessToken))
        store.setActive(account.key)
    }

    suspend fun add(account: Account) {
        store.upsertAndActivate(account)
        health.setActiveServer(account.serverUrl)
        _current.value = ActiveSession(account, factory.create(account.serverUrl, account.accessToken))
    }

    /** Signs a profile out on this device (and tells the server to end its session, best effort). */
    suspend fun remove(account: Account) {
        withContext(Dispatchers.IO) {
            runCatching { factory.create(account.serverUrl, account.accessToken).sessionApi.reportSessionEnded() }
        }
        store.remove(account.key)
        if (_current.value?.account?.key == account.key) {
            _current.value = null
            health.setActiveServer(null)
        }
    }

    suspend fun rememberServer(url: String) = store.setLastServer(url)

    /** `Authorization: MediaBrowser …` for the active profile (ExoPlayer, Coil trickplay). */
    fun authorizationHeader(): String = factory.authorizationHeader(current.value?.account?.accessToken)

    /** Sessions 1 and 2 kept one session in SharedPreferences; turn it into a saved account. */
    private suspend fun migrateLegacy() {
        val old = legacy.legacySession ?: return
        val account = withContext(Dispatchers.IO) {
            runCatching {
                val api = factory.create(old.serverUrl, old.accessToken)
                val info = api.systemApi.getPublicSystemInfo().content
                val me = api.userApi.getCurrentUser().content
                Account(
                    serverId = info.id ?: old.serverUrl, serverUrl = old.serverUrl, serverName = info.serverName ?: "Jellyfin",
                    userId = old.userId, userName = me.name.orEmpty(), accessToken = old.accessToken,
                    imageTag = me.primaryImageTag, lastUsed = System.currentTimeMillis(),
                )
            }.getOrNull()
        } ?: return // Server unreachable: try again next launch.
        store.upsertAndActivate(account)
        legacy.clearLegacySession()
    }
}
