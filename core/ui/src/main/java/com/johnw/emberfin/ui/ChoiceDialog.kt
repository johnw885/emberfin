package com.johnw.emberfin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

/** A D-pad list of choices in a dialog (sort order, genre…). The current choice has a check and starts focused. */
@Composable
fun ChoiceDialog(
    title: String,
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(340.dp).background(Emberfin.colors.surface, RoundedCornerShape(10.dp)).padding(vertical = 16.dp),
        ) {
            Text(title, style = EmberfinType.title, color = Emberfin.colors.onBackground, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.size(10.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                itemsIndexed(options) { i, label ->
                    Surface(
                        onClick = { onPick(i) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent, contentColor = Emberfin.colors.onBackground,
                            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
                        ),
                        modifier = Modifier.fillMaxWidth().then(if (i == selected.coerceAtLeast(0)) Modifier.focusRequester(first) else Modifier),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                EmberfinIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp),
                                tint = if (i == selected) Emberfin.colors.accent else Color.Transparent,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(label, style = EmberfinType.bodyStrong)
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
