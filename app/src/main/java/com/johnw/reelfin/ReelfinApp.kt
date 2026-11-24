package com.johnw.reelfin

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.johnw.reelfin.data.JellyfinClientFactory
import com.johnw.reelfin.data.LibraryRepository
import com.johnw.reelfin.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class ReelfinApp : Application(), SingletonImageLoader.Factory {
    /** Outlives screens, so a final "stopped" report still goes out after the player closes. */
    val appScope = CoroutineScope(SupervisorJob())

    // Used by the player (Session 2), which builds its ViewModel without Koin's help.
    val clients: JellyfinClientFactory by inject()
    val sessions: SessionManager by inject()
    val library: LibraryRepository by inject()

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@ReelfinApp)
            modules(appModule(this@ReelfinApp))
        }
    }

    /** Art-heavy TV UI: a bigger memory cache, RGB_565 for photos and a short crossfade by default. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.30).build() }
            .allowRgb565(true)
            .crossfade(180)
            .build()
}
