package com.johnw.reelfin.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.johnw.reelfin.browse.BrowseActions
import com.johnw.reelfin.browse.BrowseLayout

@Composable
fun HomeScreen(vm: HomeViewModel, actions: BrowseActions, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    BrowseLayout(
        api = vm.session.api,
        billboard = state.billboard,
        rows = state.rows,
        focus = vm.focus,
        actions = actions,
        details = vm::details,
        inMyList = vm::inMyList,
        modifier = modifier,
        emptyText = state.error ?: if (state.loading) null else "Nothing here yet. Add some media to your server.",
    )
}
