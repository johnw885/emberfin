package com.johnw.emberfin.browse

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.johnw.emberfin.ui.ActionButton
import com.johnw.emberfin.ui.Dimens
import com.johnw.emberfin.ui.HeroScrims
import com.johnw.emberfin.ui.ItemFormat
import com.johnw.emberfin.ui.KenBurnsBackdrop
import com.johnw.emberfin.ui.LandscapeTile
import com.johnw.emberfin.ui.MetaLine
import com.johnw.emberfin.ui.PosterTile
import com.johnw.emberfin.ui.Emberfin
import com.johnw.emberfin.ui.EmberfinIcons
import com.johnw.emberfin.ui.EmberfinType
import com.johnw.emberfin.ui.RemoteImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto

enum class RowStyle { Landscape, Poster }

/** A row of tiles. A null [title] is a grid line (the "All" grid under the rows). */
data class RowUi(val key: String, val title: String?, val style: RowStyle, val items: List<BaseItemDto>)

/** Where focus was, so it comes back to the same tile after Details or Player. Kept in the screen's ViewModel. */
@Stable
class BrowseFocus {
    var inRows by mutableStateOf(false)
    // Plain fields on purpose: writing state on every focus move would recompose all rows.
    var rowKey: String? = null
    var itemId: String? = null
}

/**
 * Whether a screen may move focus into its content when it loads. The shell says no while the
 * user is on the top nav, so switching tabs doesn't yank focus away from the tabs.
 */
val LocalAutoFocus = staticCompositionLocalOf<() -> Boolean> { { true } }


/** Height of the top area (nav + hero). The rows live below it. */
val HeroHeight = 318.dp

/** Callbacks shared by every browse screen. */
class BrowseActions(
    val open: (BaseItemDto) -> Unit,
    val play: (BaseItemDto) -> Unit,
    val toggleList: (BaseItemDto) -> Unit,
)

/**
 * Lists here scroll themselves (see [pivotTo]), Netflix-style: the focused row snaps under the
 * hero and the focused tile snaps to the left edge. Compose's own bring-into-view is turned off,
 * because nested row/column requests fight each other.
 */
@OptIn(ExperimentalFoundationApi::class)
object NoAutoScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Scrolls [index] to the start of the list (after its content padding). */
fun CoroutineScope.pivotTo(state: LazyListState, index: Int) {
    launch { state.animateScrollToItem(index) }
}

/**
 * The Netflix-style browse layout used by Home, Shows and Movies: a full-bleed backdrop with a
 * billboard (focusable buttons) at the top, and rows below. When focus moves into the rows the
 * top area turns into a hero for the focused tile (300 ms debounce, crossfaded), and Up from the
 * first row returns to the billboard.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseLayout(
    api: ApiClient,
    billboard: BaseItemDto?,
    rows: List<RowUi>,
    focus: BrowseFocus,
    actions: BrowseActions,
    details: (BaseItemDto) -> BaseItemDto?,
    inMyList: (BaseItemDto) -> Boolean,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    emptyText: String? = null,
    gridHeader: (@Composable () -> Unit)? = null,
    gridRows: List<RowUi> = emptyList(),
    onNearGridEnd: () -> Unit = {},
    /** The billboard's local trailer, if any (null: no trailers on this screen). */
    trailerUrl: (suspend (BaseItemDto) -> String?)? = null,
) {
    val playRequester = remember { FocusRequester() }
    val restoreRequester = remember { FocusRequester() }
    // Read only inside HeroBackdrop/HeroArea, so a focus move recomposes the hero, not the rows.
    val heroState = remember { HeroState() }
    // Rows that have already made their entrance (refreshes don't replay it).
    val entered = remember { mutableSetOf<String>() }
    var wantBillboardFocus by remember { mutableStateOf(false) }
    var hasHeroFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Debounce the hero so fast scrolling doesn't load every backdrop.
    LaunchedEffect(Unit) {
        snapshotFlow { heroState.focused }.debounce(300).distinctUntilChanged().collectLatest { heroState.hero = it }
    }
    LaunchedEffect(wantBillboardFocus, billboard) {
        if (wantBillboardFocus && billboard != null) {
            runCatching { playRequester.requestFocus() }
            wantBillboardFocus = false
        }
    }
    // If the billboard title changes while it has focus, keep focus on its Play button.
    LaunchedEffect(billboard?.id) {
        if (billboard != null && !focus.inRows && hasHeroFocus) runCatching { playRequester.requestFocus() }
    }
    // Restore focus on (re)entry: the tile we left from, or the billboard.
    val autoFocus = LocalAutoFocus.current
    LaunchedEffect(billboard != null, rows.isNotEmpty()) {
        if (!autoFocus()) return@LaunchedEffect
        fun tryFocus(r: FocusRequester) = runCatching { r.requestFocus(FocusDirection.Enter) }.getOrDefault(false)
        if (focus.inRows && focus.itemId != null && rows.isNotEmpty()) {
            // Bring the remembered row on screen (rows may have refreshed and reordered), let the
            // row scroll its tile into view, then focus it.
            val rowIndex = rows.indexOfFirst { it.key == focus.rowKey }
            if (rowIndex >= 0) listState.scrollToItem(rowIndex)
            delay(48)
            if (tryFocus(restoreRequester)) return@LaunchedEffect
            // The tile is gone (e.g. finished watching): start again from the billboard.
            focus.inRows = false
        }
        if (billboard != null) tryFocus(playRequester)
        else if (rows.isNotEmpty()) tryFocus(restoreRequester)
    }

    Box(modifier.fillMaxSize()) {
        HeroBackdrop(api, heroState, focus, billboard, details, trailerUrl, billboardFocused = hasHeroFocus && !focus.inRows)
        HeroScrims()
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(HeroHeight).padding(start = Dimens.ScreenPadding, top = 64.dp).onFocusChanged { hasHeroFocus = it.hasFocus }) {
                HeroArea(
                    api, heroState, focus, billboard, details, inMyList, actions, playRequester, emptyText,
                    onButtonFocused = {
                        focus.inRows = false
                        // Billboard mode shows the rows from the top, so Down lands on the first row.
                        // Instant, so a quick Down press already sees the first row in place.
                        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) scope.launch { listState.scrollToItem(0) }
                    },
                )
            }
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusProperties {
                            onExit = {
                                if (requestedFocusDirection == FocusDirection.Up && billboard != null) {
                                    cancelFocusChange()
                                    focus.inRows = false
                                    wantBillboardFocus = true
                                }
                            }
                        },
                    contentPadding = PaddingValues(bottom = 200.dp),
                ) {
                    val rowItem: @Composable (RowUi, Int, Int) -> Unit = { row, gridIndex, listIndex ->
                        ContentRow(
                            api, row,
                            entrance = if (gridIndex >= 0) null else Entrance(entered, listIndex),
                            restoreRequester = restoreRequester.takeIf {
                                row.key == focus.rowKey || (focus.rowKey == null && row == (rows.firstOrNull() ?: gridRows.firstOrNull()))
                            },
                            restoreItemId = focus.itemId,
                            onFocus = { item ->
                                scope.pivotTo(listState, listIndex)
                                heroState.focused = item
                                focus.inRows = true
                                focus.rowKey = row.key
                                focus.itemId = item.id.toString()
                                if (gridIndex >= 0 && gridIndex >= gridRows.size - 3) onNearGridEnd()
                            },
                            onClick = actions.open,
                        )
                    }
                    itemsIndexed(rows, key = { _, r -> r.key }) { i, row -> rowItem(row, -1, i) }
                    if (gridHeader != null) {
                        item(key = "grid-header") {
                            Box(Modifier.onFocusChanged { if (it.hasFocus) scope.pivotTo(listState, rows.size) }) { gridHeader() }
                        }
                    }
                    val gridStart = rows.size + if (gridHeader != null) 1 else 0
                    itemsIndexed(gridRows, key = { _, r -> r.key }) { i, row -> rowItem(row, i, gridStart + i) }
                }
            }
        }
    }
}

@Stable
private class HeroState {
    /** The tile with focus right now. */
    var focused by mutableStateOf<BaseItemDto?>(null)
    /** [focused], 300 ms later: what the hero shows. */
    var hero by mutableStateOf<BaseItemDto?>(null)

    fun shown(focus: BrowseFocus, billboard: BaseItemDto?): BaseItemDto? = if (focus.inRows) hero ?: focused else billboard
}

@Composable
private fun HeroBackdrop(
    api: ApiClient,
    heroState: HeroState,
    focus: BrowseFocus,
    billboard: BaseItemDto?,
    details: (BaseItemDto) -> BaseItemDto?,
    trailerUrl: (suspend (BaseItemDto) -> String?)?,
    billboardFocused: Boolean,
) {
    val item = heroState.shown(focus, billboard)
    var videoOn by remember { mutableStateOf(false) }
    // The trailer's video sits under the backdrop, which fades out once frames are showing.
    if (trailerUrl != null) {
        BillboardTrailer(billboard.takeIf { !focus.inRows }, active = billboardFocused, trailerUrl = trailerUrl, onVideo = { videoOn = it })
    }
    val backdropAlpha by animateFloatAsState(if (videoOn) 0f else 1f, tween(if (videoOn) 800 else 350), label = "backdrop")
    // Ken Burns drift on the billboard only; the focus hero changes too often for it.
    KenBurnsBackdrop(
        item?.let { ItemFormat.backdrop(api, details(it) ?: it) },
        Modifier.fillMaxSize().graphicsLayer { alpha = backdropAlpha },
        animate = !focus.inRows && !videoOn,
    )
}

@Composable
private fun HeroArea(
    api: ApiClient,
    heroState: HeroState,
    focus: BrowseFocus,
    billboard: BaseItemDto?,
    details: (BaseItemDto) -> BaseItemDto?,
    inMyList: (BaseItemDto) -> Boolean,
    actions: BrowseActions,
    playRequester: FocusRequester,
    emptyText: String?,
    onButtonFocused: () -> Unit,
) {
    val shown = heroState.shown(focus, billboard)
    val showItem = shown?.let { details(it) ?: it }
    AnimatedContent(
        targetState = showItem to focus.inRows,
        transitionSpec = { fadeIn(tween(320, delayMillis = 60)) togetherWith fadeOut(tween(180)) },
        contentKey = { (item, rowsMode) -> (item?.id?.toString() ?: "") + rowsMode },
        label = "hero",
    ) { (item, rowsMode) ->
        if (item != null) {
            HeroInfo(
                api, item, showButtons = !rowsMode, playRequester = playRequester,
                inMyList = inMyList(item), actions = actions, onButtonFocused = onButtonFocused,
            )
        } else if (emptyText != null && !rowsMode) {
            Text(emptyText, style = EmberfinType.body, color = Emberfin.colors.muted)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentRow(
    api: ApiClient,
    row: RowUi,
    entrance: Entrance?,
    restoreRequester: FocusRequester?,
    restoreItemId: String?,
    onFocus: (BaseItemDto) -> Unit,
    onClick: (BaseItemDto) -> Unit,
) {
    Column(Modifier.fillMaxWidth().then(if (entrance != null) Modifier.entrance(row.key, entrance) else Modifier)) {
        if (row.title != null) {
            Text(
                row.title, style = EmberfinType.rowTitle, color = Emberfin.colors.onBackground,
                modifier = Modifier.padding(start = Dimens.ScreenPadding, top = 10.dp),
            )
        }
        val rowState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // The row holding the remembered tile scrolls it into view so focus can be restored.
        LaunchedEffect(restoreRequester != null, restoreItemId, row.items) {
            if (restoreRequester == null) return@LaunchedEffect
            val i = row.items.indexOfFirst { it.id.toString() == restoreItemId }.takeIf { it >= 0 } ?: 0
            rowState.scrollToItem(i)
        }
            LazyRow(
                state = rowState,
                modifier = Modifier.focusRestorer().stopHorizontalExit(),
                contentPadding = Dimens.RowPadding,
                horizontalArrangement = Arrangement.spacedBy(Dimens.TileGap),
            ) {
                itemsIndexed(row.items, key = { _, it -> it.id.toString() }) { index, item ->
                    val restore = restoreRequester != null &&
                        (item.id.toString() == restoreItemId || (restoreItemId == null && item == row.items.first()))
                    val m = if (restore) Modifier.focusRequester(restoreRequester!!) else Modifier
                    when (row.style) {
                        RowStyle.Landscape -> LandscapeTile(
                            art = ItemFormat.landscape(api, item),
                            title = ItemFormat.title(item),
                            subtitle = ItemFormat.episodeLine(item) ?: ItemFormat.remaining(item),
                            progress = ItemFormat.progress(item),
                            onClick = { onClick(item) },
                            onFocus = { scope.pivotTo(rowState, index); onFocus(item) },
                            modifier = m,
                        )
                        RowStyle.Poster -> PosterTile(
                            art = ItemFormat.poster(api, item),
                            title = ItemFormat.title(item),
                            progress = ItemFormat.progress(item),
                            played = ItemFormat.played(item),
                            onClick = { onClick(item) },
                            onFocus = { scope.pivotTo(rowState, index); onFocus(item) },
                            modifier = m,
                        )
                    }
                }
            }
    }
}

/** A row's first appearance on a screen: [index] staggers it after the rows above. */
class Entrance(val done: MutableSet<String>, val index: Int)

/**
 * Rows rise and fade in the first time they appear (staggered top to bottom), then never again,
 * so a refresh or scrolling back doesn't replay it. Only a graphics layer changes: no relayout.
 */
@Composable
private fun Modifier.entrance(key: String, e: Entrance): Modifier {
    val progress = remember(key) { Animatable(if (key in e.done) 1f else 0f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            delay(minOf(e.index, 5) * 55L)
            progress.animateTo(1f, tween(380))
        }
        e.done += key
    }
    val rise = with(LocalDensity.current) { 18.dp.toPx() }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise
    }
}

/** Logo (or title), metadata line with badges, synopsis and, on the billboard, the buttons. */
@Composable
fun HeroInfo(
    api: ApiClient,
    item: BaseItemDto,
    showButtons: Boolean,
    playRequester: FocusRequester?,
    inMyList: Boolean,
    actions: BrowseActions,
    onButtonFocused: () -> Unit = {},
    overviewLines: Int = 3,
    logoHeight: Dp = 88.dp,
) {
    Column(Modifier.widthIn(max = 460.dp)) {
        TitleArt(api, item, logoHeight)
        Spacer(Modifier.height(10.dp))
        MetaLine(ItemFormat.metaParts(item), ItemFormat.badges(item))
        ItemFormat.episodeLine(item)?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = EmberfinType.bodyStrong, color = Emberfin.colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        item.overview?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = EmberfinType.body, color = Emberfin.colors.onBackground.copy(alpha = 0.85f), maxLines = overviewLines, overflow = TextOverflow.Ellipsis)
        }
        if (showButtons) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.stopHorizontalExit()) {
                val resume = (item.userData?.playbackPositionTicks ?: 0) > 0
                ActionButton(
                    if (resume) "Resume" else "Play", onClick = { actions.play(item) }, icon = EmberfinIcons.Play, primary = true,
                    modifier = Modifier.then(if (playRequester != null) Modifier.focusRequester(playRequester) else Modifier)
                        .onFocusGained(onButtonFocused),
                )
                ActionButton("More Info", onClick = { actions.open(item) }, icon = EmberfinIcons.Info, modifier = Modifier.onFocusGained(onButtonFocused))
                ActionButton(
                    "My List", onClick = { actions.toggleList(item) },
                    icon = if (inMyList) EmberfinIcons.Check else EmberfinIcons.Plus, modifier = Modifier.onFocusGained(onButtonFocused),
                )
            }
        }
    }
}

/** The clear logo when the item (or its series) has one, the name in display type otherwise. */
@Composable
fun TitleArt(api: ApiClient, item: BaseItemDto, height: Dp = 88.dp) {
    val logo = ItemFormat.logo(api, item)
    if (logo != null) {
        RemoteImage(
            logo, contentDescription = ItemFormat.title(item), opaque = false, contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart, modifier = Modifier.width(320.dp).heightIn(max = height).height(height),
        )
    } else {
        Text(
            ItemFormat.title(item), style = EmberfinType.display, color = Emberfin.colors.onBackground,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.onFocusGained(block: () -> Unit): Modifier = this.onFocusChanged { if (it.isFocused) block() }

/** Left/Right at either end of a row stays put instead of jumping to another row or the nav. */
fun Modifier.stopHorizontalExit(): Modifier = this.focusProperties {
    onExit = {
        if (requestedFocusDirection == FocusDirection.Left || requestedFocusDirection == FocusDirection.Right) cancelFocusChange()
    }
}.focusGroup()
