package com.johnw.emberfin.details

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.johnw.emberfin.browse.TitleArt
import com.johnw.emberfin.browse.NoAutoScroll
import com.johnw.emberfin.browse.pivotTo
import com.johnw.emberfin.browse.stopHorizontalExit
import com.johnw.emberfin.data.ImageUrls
import com.johnw.emberfin.nav.AppNavigator
import com.johnw.emberfin.playback.AudioTrackPolicy
import com.johnw.emberfin.player.PlayerArgs
import com.johnw.emberfin.data.Thumbs
import com.johnw.emberfin.ui.ActionButton
import com.johnw.emberfin.ui.ChoiceDialog
import com.johnw.emberfin.ui.Art
import com.johnw.emberfin.ui.Dimens
import com.johnw.emberfin.ui.EpisodeTile
import com.johnw.emberfin.ui.HeroScrims
import com.johnw.emberfin.ui.ItemFormat
import com.johnw.emberfin.ui.KenBurnsBackdrop
import com.johnw.emberfin.ui.MetaLine
import com.johnw.emberfin.ui.NavItem
import com.johnw.emberfin.ui.PersonTile
import com.johnw.emberfin.ui.PosterTile
import com.johnw.emberfin.ui.Emberfin
import com.johnw.emberfin.ui.EmberfinIcons
import com.johnw.emberfin.ui.EmberfinType
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PersonKind
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Details: backdrop, logo, metadata and badges, synopsis, [Play/Resume] [Restart] [Trailer]
 * [My List]; then Episodes (season tabs), More Like This, Cast, and an About panel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(itemId: String, nav: AppNavigator, vm: DetailsViewModel = koinViewModel(key = "details/$itemId") { parametersOf(itemId) }) {
    val state by vm.state.collectAsStateWithLifecycle()
    val title = state.title
    val api = vm.session.api
    val listState = rememberLazyListState()
    val restore = remember { FocusRequester() }
    var returned by remember { mutableStateOf(false) }

    BackHandler { nav.back() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { returned = true }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (returned) { returned = false; vm.load() } }
    LaunchedEffect(title != null) { if (title != null) runCatching { restore.requestFocus() } }

    fun Modifier.track(key: String): Modifier =
        this.then(if (key == vm.focusKey) Modifier.focusRequester(restore) else Modifier)
            .onFocusChanged { if (it.isFocused) vm.focusKey = key }

    // The page fades in once the title has loaded, instead of popping in.
    val shown by animateFloatAsState(if (title != null) 1f else 0f, androidx.compose.animation.core.tween(300), label = "details-in")
    val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 200
    val dim by animateFloatAsState(if (scrolled) 0.82f else 0f, label = "dim")

    Box(Modifier.fillMaxSize().background(Emberfin.colors.background).graphicsLayer { alpha = if (title == null) 1f else shown }) {
        if (title == null) {
            state.error?.let { Text(it, style = EmberfinType.body, color = Emberfin.colors.error, modifier = Modifier.padding(48.dp)) }
            return@Box
        }
        // A new collection or playlist has no art of its own: borrow its first item's.
        KenBurnsBackdrop(ItemFormat.backdrop(api, title) ?: state.children.firstOrNull()?.let { ItemFormat.backdrop(api, it) }, Modifier.fillMaxSize())
        HeroScrims(strength = 1f)
        Box(Modifier.fillMaxSize().background(Emberfin.colors.background.copy(alpha = dim)))

        val scope = rememberCoroutineScope()
        // Sections snap to the top when focus enters them (see NoAutoScroll in BrowseLayout).
        fun Modifier.section(index: Int): Modifier = onFocusChanged { if (it.hasFocus) scope.pivotTo(listState, index) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoAutoScroll) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 240.dp)) {
                var index = 0
                val headerIndex = index++
                item(key = "header") { Header(vm, state, title, nav, Modifier.section(headerIndex)) { key -> Modifier.track(key) } }

                if (state.seasons.isNotEmpty()) {
                    val i = index++
                    item(key = "episodes") {
                        Section("Episodes", Modifier.section(i)) {
                            if (state.seasons.size > 1) {
                                PivotRow(state.seasons, key = { it.id.toString() }, gap = 4.dp, padding = PaddingValues(horizontal = Dimens.ScreenPadding)) { n, season, m ->
                                    NavItem(
                                        season.name ?: "Season ${n + 1}", selected = n == state.seasonIndex,
                                        onClick = { vm.selectSeason(n) }, onFocus = { vm.selectSeason(n) }, modifier = m,
                                    )
                                }
                            }
                            PivotRow(state.episodes, key = { it.id.toString() }) { _, ep, m ->
                                EpisodeTile(
                                    art = ItemFormat.landscape(api, ep),
                                    title = listOfNotNull(ep.indexNumber?.let { "$it." }, ep.name).joinToString(" "),
                                    meta = ItemFormat.runtime(ep.runTimeTicks),
                                    overview = ep.overview,
                                    progress = ItemFormat.progress(ep),
                                    played = ItemFormat.played(ep),
                                    onClick = { nav.play(ep.id.toString()) },
                                    modifier = m.track("ep:${ep.id}"),
                                )
                            }
                        }
                    }
                }

                if (state.children.isNotEmpty()) {
                    val i = index++
                    val label = if (title.type == BaseItemKind.PLAYLIST) "In this playlist" else "In this collection"
                    item(key = "children") {
                        Section(label, Modifier.section(i)) {
                            PivotRow(state.children, key = { it.id.toString() }) { _, c, m ->
                                PosterTile(
                                    art = ItemFormat.poster(api, c), title = ItemFormat.title(c),
                                    progress = ItemFormat.progress(c), played = ItemFormat.played(c),
                                    onClick = { com.johnw.emberfin.shell.openItem(nav, c) }, modifier = m.track("child:${c.id}"),
                                )
                            }
                        }
                    }
                }

                if (state.similar.isNotEmpty()) {
                    val i = index++
                    item(key = "similar") {
                        Section("More Like This", Modifier.section(i)) {
                            PivotRow(state.similar, key = { it.id.toString() }) { _, s, m ->
                                PosterTile(
                                    art = ItemFormat.poster(api, s), title = ItemFormat.title(s), played = ItemFormat.played(s),
                                    onClick = { nav.details(s.id.toString()) }, modifier = m.track("sim:${s.id}"),
                                )
                            }
                        }
                    }
                }

                val people = title.people.orEmpty().filter { it.type == PersonKind.ACTOR || it.type == PersonKind.DIRECTOR }
                    .distinctBy { it.id }.take(24)
                if (people.isNotEmpty()) {
                    val i = index++
                    item(key = "cast") {
                        Section("Cast & Crew", Modifier.section(i)) {
                            PivotRow(people, key = { it.id.toString() }) { _, p, m ->
                                PersonTile(
                                    art = ImageUrls.person(api, p)?.let { Art(it.url, it.blurHash) },
                                    name = p.name.orEmpty(),
                                    role = if (p.type == PersonKind.DIRECTOR) "Director" else p.role,
                                    onClick = { nav.person(p.id.toString()) },
                                    modifier = m.track("cast:${p.id}"),
                                )
                            }
                        }
                    }
                }

                item(key = "about") { About(state.playTarget ?: title, title) }
            }
        }
    }
}

/** A row that keeps its focused item at the left edge, and stops at both ends. */
@Composable
private fun <T> PivotRow(
    items: List<T>,
    key: (T) -> Any,
    gap: androidx.compose.ui.unit.Dp = Dimens.TileGap,
    padding: PaddingValues = Dimens.RowPadding,
    content: @Composable (Int, T, Modifier) -> Unit,
) {
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyRow(
        state = state,
        contentPadding = padding,
        horizontalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier.focusRestorer().stopHorizontalExit(),
    ) {
        itemsIndexed(items, key = { _, it -> key(it) }) { i, item ->
            content(i, item, Modifier.onFocusChanged { if (it.isFocused || it.hasFocus) scope.pivotTo(state, i) })
        }
    }
}

@Composable
private fun Header(
    vm: DetailsViewModel,
    state: DetailsState,
    title: BaseItemDto,
    nav: AppNavigator,
    modifier: Modifier,
    track: (String) -> Modifier,
) {
    val api = vm.session.api
    val target = state.playTarget
    // Badges come from the thing that plays (the movie, or the episode for a series).
    val badgeSource = target?.takeIf { it.mediaSources != null || it.mediaStreams != null } ?: title
    Column(modifier.fillMaxWidth().height(500.dp).padding(start = Dimens.ScreenPadding, top = 56.dp)) {
        TitleArt(api, title, height = 104.dp)
        Spacer(Modifier.height(14.dp))
        MetaLine(ItemFormat.metaParts(title), ItemFormat.badges(badgeSource))
        title.genres?.takeIf { it.isNotEmpty() }?.let {
            Spacer(Modifier.height(4.dp))
            Text(it.take(3).joinToString("  ·  "), style = EmberfinType.meta, color = Emberfin.colors.muted)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            title.overview.orEmpty(), style = EmberfinType.body, color = Emberfin.colors.onBackground.copy(alpha = 0.88f),
            maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 500.dp),
        )
        if (target != null && title.type == BaseItemKind.SERIES) {
            Spacer(Modifier.height(10.dp))
            Text(ItemFormat.episodeLine(target).orEmpty(), style = EmberfinType.bodyStrong, color = Emberfin.colors.onBackground)
            ItemFormat.progress(target)?.let { p ->
                Spacer(Modifier.height(6.dp))
                Row { Box(Modifier.width(220.dp)) { com.johnw.emberfin.ui.ProgressBar(p) } }
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.stopHorizontalExit()) {
            val resumeAt = target?.userData?.playbackPositionTicks ?: 0
            val resume = resumeAt > 0 && ItemFormat.progress(target!!) != null
            ActionButton(
                text = if (resume) "Resume" else "Play", icon = EmberfinIcons.Play, primary = true,
                onClick = { target?.let { nav.play(it.id.toString(), PlayerArgs.RESUME) } },
                modifier = track("play"),
            )
            if (resume) {
                ActionButton("Restart", icon = EmberfinIcons.Restart, onClick = { nav.play(target!!.id.toString(), 0) }, modifier = track("restart"))
            }
            state.trailerId?.let { id ->
                ActionButton("Trailer", icon = EmberfinIcons.Episodes, onClick = { nav.play(id, 0) }, modifier = track("trailer"))
            }
            ActionButton(
                "My List", icon = if (state.inMyList) EmberfinIcons.Check else EmberfinIcons.Plus,
                onClick = vm::toggleMyList, modifier = track("mylist"),
            )
            RateButton(state.thumbs, vm::setThumbs, track("rate"))
        }
    }
}

/** Netflix-style rating: one button showing the current rating; OK opens the three choices. */
@Composable
private fun RateButton(thumbs: Thumbs, onRate: (Thumbs) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val icon = when (thumbs) {
        Thumbs.Down -> EmberfinIcons.ThumbDown
        Thumbs.Love -> EmberfinIcons.ThumbsUpDouble
        else -> EmberfinIcons.ThumbUp
    }
    val label = when (thumbs) {
        Thumbs.None -> "Rate"
        Thumbs.Down -> "Not for me"
        Thumbs.Up -> "I like this"
        Thumbs.Love -> "Love this!"
    }
    ActionButton(label, onClick = { open = true }, icon = icon, modifier = modifier)
    if (open) {
        val choices = listOf(Thumbs.Down, Thumbs.Up, Thumbs.Love)
        val labels = listOf("Not for me", "I like this", "Love this!") + if (thumbs != Thumbs.None) listOf("Remove rating") else emptyList()
        ChoiceDialog(
            title = "Rate this title",
            options = labels,
            selected = choices.indexOf(thumbs), // -1 (not rated): no check, focus on the first choice
            onPick = { i ->
                open = false
                val pick = choices.getOrNull(i) ?: Thumbs.None
                if (pick != thumbs) onRate(pick)
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(title, style = EmberfinType.rowTitle, color = Emberfin.colors.onBackground, modifier = Modifier.padding(start = Dimens.ScreenPadding, bottom = 2.dp))
        content()
    }
}

/** The About panel: genres, director, and the playable audio/subtitle tracks and file details. */
@Composable
private fun About(playable: BaseItemDto, title: BaseItemDto) {
    val streams = playable.mediaSources?.firstOrNull()?.mediaStreams ?: playable.mediaStreams.orEmpty()
    val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
    val audio = streams.filter { it.type == MediaStreamType.AUDIO && !AudioTrackPolicy.isBanned(it.codec) }
        .map { it.displayTitle ?: listOfNotNull(it.language, it.codec?.uppercase()).joinToString(" ") }
    val subs = streams.filter { it.type == MediaStreamType.SUBTITLE }
        .map { it.displayTitle ?: it.language ?: "Unknown" }.distinct()
    val source = playable.mediaSources?.firstOrNull()
    val file = listOfNotNull(
        video?.let { v -> listOfNotNull(v.width?.let { w -> "${w}×${v.height}" }, v.codec?.uppercase(), v.videoRangeType?.toString(), v.realFrameRate?.let { "%.3f fps".format(it) }).joinToString(" · ") },
        source?.container?.uppercase(),
        source?.size?.let { "%.1f GB".format(it / 1_073_741_824.0) },
    ).joinToString(" · ")
    val director = title.people.orEmpty().filter { it.type == PersonKind.DIRECTOR }.mapNotNull { it.name }

    Column(Modifier.fillMaxWidth().padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding, top = 16.dp)) {
        Text("About", style = EmberfinType.rowTitle, color = Emberfin.colors.onBackground)
        Spacer(Modifier.height(8.dp))
        Fact("Genres", title.genres.orEmpty().joinToString(", "))
        Fact("Director", director.joinToString(", "))
        Fact("Studio", title.studios.orEmpty().mapNotNull { it.name }.take(3).joinToString(", "))
        Fact("Audio", audio.joinToString("  ·  "))
        Fact("Subtitles", subs.take(8).joinToString(", "))
        Fact("Video", file)
    }
}

@Composable
private fun Fact(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, style = EmberfinType.meta, color = Emberfin.colors.faint, modifier = Modifier.width(96.dp))
        Text(value, style = EmberfinType.meta, color = Emberfin.colors.onBackground.copy(alpha = 0.85f), modifier = Modifier.alpha(1f))
    }
}
