package com.johnw.reelfin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto

sealed interface QuickConnectState {
    data class Code(val code: String) : QuickConnectState
    data class SignedIn(val account: Account) : QuickConnectState
}

/** Signs users in (Quick Connect or password) and returns the [Account] to save. */
class AuthRepository(private val factory: JellyfinClientFactory) {
    /**
     * Quick Connect: emits the code to show, polls until the user approves it on another device,
     * then exchanges the secret for a token.
     */
    fun quickConnect(server: ServerInfo, pollMs: Long = 3_000): Flow<QuickConnectState> = flow {
        val api = factory.create(server.url).authenticationApi
        var state = api.initiateQuickConnect().content
        emit(QuickConnectState.Code(state.code))
        while (!state.authenticated) {
            delay(pollMs)
            state = api.getQuickConnectState(state.secret).content
        }
        val auth = api.authenticateWithQuickConnect(QuickConnectDto(secret = state.secret)).content
        emit(QuickConnectState.SignedIn(auth.toAccount(server)))
    }.flowOn(Dispatchers.IO)

    suspend fun signIn(server: ServerInfo, username: String, password: String): Account = withContext(Dispatchers.IO) {
        val auth = factory.create(server.url).authenticationApi
            .authenticateUserByName(AuthenticateUserByName(username = username, pw = password)).content
        auth.toAccount(server)
    }

    private fun AuthenticationResult.toAccount(server: ServerInfo): Account {
        val u = requireNotNull(user) { "Server returned no user" }
        return Account(
            serverId = server.id,
            serverUrl = server.url,
            serverName = server.name,
            userId = u.id.toString(),
            userName = u.name.orEmpty(),
            accessToken = requireNotNull(accessToken) { "Server returned no token" },
            imageTag = u.primaryImageTag,
            lastUsed = System.currentTimeMillis(),
        )
    }
}
