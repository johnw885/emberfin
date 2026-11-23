package com.johnw.reelfin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.johnw.reelfin.player.PlayerScreen
import com.johnw.reelfin.spike.HomeScreen
import com.johnw.reelfin.spike.QuickConnectScreen
import com.johnw.reelfin.spike.ServerScreen
import com.johnw.reelfin.ui.ReelfinColors
import com.johnw.reelfin.ui.ReelfinTheme

class MainActivity : ComponentActivity() {
    private val vm: SpikeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleDebugIntent(intent)
        setContent {
            ReelfinTheme {
                Box(Modifier.fillMaxSize().background(ReelfinColors.Background)) {
                    SpikeApp(vm)
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
     * --es play_item_id <id> [--el start_ms <ms>]` plays an item directly, for playback testing.
     */
    private fun handleDebugIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val id = intent?.getStringExtra("play_item_id") ?: return
        vm.playById(id, intent.getLongExtra("start_ms", 0L))
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home button, input switch) ends playback and saves the resume point.
        if (vm.screen.value is Screen.Player) vm.closePlayer()
    }
}

@Composable
private fun SpikeApp(vm: SpikeViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    when (val s = screen) {
        is Screen.Server -> ServerScreen(s, onConnect = vm::connect)
        is Screen.QuickConnect -> QuickConnectScreen(s, onBack = vm::backToServer)
        is Screen.Home -> HomeScreen(s, imageUrl = vm::imageUrl, onPlay = vm::play, onSignOut = vm::signOut)
        is Screen.Player -> PlayerScreen(s.session, onExit = vm::closePlayer)
    }
}
