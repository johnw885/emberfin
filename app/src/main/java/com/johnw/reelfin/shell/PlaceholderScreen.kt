package com.johnw.reelfin.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.johnw.reelfin.ui.ActionButton
import com.johnw.reelfin.ui.Dimens
import com.johnw.reelfin.ui.Reelfin
import com.johnw.reelfin.ui.ReelfinIcons
import com.johnw.reelfin.ui.ReelfinType

/** Stand-in for Search and Settings until Session 4 builds them. Reachable and leavable with the remote. */
@Composable
fun PlaceholderScreen(title: String, message: String, onBack: () -> Unit) {
    val back = remember { FocusRequester() }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
    Column(Modifier.fillMaxSize().background(Reelfin.colors.background).padding(start = Dimens.ScreenPadding, top = 60.dp)) {
        Text(title, style = ReelfinType.headline, color = Reelfin.colors.onBackground)
        Spacer(Modifier.height(10.dp))
        Text(message, style = ReelfinType.body, color = Reelfin.colors.muted, modifier = Modifier.widthIn(max = 520.dp))
        Spacer(Modifier.height(24.dp))
        ActionButton("Back", onClick = onBack, icon = ReelfinIcons.Back, modifier = Modifier.focusRequester(back))
    }
}
