package com.johnw.reelfin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
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
) {
    private val _current = MutableStateFlow<ActiveSession?>(null)
    val current: StateFlow<ActiveSession?> = _current.asStateFlow()

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
        _current.value = ActiveSession(account, factory.create(account.serverUrl, account.accessToken))
        store.setActive(account.key)
    }

    suspend fun add(account: Account) {
        store.upsertAndActivate(account)
        _current.value = ActiveSession(account, factory.create(account.serverUrl, account.accessToken))
    }

    suspend fun remove(account: Account) {
        store.remove(account.key)
        if (_current.value?.account?.key == account.key) _current.value = null
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
