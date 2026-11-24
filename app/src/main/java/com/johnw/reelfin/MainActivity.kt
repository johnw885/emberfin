package com.johnw.reelfin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.johnw.reelfin.nav.PlayerRoute
import com.johnw.reelfin.nav.ReelfinNavHost
import androidx.lifecycle.lifecycleScope
import com.johnw.reelfin.settings.SettingsStore
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import com.johnw.reelfin.ui.ReelfinColors
import com.johnw.reelfin.ui.ReelfinTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val debugPlay = Channel<PlayerRoute>(Channel.CONFLATED)
    private val settings: SettingsStore by inject()
    private val health: com.johnw.reelfin.data.ApiHealth by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleDebugIntent(intent)
        setContent {
            ReelfinTheme {
                Box(Modifier.fillMaxSize().background(ReelfinColors.Background)) {
                    ReelfinNavHost(rememberNavController(), debugPlay.receiveAsFlow())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDebugIntent(intent)
    }

    /**
     * Debug builds only: `adb shell am start -n com.johnw.reelfin/.MainActivity
     * --es play_item_id <id> [--el start_ms <ms>, -1 = resume]` plays an item directly, for playback testing.
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        // `--ei max_bitrate <bps>` sets the quality cap, `--es frame_rate OFF|SEAMLESS|ALWAYS` the frame-rate mode.
        if (intent.hasExtra("max_bitrate")) {
            val bps = intent.getIntExtra("max_bitrate", 0)
            lifecycleScope.launch { settings.update { it.copy(maxBitrate = bps) } }
        }
        // `--ez offline true|false` makes the active server look unreachable (tests the retry banner).
        if (intent.hasExtra("offline")) health.simulateOffline = intent.getBooleanExtra("offline", false)
        intent.getStringExtra("frame_rate")?.let { v ->
            com.johnw.reelfin.playback.FrameRateMode.entries.firstOrNull { it.name == v }?.let { mode ->
                lifecycleScope.launch { settings.update { it.copy(frameRateMode = mode) } }
            }
        }
        val id = intent.getStringExtra("play_item_id") ?: return
        debugPlay.trySend(PlayerRoute(id, intent.getLongExtra("start_ms", 0L)))
    }
}
