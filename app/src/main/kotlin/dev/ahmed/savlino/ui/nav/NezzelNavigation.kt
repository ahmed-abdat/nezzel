package dev.ahmed.savlino.ui.nav

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import dev.ahmed.savlino.viewer.miniPlayerTabs
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.ahmed.savlino.ui.theme.Dock
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
        TabBar(destinations, selected, Modifier.testTag("navigation_bar").then(collection)) { destination, index, isSelected ->
            Column(Modifier.weight(1f).fillMaxHeight().heightIn(min = 54.dp).clip(RoundedCornerShape(27.dp))
                .selectable(isSelected, role = Role.Tab) { select(destination.route) }.then(itemModifier(destination, index))
                .padding(horizontal = Space.s, vertical = Space.s),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                TabIcon(destination.route, isSelected, badges, updateDescription)
                TabLabel(stringResource(destination.label), isSelected)
            }
        }
    }
}

/**
 * T1: a pill floating above the content (owner pick B, 2026-10-25). The raised inner pill travels on a spatial
 * spring; it is placed in the layout's placement step only, so moving it never recomposes the tabs. Badges sit on the
 * icon and never shift a label.
 */
@Composable
private fun TabBar(destinations: List<NavigationDestination>, selected: String, modifier: Modifier,
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
        }.shadow(1.dp, RoundedCornerShape(27.dp)).background(colors.surfaceBright, RoundedCornerShape(27.dp)))
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            destinations.forEachIndexed { i, destination -> item(destination, i, destination.route == selected) }
        }
    }
    Box(modifier.fillMaxWidth().windowInsetsPadding(insets).padding(start = Dock.INSET, end = Dock.INSET, top = Dock.GAP, bottom = Space.m)) {
        // Shrunk music sits just above the tabs.
        Column {
            dev.ahmed.savlino.viewer.MiniPlayerSlot()
            // Bound the corners: a growing capsule cuts off large labels near its outer edges.
            Surface(Modifier.fillMaxWidth().miniPlayerTabs(), shape = Dock.SHAPE, color = Dock.color, shadowElevation = Dock.SHADOW) {
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
    val style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.sp, textAlign = TextAlign.Center)
    // Both weights are laid out once and the colour is read while drawing, so a selection change never re-lays the
    // text (or the bar's intrinsic height) out; the label also keeps its size when bold would wrap.
    Box(Modifier.padding(top = 2.dp), contentAlignment = Alignment.Center) {
        BasicText(text, Modifier.graphicsLayer { alpha = if (selected) 0f else 1f }, color = { tint.value },
            style = style.copy(fontWeight = FontWeight.Medium))
        BasicText(text, Modifier.clearAndSetSemantics {}.graphicsLayer { alpha = if (selected) 1f else 0f }, color = { tint.value },
            style = style.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun NavLabel(text: String) = Text(text, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
