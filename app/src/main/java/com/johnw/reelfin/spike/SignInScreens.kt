package com.johnw.reelfin.spike

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import com.johnw.reelfin.Screen
import com.johnw.reelfin.ui.ReelfinColors

@Composable
fun ServerScreen(state: Screen.Server, onConnect: (String) -> Unit) {
    var url by remember(state.url) { mutableStateOf(state.url) }
    var fieldFocused by remember { mutableStateOf(false) }
    val buttonFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { buttonFocus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Reelfin", fontSize = 48.sp, fontWeight = FontWeight.Bold, color = ReelfinColors.OnBackground)
        Spacer(Modifier.height(8.dp))
        Text("Enter your Jellyfin server address", fontSize = 20.sp, color = ReelfinColors.Muted)
        Spacer(Modifier.height(32.dp))
        BasicTextField(
            value = url,
            onValueChange = { url = it },
            singleLine = true,
            textStyle = TextStyle(color = ReelfinColors.OnBackground, fontSize = 24.sp),
            cursorBrush = SolidColor(ReelfinColors.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onConnect(url) }),
            modifier = Modifier
                .width(640.dp)
                .onFocusChanged { fieldFocused = it.isFocused }
                .background(ReelfinColors.Surface, RoundedCornerShape(8.dp))
                .border(
                    width = if (fieldFocused) 3.dp else 1.dp,
                    color = if (fieldFocused) Color.White else ReelfinColors.Muted,
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 20.dp, vertical = 14.dp),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onConnect(url) },
            enabled = !state.busy,
            modifier = Modifier.focusRequester(buttonFocus),
        ) { Text(if (state.busy) "Connecting..." else "Connect") }
        state.error?.let {
            Spacer(Modifier.height(24.dp))
            Text(it, color = Color(0xFFFF8A80), fontSize = 18.sp)
        }
    }
}

@Composable
fun QuickConnectScreen(state: Screen.QuickConnect, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().padding(64.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sign in with Quick Connect", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = ReelfinColors.OnBackground)
        Spacer(Modifier.height(8.dp))
        Text(state.serverName, fontSize = 18.sp, color = ReelfinColors.Muted)
        Spacer(Modifier.height(40.dp))
        when {
            state.error != null -> Text(state.error, color = Color(0xFFFF8A80), fontSize = 20.sp)
            state.code == null -> Text("Getting a code...", fontSize = 24.sp, color = ReelfinColors.Muted)
            else -> {
                Text(
                    state.code,
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 16.sp,
                    color = ReelfinColors.Accent,
                )
                Spacer(Modifier.height(32.dp))
                Text(
                    "On your phone or computer, open Jellyfin, go to your profile > Quick Connect, and enter this code.",
                    fontSize = 20.sp,
                    color = ReelfinColors.OnBackground,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("Press Back to change server", fontSize = 16.sp, color = ReelfinColors.Muted)
    }
}
