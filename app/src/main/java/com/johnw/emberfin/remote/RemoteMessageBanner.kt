package com.johnw.emberfin.remote

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.johnw.emberfin.ui.EmberfinColors
import kotlinx.coroutines.delay

/** A message sent from another Jellyfin client ("Send message"), top-centre, gone after its timeout. Never takes focus. */
@Composable
fun RemoteMessageBanner(remote: RemoteControl) {
    val message by remote.message.collectAsStateWithLifecycle()
    var shown by remember { mutableStateOf<RemoteMessage?>(null) }
    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        shown = m
        delay(m.timeoutMs)
        remote.dismissMessage(m.id)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
            val m = shown ?: return@AnimatedVisibility
            Column(
                Modifier
                    .padding(top = 28.dp)
                    .widthIn(max = 640.dp)
                    .background(Color(0xF01B1D22), RoundedCornerShape(10.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                m.header?.let { Text(it, color = EmberfinColors.Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
                Text(m.text, color = Color.White, fontSize = 20.sp)
            }
        }
    }
}
