package com.johnw.emberfin.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.johnw.emberfin.data.Pin
import com.johnw.emberfin.ui.Emberfin
import com.johnw.emberfin.ui.EmberfinIcons
import com.johnw.emberfin.ui.EmberfinType

/**
 * A 4-digit PIN pad for the remote (the Streamer's remote has no number keys): a 3×4 keypad,
 * plus the digit keys of a keyboard or `adb shell input text`. [onComplete] gets the PIN when the
 * fourth digit is in and returns an error to show (the pad clears), or null to accept it.
 */
@Composable
fun PinPad(
    title: String,
    onComplete: (String) -> String?,
    onDismiss: () -> Unit,
    subtitle: String? = null,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    fun type(d: Char) {
        if (pin.length >= Pin.LENGTH) return
        pin += d
        error = null
        if (pin.length == Pin.LENGTH) {
            val entered = pin
            pin = ""
            error = onComplete(entered)
        }
    }

    fun erase() {
        pin = pin.dropLast(1)
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(320.dp)
                .background(Emberfin.colors.surface, RoundedCornerShape(12.dp))
                .padding(24.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val digit = DigitKeys[e.key]
                    when {
                        digit != null -> { type(digit); true }
                        e.key == Key.Backspace || e.key == Key.Delete -> { erase(); true }
                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = EmberfinType.title, color = Emberfin.colors.onBackground, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = EmberfinType.meta, color = Emberfin.colors.muted, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(Pin.LENGTH) { i ->
                    val filled = i < pin.length
                    Box(
                        Modifier.size(16.dp)
                            .background(if (filled) Emberfin.colors.accent else Color.Transparent, CircleShape)
                            .border(2.dp, if (filled) Emberfin.colors.accent else Emberfin.colors.muted, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(error ?: " ", style = EmberfinType.meta, color = Emberfin.colors.error)
            Spacer(Modifier.height(8.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "", "0", "<")
            keys.chunked(3).forEach { line ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                    line.forEach { k ->
                        when (k) {
                            "" -> Spacer(Modifier.size(64.dp, 46.dp))
                            "<" -> PadKey(onClick = ::erase) {
                                Icon(EmberfinIcons.Backspace, contentDescription = "Delete", modifier = Modifier.size(22.dp))
                            }
                            else -> PadKey(onClick = { type(k[0]) }, modifier = if (k == "1") Modifier.focusRequester(first) else Modifier) {
                                Text(k, style = EmberfinType.title)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun PadKey(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(64.dp, 46.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f), contentColor = Emberfin.colors.onBackground,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
    ) {
        Box(Modifier.size(64.dp, 46.dp), contentAlignment = Alignment.Center) { content() }
    }
}

private val DigitKeys = mapOf(
    Key.Zero to '0', Key.One to '1', Key.Two to '2', Key.Three to '3', Key.Four to '4',
    Key.Five to '5', Key.Six to '6', Key.Seven to '7', Key.Eight to '8', Key.Nine to '9',
    Key.NumPad0 to '0', Key.NumPad1 to '1', Key.NumPad2 to '2', Key.NumPad3 to '3', Key.NumPad4 to '4',
    Key.NumPad5 to '5', Key.NumPad6 to '6', Key.NumPad7 to '7', Key.NumPad8 to '8', Key.NumPad9 to '9',
)
