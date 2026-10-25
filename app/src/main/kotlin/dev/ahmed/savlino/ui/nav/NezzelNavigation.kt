package dev.ahmed.savlino.ui.nav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.NezzelFormat

@Immutable
data class NavigationDestination(val route: String, @param:StringRes val label: Int)

val TopLevelDestinations = listOf(
    NavigationDestination("Home", R.string.home),
    NavigationDestination("Downloads", R.string.shortcut_downloads_short),
    NavigationDestination("Statuses", R.string.statuses_tab),
    NavigationDestination("Settings", R.string.settings),
)

/** Small, stable inputs: journal progress ticks do not invalidate the navigation drawing. */
@Immutable
data class NavigationBadges(val running: Int = 0, val failed: Boolean = false, val update: Boolean = false, val language: String = "en")

/** Replace this drawing for a new design; selection, re-selection and content state stay in the host. */
@Composable
fun NezzelNavigation(
    destinations: List<NavigationDestination>, selected: String, badges: NavigationBadges,
    onSelect: (String) -> Unit, onReselect: (String) -> Unit,
) {
    val rail = LocalConfiguration.current.screenHeightDp < 480
    val count = NezzelFormat.count(badges.running, badges.language)
    val downloadLabel = stringResource(when {
        badges.running > 0 -> R.string.downloads_running_accessibility
        badges.failed -> R.string.downloads_failed_accessibility
        else -> R.string.downloads
    }, count)
    val settingsLabel = stringResource(R.string.settings_update_accessibility)
    val updateDescription = stringResource(R.string.update_badge_description)
    @Composable fun DestinationIcon(destination: String) {
        when (destination) {
            "Home" -> Icon(Icons.Default.Home, null)
            "Statuses" -> Icon(painterResource(R.drawable.ic_status_ring), null)
            "Settings" -> BadgedBox(badge = {
                if (badges.update) Badge(Modifier.semantics { contentDescription = updateDescription }, containerColor = MaterialTheme.colorScheme.primary)
            }) { Icon(Icons.Default.Settings, null) }
            else -> BadgedBox(badge = {
                if (badges.running > 0) Badge(Modifier.offset(x = 4.dp, y = (-2).dp), containerColor = MaterialTheme.colorScheme.primary)
                else if (badges.failed) Badge(Modifier.offset(x = 4.dp, y = (-2).dp))
            }) { Icon(painterResource(R.drawable.ic_download), null) }
        }
    }
    fun itemModifier(destination: NavigationDestination, index: Int) = Modifier.heightIn(min = 48.dp).semantics {
        role = Role.Tab
        collectionItemInfo = CollectionItemInfo(if (rail) index else 0, 1, if (rail) 0 else index, 1)
        if (destination.route == "Downloads") contentDescription = downloadLabel
        if (destination.route == "Settings" && badges.update) {
            contentDescription = settingsLabel
        }
    }
    fun select(destination: String) { if (selected == destination) onReselect(destination) else onSelect(destination) }
    val collection = Modifier.semantics { collectionInfo = CollectionInfo(if (rail) destinations.size else 1, if (rail) 1 else destinations.size) }
    if (rail) {
        NavigationRail(modifier = Modifier.testTag("navigation_rail").then(collection).fillMaxHeight().verticalScroll(rememberScrollState()),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical)) {
            destinations.forEachIndexed { index, destination ->
                NavigationRailItem(selected = selected == destination.route, onClick = { select(destination.route) },
                    modifier = itemModifier(destination, index), icon = { DestinationIcon(destination.route) },
                    label = { NavLabel(stringResource(destination.label)) }, alwaysShowLabel = true,
                    colors = NavigationRailItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer))
            }
        }
    } else {
        NavigationBar(modifier = Modifier.testTag("navigation_bar").then(collection), containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            destinations.forEachIndexed { index, destination ->
                NavigationBarItem(selected = selected == destination.route, onClick = { select(destination.route) },
                    modifier = itemModifier(destination, index), icon = { DestinationIcon(destination.route) },
                    label = { NavLabel(stringResource(destination.label)) }, alwaysShowLabel = true,
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer))
            }
        }
    }
}

@Composable
private fun NavLabel(text: String) = Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
