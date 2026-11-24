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
import com.johnw.reelfin.player.PlaybackPrefs
import com.johnw.reelfin.ui.ReelfinColors
import com.johnw.reelfin.ui.ReelfinTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

class MainActivity : ComponentActivity() {
    private val debugPlay = Channel<PlayerRoute>(Channel.CONFLATED)

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
        // `--ei max_bitrate <bps>` sets the quality cap (Session 4 adds the settings screen).
        if (intent.hasExtra("max_bitrate")) PlaybackPrefs(this).maxStreamingBitrate = intent.getIntExtra("max_bitrate", 0)
        val id = intent.getStringExtra("play_item_id") ?: return
        debugPlay.trySend(PlayerRoute(id, intent.getLongExtra("start_ms", 0L)))
    }
}
