package com.johnw.emberfin.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import com.johnw.emberfin.ui.Art
import com.johnw.emberfin.ui.Avatar
import com.johnw.emberfin.ui.Dimens
import com.johnw.emberfin.ui.NavItem
import com.johnw.emberfin.ui.EmberfinIcons
import com.johnw.emberfin.ui.Wordmark

/** The top-level tabs. Search and Settings open their own screens. */
enum class Tab(val label: String) { Home("Home"), Shows("Shows"), Movies("Movies"), MyList("My List"), HomeVideos("Home Videos") }

/**
 * Netflix-style top bar: wordmark, tabs, then search, profile avatar and settings on the right.
 * It sits over the backdrop with a light top scrim; focus returns to the current tab when entering.
 */
@Composable
fun TopNav(
    current: Tab,
    tabs: List<Tab>,
    profileName: String,
    profileArt: Art?,
    onTab: (Tab) -> Unit,
    onSearch: () -> Unit,
    onProfile: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    currentTabRequester: FocusRequester? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 0.6f to Color.Black.copy(alpha = 0.35f), 1f to Color.Transparent))
            .padding(start = Dimens.ScreenPadding, end = Dimens.ScreenPadding - 8.dp, top = 14.dp, bottom = 18.dp)
            .focusRestorer(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark(size = 22)
        Spacer(Modifier.width(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEach { tab ->
                val m = if (tab == current && currentTabRequester != null) Modifier.focusRequester(currentTabRequester) else Modifier
                NavItem(tab.label, selected = tab == current, onClick = { onTab(tab) }, modifier = m)
            }
        }
        Spacer(Modifier.weight(1f))
        NavItem(null, selected = false, onClick = onSearch, icon = EmberfinIcons.Search)
        Spacer(Modifier.width(4.dp))
        ProfileButton(profileName, profileArt, onProfile)
        Spacer(Modifier.width(4.dp))
        NavItem(null, selected = false, onClick = onSettings, icon = EmberfinIcons.Settings)
    }
}

@Composable
private fun ProfileButton(name: String, art: Art?, onClick: () -> Unit) {
    Row(Modifier.padding(bottom = 6.dp)) {
        Surface(
            onClick = onClick,
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.12f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White),
            modifier = Modifier.height(34.dp).width(34.dp),
        ) {
            Avatar(name, art, size = 30.dp, modifier = Modifier.padding(2.dp))
        }
    }
}
