package com.johnw.emberfin.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.johnw.emberfin.nav.AppNavigator
import com.johnw.emberfin.shell.openItem
import com.johnw.emberfin.ui.ActionButton
import com.johnw.emberfin.ui.Dimens
import com.johnw.emberfin.ui.ItemFormat
import com.johnw.emberfin.ui.LandscapeTile
import com.johnw.emberfin.ui.PosterTile
import com.johnw.emberfin.ui.Emberfin
import com.johnw.emberfin.ui.EmberfinIcons
import com.johnw.emberfin.ui.EmberfinType
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.compose.viewmodel.koinViewModel

/**
 * Search: the A–Z keyboard on the left (plus the mic, which hands off to the system speech
 * recognizer), results on the right. Typing on a real or adb keyboard works too.
 */
@Composable
fun SearchScreen(nav: AppNavigator, vm: SearchViewModel = koinViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    BackHandler { nav.back() }

    val speech = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a title, actor or genre")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val canSpeak = remember { speech.resolveActivity(context.packageManager) != null }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.setQuery(it.lowercase()) }
        }
    }

    val firstKey = remember { FocusRequester() }
    val restore = remember { FocusRequester() }
    val open: (BaseItemDto) -> Unit = { item ->
        vm.lastResult = item.id.toString()
        openItem(nav, item)
    }

    LaunchedEffect(Unit) {
        val back = vm.lastResult
        if (back != null && (state.titles + state.episodes).any { it.id.toString() == back }) {
            runCatching { restore.requestFocus() }.onFailure { runCatching { firstKey.requestFocus() } }
        } else {
            runCatching { firstKey.requestFocus() }
        }
    }

    Row(
        Modifier
            .fillMaxSize()
            .background(Emberfin.colors.background)
            .onPreviewKeyEvent { ev ->
                // A hardware keyboard (or `adb shell input text`) types straight into the query.
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val native = ev.nativeKeyEvent
                when {
                    native.keyCode == AndroidKey.KEYCODE_DEL -> { vm.press(SearchKey.Delete); true }
                    native.keyCode == AndroidKey.KEYCODE_SPACE -> { vm.press(SearchKey.Space); true }
                    native.unicodeChar.toChar().isLetterOrDigit() -> {
                        vm.press(SearchKey.Char(native.unicodeChar.toChar().lowercaseChar())); true
                    }
                    else -> false
                }
            },
    ) {
        Keyboard(
            query = state.query,
            canSpeak = canSpeak,
            onKey = vm::press,
            onMic = { runCatching { voice.launch(speech) } },
            firstKey = firstKey,
            modifier = Modifier.width(300.dp).fillMaxHeight().padding(start = Dimens.ScreenPadding, top = 40.dp),
        )
        Results(state, vm, open, restore, Modifier.weight(1f).fillMaxHeight())
    }
}

@Composable
private fun Keyboard(
    query: String,
    canSpeak: Boolean,
    onKey: (SearchKey) -> Unit,
    onMic: () -> Unit,
    firstKey: FocusRequester,
    modifier: Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(40.dp)) {
            Icon(EmberfinIcons.Search, contentDescription = null, tint = Emberfin.colors.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                if (query.isEmpty()) "Search" else query,
                style = EmberfinType.title,
                color = if (query.isEmpty()) Emberfin.colors.faint else Emberfin.colors.onBackground,
                maxLines = 1, overflow = TextOverflow.StartEllipsis,
            )
            if (query.isNotEmpty()) Box(Modifier.padding(start = 2.dp).width(2.dp).height(22.dp).background(Emberfin.colors.accent))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Emberfin.colors.faint.copy(alpha = 0.5f)))
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
            if (canSpeak) KeyButton(icon = EmberfinIcons.Mic, label = "Voice", width = WideKey, onClick = onMic)
            KeyButton(icon = EmberfinIcons.Space, label = "Space", width = WideKey, onClick = { onKey(SearchKey.Space) })
            KeyButton(icon = EmberfinIcons.Backspace, label = "Delete", width = WideKey, onClick = { onKey(SearchKey.Delete) })
        }
        Spacer(Modifier.height(KeyGap))
        SearchKeyboard.letters.chunked(SearchKeyboard.COLUMNS).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                row.forEachIndexed { c, key ->
                    KeyButton(
                        text = key.c.uppercase(),
                        onClick = { onKey(key) },
                        modifier = if (r == 0 && c == 0) Modifier.focusRequester(firstKey) else Modifier,
                    )
                }
            }
            Spacer(Modifier.height(KeyGap))
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            KeyButton(text = "Clear", width = KeySize * 6 + KeyGap * 5, onClick = { onKey(SearchKey.Clear) })
        }
    }
}

private val KeySize = 38.dp
private val KeyGap = 5.dp
private val WideKey = (KeySize * 6 + KeyGap * 3) / 3

@Composable
private fun KeyButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    icon: ImageVector? = null,
    label: String? = null,
    width: androidx.compose.ui.unit.Dp = KeySize,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(width, KeySize),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(5.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Emberfin.colors.onBackground,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            if (text != null) Text(text, style = EmberfinType.bodyStrong)
        }
    }
}

@Composable
private fun Results(
    state: SearchState,
    vm: SearchViewModel,
    open: (BaseItemDto) -> Unit,
    restore: FocusRequester,
    modifier: Modifier,
) {
    val api = vm.session.api
    LazyVerticalGrid(
        columns = GridCells.Fixed(COLUMNS),
        contentPadding = PaddingValues(start = 28.dp, end = Dimens.ScreenPadding, top = 40.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(Dimens.TileGap),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier,
    ) {
        val heading = when {
            state.error != null -> null
            state.suggestions -> "Recently added"
            state.titles.isEmpty() && state.episodes.isEmpty() && !state.loading -> null
            state.titles.isNotEmpty() -> "Movies & shows"
            else -> null
        }
        heading?.let { header(it) }
        state.error?.let { msg ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(msg, style = EmberfinType.body, color = Emberfin.colors.error)
                    Spacer(Modifier.height(12.dp))
                    ActionButton("Try again", onClick = { vm.retry() })
                }
            }
        }
        if (!state.suggestions && !state.loading && state.error == null && state.titles.isEmpty() && state.episodes.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "No matches for “${state.query.trim()}”. Try another title, or fewer letters.",
                    style = EmberfinType.body, color = Emberfin.colors.muted,
                )
            }
        }
        items(state.titles, key = { "t" + it.id }) { item ->
            PosterTile(
                art = ItemFormat.poster(api, item), title = ItemFormat.title(item),
                progress = ItemFormat.progress(item), played = ItemFormat.played(item), showCaption = true,
                width = PosterWidth,
                onClick = { open(item) },
                modifier = restoreModifier(item, vm, restore),
            )
        }
        if (state.episodes.isNotEmpty()) {
            header("Episodes & videos")
            items(state.episodes, key = { "e" + it.id }, span = { GridItemSpan(2) }) { item ->
                LandscapeTile(
                    art = ItemFormat.landscape(api, item), title = ItemFormat.title(item),
                    subtitle = ItemFormat.episodeLine(item), progress = ItemFormat.progress(item),
                    width = PosterWidth * 2 + Dimens.TileGap,
                    onClick = { open(item) },
                    modifier = restoreModifier(item, vm, restore),
                )
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.header(text: String) {
    item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
        Text(text, style = EmberfinType.rowTitle, color = Emberfin.colors.onBackground, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun restoreModifier(item: BaseItemDto, vm: SearchViewModel, restore: FocusRequester): Modifier =
    (if (item.id.toString() == vm.lastResult) Modifier.focusRequester(restore) else Modifier)
        .onFocusChanged { if (it.isFocused) vm.lastResult = item.id.toString() }

private const val COLUMNS = 5
private val PosterWidth = 104.dp
