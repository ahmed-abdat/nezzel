package dev.ahmed.savlino.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ahmed.savlino.R
import dev.ahmed.savlino.NavigationScaffold
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w800dp-h360dp-mdpi")
class NavigationLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun landscapeShowsRailAndAllDestinationsWorkWithRunningAndFailedSemantics() = navigation(true)

    @Test @Config(qualifiers = "en-w360dp-h800dp-mdpi")
    fun portraitKeepsBarAndDestinationsWork() = navigation(false)

    // Real font metrics (native graphics) so a clipped or ellipsized label shows up as overflow.
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [35], qualifiers = "en-w360dp-h800dp-xxhdpi")
    fun englishPortraitBarFitsFourLabelsAtLargeText() = navigation(false, checkClip = true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [35], qualifiers = "ar-w360dp-h800dp-xxhdpi")
    fun arabicPortraitBarFitsFourLabelsAtLargeText() = navigation(false, rtl = true, checkClip = true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [35], qualifiers = "fr-w360dp-h800dp-xxhdpi")
    fun frenchPortraitBarFitsFourLabelsAtLargeText() = navigation(false, checkClip = true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [35], qualifiers = "fr-w800dp-h360dp-xxhdpi")
    fun frenchLandscapeRailFitsFourLabelsAtLargeText() = navigation(true, checkClip = true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [35], qualifiers = "en-w360dp-h800dp-xxhdpi")
    fun englishPortraitLabelsRemainInsideTheirTabsAtDoubleFont() = navigation(false, fontScale = 2f, checkClip = true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [35], qualifiers = "ar-w360dp-h800dp-xxhdpi")
    fun arabicPortraitLabelsRemainInsideTheirTabsAtDoubleFont() = navigation(false, rtl = true, fontScale = 2f, checkClip = true)

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) @Config(sdk = [35], qualifiers = "fr-w360dp-h800dp-xxhdpi")
    fun frenchPortraitLabelsRemainInsideTheirTabsAtDoubleFont() = navigation(false, fontScale = 2f, checkClip = true)

    @Test @Config(qualifiers = "en-w800dp-h480dp-mdpi")
    fun height480KeepsBar() = navigation(false)

    @Test @Config(qualifiers = "ar-w800dp-h360dp-mdpi")
    fun arabicLandscapeKeepsLabelsAndBadgeAtLargeText() = navigation(true, rtl = true)

    @Test fun landscapeDestinationsRemainReachableAtDoubleFont() = navigation(true, fontScale = 2f)

    @Test @Config(qualifiers = "ar-w800dp-h360dp-mdpi")
    fun arabicLandscapeDestinationsRemainReachableAtDoubleFont() = navigation(true, rtl = true, fontScale = 2f)

    @Test @Config(sdk = [35]) fun landscapeProtectsCutoutAndNavigationBarAtBothEdges() = navigation(true, sideInsets = true)

    @Test @Config(sdk = [35], qualifiers = "ar-w800dp-h360dp-mdpi")
    fun arabicLandscapeProtectsCutoutAndNavigationBarAtBothEdges() = navigation(true, rtl = true, sideInsets = true)

    private fun navigation(rail: Boolean, rtl: Boolean = false, sideInsets: Boolean = false, fontScale: Float = 1.3f, checkClip: Boolean = false) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        var route by mutableStateOf("Home")
        var jobs by mutableStateOf(listOf(
            DownloadProgress("a", "one", DownloadPhase.DOWNLOADING),
            DownloadProgress("b", "two", DownloadPhase.FAILED)))
        lateinit var composeView: android.view.View
        try {
            activity.setContent {
                val view = LocalView.current
                SideEffect { composeView = view }
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    NezzelTheme(if (rtl) ThemePref.Dark else ThemePref.Light, false) {
                        NavigationScaffold(route, { jobs }, if (rtl) "ar" else "en", { route = it }) {
                            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                                .testTag("screen_content"))
                        }
                    }
                }
            }
            if (sideInsets) compose.runOnIdle {
                ViewCompat.dispatchApplyWindowInsets(composeView, WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 24, 0))
                    .setDisplayCutout(DisplayCutoutCompat(android.graphics.Rect(30, 0, 0, 0), emptyList()))
                    .build())
            }
            compose.onNodeWithTag(if (rail) "navigation_rail" else "navigation_bar").assertIsDisplayed()
            compose.onNodeWithTag(if (rail) "navigation_bar" else "navigation_rail").assertDoesNotExist()
            val navigation = compose.onNodeWithTag(if (rail) "navigation_rail" else "navigation_bar").fetchSemanticsNode().boundsInRoot
            val content = compose.onNodeWithTag("screen_content").fetchSemanticsNode().boundsInRoot
            if (sideInsets) {
                assertTrue("Cutout must be excluded: $navigation / $content", minOf(content.left, navigation.left) >= 30f)
                assertTrue("Side navigation bar must be excluded", maxOf(content.right, navigation.right) <= composeView.width - 24f)
                assertEquals("Horizontal insets are consumed once", 800f - 30f - 24f, content.width + navigation.width, 1f)
            }
            if (rail) {
                assertTrue("Rail must leave the full height to content", content.height >= navigation.height)
                if (rtl) assertTrue("Arabic rail belongs at start (right)", content.right <= navigation.left)
                else assertTrue("English rail belongs at start (left)", navigation.right <= content.left)
            } else {
                assertTrue("Portrait bar stays below content", content.bottom <= navigation.top)
            }
            val labels = listOf("Settings" to R.string.settings, "Statuses" to R.string.statuses_tab, "Downloads" to R.string.shortcut_downloads_short, "Home" to R.string.home)
            for ((destination, label) in labels) {
                val item = compose.onNode(hasText(activity.getString(label)) and hasClickAction())
                if (rail) item.performScrollTo()
                item.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
                item.performClick().assertIsSelected()
                val semantics = item.fetchSemanticsNode().config
                assertEquals(Role.Tab, semantics[SemanticsProperties.Role])
                val position = semantics[SemanticsProperties.CollectionItemInfo]
                val index = listOf("Home", "Downloads", "Statuses", "Settings").indexOf(destination)
                assertEquals(if (rail) index else 0, position.rowIndex)
                assertEquals(if (rail) 0 else index, position.columnIndex)
                compose.runOnIdle { assertEquals(destination, route) }
            }
            if (checkClip) System.getenv("NEZZEL_NAV_RENDER_DIR")?.let { directory ->
                compose.runOnIdle {
                    val root = activity.window.decorView
                    val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(bitmap))
                    val locale = activity.resources.configuration.locales[0].language
                    java.io.File(directory).mkdirs()
                    java.io.File(directory, "$locale-$fontScale-$rail.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            // Four labels, none ellipsized: each keeps its full text on screen (spec T5: icon-only is not allowed).
            if (checkClip) for ((_, label) in labels) {
                val text = compose.onAllNodes(hasText(activity.getString(label)) and
                    SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).onFirst().fetchSemanticsNode()
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                text.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
                layouts.single().let {
                    // Center-aligned labels report width "overflow" against their paragraph box; ellipsis or cut height are the real clips.
                    assertFalse("${activity.getString(label)} must not ellipsize: lines=${it.lineCount}", it.isLineEllipsized(it.lineCount - 1))
                    assertFalse("${activity.getString(label)} must not clip vertically", it.didOverflowHeight)
                    val item = compose.onNode(hasText(activity.getString(label)) and hasClickAction()).fetchSemanticsNode().boundsInRoot
                    val origin = text.positionInRoot
                    for (offset in it.layoutInput.text.indices.filterNot { i -> it.layoutInput.text[i].isWhitespace() }) {
                        val glyph = it.getBoundingBox(offset).translate(origin)
                        assertTrue("${activity.getString(label)} glyph $offset must stay inside its tab: $glyph / $item",
                            rail || (glyph.left >= item.left - 1f && glyph.right <= item.right + 1f && glyph.top >= item.top - 1f && glyph.bottom <= item.bottom + 1f))
                    }
                }
            }
            if (!rail) {
                val tops = labels.map { compose.onNode(hasText(activity.getString(it.second)) and hasClickAction()).fetchSemanticsNode().boundsInRoot }
                assertTrue("All four bar items sit inside the one bar: $tops / $navigation", tops.all { it.top >= navigation.top && it.bottom <= navigation.bottom + 1f })
                // Bar order follows reading direction: Home, Downloads, Statuses, Settings from the start edge.
                val starts = tops.reversed().map { if (rtl) -it.right else it.left }
                assertEquals(starts.sorted(), starts)
            }
            compose.onNodeWithContentDescription(activity.getString(R.string.downloads_running_accessibility, "1")).assertExists()
            compose.runOnIdle { jobs = jobs.takeLast(1) }
            compose.onNodeWithContentDescription(activity.getString(R.string.downloads_failed_accessibility)).assertExists()
        } finally { controller.pause().stop().destroy() }
    }
}
