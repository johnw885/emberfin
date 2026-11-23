package com.johnw.reelfin

import android.app.Application
import com.johnw.reelfin.data.AuthRepository
import com.johnw.reelfin.data.JellyfinClientFactory
import com.johnw.reelfin.data.LibraryRepository
import com.johnw.reelfin.data.ServerRepository
import com.johnw.reelfin.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Spike wiring by hand; Session 3 replaces this with Koin modules. */
class ReelfinApp : Application() {
    /** Outlives screens, so a final "stopped" report still goes out after the player closes. */
    val appScope = CoroutineScope(SupervisorJob())

    val store by lazy { SessionStore(this) }
    val clients by lazy { JellyfinClientFactory(this, store, BuildConfig.VERSION_NAME) }
    val servers by lazy { ServerRepository(clients) }
    val auth by lazy { AuthRepository(clients, store) }
    val library by lazy { LibraryRepository() }
}
