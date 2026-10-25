package dev.ahmed.savlino.ui.nav

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.outlined.Home as OutlinedHome
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Settings as OutlinedSettings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.Motion
import dev.ahmed.savlino.ui.theme.Space
import dev.ahmed.savlino.ui.theme.motionSpec
import kotlin.math.roundToInt
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

/** Owner pick pending (beta12 feedback): true = A, docked edge to edge; false = B, floating with a lighter shadow. Delete the loser. */
internal val LocalDockedTabBar = staticCompositionLocalOf { true }

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
        TabBar(destinations, selected, LocalDockedTabBar.current, Modifier.testTag("navigation_bar").then(collection)) { destination, index, isSelected ->
            Column(Modifier.weight(1f).heightIn(min = 54.dp).clip(CircleShape)
                .selectable(isSelected, role = Role.Tab) { select(destination.route) }.then(itemModifier(destination, index)),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                TabIcon(destination.route, isSelected, badges, updateDescription)
                TabLabel(stringResource(destination.label), isSelected)
            }
        }
    }
}

/**
 * T1: docked edge to edge (A) or a pill floating above the content (B). The raised inner pill travels on a spatial
 * spring; it is placed in the layout's placement step only, so moving it never recomposes the tabs. Badges sit on the
 * icon and never shift a label.
 */
@Composable
private fun TabBar(destinations: List<NavigationDestination>, selected: String, docked: Boolean, modifier: Modifier,
                   item: @Composable RowScope.(NavigationDestination, Int, Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val index = destinations.indexOfFirst { it.route == selected }.coerceAtLeast(0)
    val position = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index, reduced) { if (reduced) position.snapTo(index.toFloat()) else position.animateTo(index.toFloat(), Motion.TabIndicator) }
    val count = destinations.size
    val insets = WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    val tabs: @Composable BoxScope.() -> Unit = {
        Box(Modifier.matchParentSize().layout { measurable, constraints ->
            val cell = constraints.maxWidth / count
            val pill = measurable.measure(Constraints.fixed(cell, constraints.maxHeight))
            layout(constraints.maxWidth, constraints.maxHeight) { pill.placeRelative((position.value * cell).roundToInt(), 0) }
        }.then(if (docked) Modifier else Modifier.shadow(1.dp, CircleShape)).background(colors.surfaceBright, CircleShape))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            destinations.forEachIndexed { i, destination -> item(destination, i, destination.route == selected) }
        }
    }
    if (docked) {
        // No shadows: the bar's colour runs under the gesture handle; only its tabs keep clear of it.
        Surface(modifier.fillMaxWidth(), color = colors.surfaceContainer) {
            Box(Modifier.windowInsetsPadding(insets).padding(horizontal = Space.s, vertical = Space.xs)) { tabs() }
        }
    } else {
        Box(modifier.fillMaxWidth().windowInsetsPadding(insets).padding(start = Space.m, end = Space.m, top = Space.s, bottom = Space.m)) {
            Surface(Modifier.fillMaxWidth(), shape = CircleShape, color = colors.surfaceContainerHigh, shadowElevation = 1.dp) {
                Box(Modifier.padding(Space.xs)) { tabs() }
            }
        }
    }
}

// Outlined at rest, filled when selected: the two cross-fade on an effects spring read in the draw phase.
private val OutlinedDownload = ImageVector.Builder("download-outlined", 24.dp, 24.dp, 24f, 24f).path(
    stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
    moveTo(12f, 3.5f); lineTo(12f, 15f); moveTo(6.5f, 9.8f); lineTo(12f, 15.3f); lineTo(17.5f, 9.8f); moveTo(5f, 20f); lineTo(19f, 20f)
}.build()

// Same segmented ring as ic_status_ring; the centre dot is hollow until selected.
private val OutlinedStatus = ImageVector.Builder("status-outlined", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(addPathNodes("M13.77,3.69 A8.5,8.5 0 0,1 20.31,10.23 M20.31,13.77 A8.5,8.5 0 0,1 13.77,20.31 M10.23,20.31 A8.5,8.5 0 0,1 3.69,13.77 M3.69,10.23 A8.5,8.5 0 0,1 10.23,3.69"),
        stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f, strokeLineCap = StrokeCap.Round)
    addPath(addPathNodes("M12,8.6 A3.4,3.4 0 1,1 11.99,8.6z"), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f)
}.build()

@Composable
private fun TabIcon(route: String, selected: Boolean, badges: NavigationBadges, updateDescription: String) {
    val colors = MaterialTheme.colorScheme
    val fill = animateFloatAsState(if (selected) 1f else 0f, motionSpec(LocalReducedMotion.current, Motion.TabIconFill), label = "fill")
    val (outlined, filled) = when (route) {
        "Home" -> rememberVectorPainter(Icons.Outlined.OutlinedHome) to rememberVectorPainter(Icons.Filled.Home)
        "Statuses" -> rememberVectorPainter(OutlinedStatus) to painterResource(R.drawable.ic_status_ring)
        "Settings" -> rememberVectorPainter(Icons.Outlined.OutlinedSettings) to rememberVectorPainter(Icons.Filled.Settings)
        else -> rememberVectorPainter(OutlinedDownload) to painterResource(R.drawable.ic_download)
    }
    BadgedBox(badge = {
        // Fixed-size dots on the icon's top-end corner (mirrored in RTL): no label moves when one appears.
        when {
            route == "Settings" && badges.update -> Badge(Modifier.semantics { contentDescription = updateDescription }, containerColor = colors.primary)
            route == "Downloads" && badges.running > 0 -> Badge(Modifier.offset(x = 2.dp, y = (-2).dp), containerColor = colors.primary)
            route == "Downloads" && badges.failed -> Badge(Modifier.offset(x = 2.dp, y = (-2).dp))
        }
    }) {
        Box(Modifier.size(24.dp)) {
            Icon(outlined, null, Modifier.fillMaxSize().graphicsLayer { alpha = 1f - fill.value }, tint = colors.onSurfaceVariant)
            Icon(filled, null, Modifier.fillMaxSize().graphicsLayer { alpha = fill.value }, tint = colors.primary)
        }
    }
}

@Composable
private fun TabLabel(text: String, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tint = animateColorAsState(if (selected) colors.onSurface else colors.onSurfaceVariant,
        motionSpec(LocalReducedMotion.current, Motion.TabLabelColor), label = "label")
    // The colour is read while drawing, so a selection change never re-lays the text out.
    BasicText(text, Modifier.padding(top = 2.dp), color = { tint.value }, maxLines = 2, overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium))
}

@Composable
private fun NavLabel(text: String) = Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
