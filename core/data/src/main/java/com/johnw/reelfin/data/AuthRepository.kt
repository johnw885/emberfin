package com.johnw.reelfin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jellyfin.sdk.api.client.extensions.authenticationApi
import org.jellyfin.sdk.model.api.QuickConnectDto

sealed interface QuickConnectState {
    data class Code(val code: String) : QuickConnectState
    data class SignedIn(val session: StoredSession) : QuickConnectState
}

class AuthRepository(private val factory: JellyfinClientFactory, private val store: SessionStore) {
    val currentSession: StoredSession? get() = store.session

    /**
     * Quick Connect: emits the code to show, polls until the user approves it on another device,
     * then exchanges the secret for a token and saves the session.
     */
    fun quickConnect(serverUrl: String, pollMs: Long = 3_000): Flow<QuickConnectState> = flow {
        val api = factory.create(serverUrl).authenticationApi
        var state = api.initiateQuickConnect().content
        emit(QuickConnectState.Code(state.code))
        while (!state.authenticated) {
            delay(pollMs)
            state = api.getQuickConnectState(state.secret).content
        }
        val auth = api.authenticateWithQuickConnect(QuickConnectDto(secret = state.secret)).content
        val session = StoredSession(
            serverUrl = serverUrl,
            userId = requireNotNull(auth.user?.id) { "Server returned no user" }.toString(),
            accessToken = requireNotNull(auth.accessToken) { "Server returned no token" },
        )
        store.session = session
        emit(QuickConnectState.SignedIn(session))
    }.flowOn(Dispatchers.IO)

    fun signOut() {
        store.session = null
    }
}
