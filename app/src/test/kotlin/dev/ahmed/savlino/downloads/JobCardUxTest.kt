package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Critique U2 (Downloads search) and U5 (job card): focus + IME, stable card height, honest labels, TalkBack, 200% text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w320dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JobCardUxTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val language get() = context.resources.configuration.locales[0].language
    private val family = SavedMedia(Uri.parse("content://media/external/downloads/1"), "Family.mp4", "Family", "video/mp4", 5_600_000, 1700000000, 720, null, 84000)
    private val song = SavedMedia(Uri.parse("content://media/external/downloads/2"), "Song.m4a", "Song", "audio/mp4", 506_000, 1700000000, null, 128, null)
    private val job = DownloadProgress("a", "A fairly long downloaded video file name.mp4", DownloadPhase.PREPARING,
        total = Size.Exact(100_000_000), qualityHeight = 720)
    private val downloading = job.copy(phase = DownloadPhase.DOWNLOADING, bytes = 25_000_000, bytesPerSecond = 2_500_000.0,
        remainingTransferSeconds = 30, stalled = false)

    private fun host(fontScale: Float = 1f, content: @Composable () -> Unit, check: (ComponentActivity) -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) { content() } }
            compose.waitForIdle(); check(c.get())
        } finally { c.pause().stop().destroy() }
    }
    /** Counts IME requests; Robolectric's InputMethodManager doesn't see Compose's request without real window focus. */
    private val keyboard = object : SoftwareKeyboardController {
        var shown = 0
        override fun show() { shown++ }
        override fun hide() {}
    }
    private fun screen(fontScale: Float = 1f, check: (ComponentActivity) -> Unit) = host(fontScale, {
        CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) { DownloadsScreen(emptyList(), language, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(family, song))) }
    }, check)
    private fun field() = compose.onNode(hasSetTextAction(), useUnmergedTree = true)
    private fun row(title: String) = compose.onNodeWithText(NezzelFormat.firstStrong(title), useUnmergedTree = true)

    private fun searchOpensFocusedWithKeyboardAndCloses(fontScale: Float) = screen(fontScale) { a ->
        field().assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.saved_search), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        field().assertIsDisplayed().assertIsFocused()
        val bounds = field().fetchSemanticsNode().boundsInRoot
        val height = a.window.decorView.height
        assertTrue("Search field out of view: $bounds", bounds.top >= 0f && bounds.bottom <= height && bounds.left >= -.5f &&
            bounds.right <= a.window.decorView.width + .5f)
        assertEquals("Keyboard was not requested once", 1, keyboard.shown)

        field().performTextInput("Song")
        compose.waitForIdle()
        row("Song").assertExists(); row("Family").assertDoesNotExist()

        compose.onNodeWithContentDescription(context.getString(R.string.saved_search_close), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        field().assertDoesNotExist()
        row("Family").assertExists(); row("Song").assertExists()
    }
    @Test fun searchOpensFocusedWithKeyboardFiltersAndCloses() = searchOpensFocusedWithKeyboardAndCloses(1f)
    @Test @Config(qualifiers = "ar-w320dp-h800dp-mdpi") fun searchWorksInRtlAtDoubleFont() = searchOpensFocusedWithKeyboardAndCloses(2f)

    @Test fun clearKeepsSearchOpenAndRestoresRows() = screen { _ ->
        compose.onNodeWithContentDescription(context.getString(R.string.saved_search), useUnmergedTree = true).performClick()
        field().performTextInput("Song"); compose.waitForIdle()
        compose.onNodeWithContentDescription(context.getString(R.string.saved_search_clear), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        field().assertIsFocused(); row("Family").assertExists()
    }

    private fun noMatchesOffersRecovery() = screen { _ ->
        compose.onNodeWithContentDescription(context.getString(R.string.saved_search), useUnmergedTree = true).performClick()
        field().performTextInput("missing file"); compose.waitForIdle()
        field().assertContentDescriptionContains(context.getString(R.string.saved_search))
        compose.onNodeWithText(context.getString(R.string.saved_no_matches)).assertExists()
        compose.onNodeWithText(context.getString(R.string.saved_search_clear)).performClick()
        compose.waitForIdle()
        row("Family").assertExists(); row("Song").assertExists()
        field().assertExists()
    }
    @Test fun noMatchesCanClearSearchEnglish() = noMatchesOffersRecovery()
    @Test @Config(qualifiers = "ar-w320dp-h800dp-mdpi")
    fun noMatchesCanClearSearchArabic() = noMatchesOffersRecovery()

    private fun cardHeightsStayFixed(fontScale: Float, width: Int = 320) {
        var progress by mutableStateOf(job)
        host(fontScale, {
            Column(Modifier.width(width.dp).padding(16.dp)) { JobRow(progress, {}, {}, {}, Modifier.testTag("card")) }
        }) { a ->
            val states = listOf(job, job.copy(waiting = true), downloading, downloading.copy(bytes = 100_000_000, remainingTransferSeconds = null),
                job.copy(phase = DownloadPhase.JOINING), job.copy(phase = DownloadPhase.SAVING), job.copy(phase = DownloadPhase.COMPLETED))
            val heights = states.map { state ->
                compose.runOnIdle { progress = state }; compose.waitForIdle()
                state.phase to compose.onNodeWithTag("card").fetchSemanticsNode().size.height
            }
            assertEquals("Card height jumped across phases: $heights", 1, heights.map { it.second }.distinct().size)
            // Owner 2026-10-19: a running download is one flat list row, not a 160dp hero card.
            // Robolectric's Arabic fallback font is taller than Roboto's line height, so the dp check is English-only.
            if (fontScale == 1f && language == "en") assertEquals("Row is not the 72dp list height: $heights", 72f,
                heights.first().second / a.resources.displayMetrics.density, .5f)
            // No text loses lines at this scale (ellipsis only on the filename, by design).
            compose.runOnIdle { progress = downloading }; compose.waitForIdle()
            compose.onAllNodes(hasAnyAncestor(hasTestTag("card")) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                .fetchSemanticsNodes().forEach { n ->
                    val layout = mutableListOf<TextLayoutResult>()
                    n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
                    // The filename ellipsizes by design (2 lines); TalkBack still reads it whole.
                    if (job.filename in layout.single().layoutInput.text.text) return@forEach
                    assertFalse("Clipped vertically: ${layout.single().layoutInput.text}", layout.single().didOverflowHeight)
                }
        }
    }
    @Test fun cardKeepsOneHeightFromPreparingToSaved() = cardHeightsStayFixed(1f)
    @Test @Config(qualifiers = "en-w360dp-h800dp-mdpi") fun rowWithKindTileKeepsOneHeightAt360() = cardHeightsStayFixed(1f, 360)
    @Test @Config(qualifiers = "ar-w360dp-h800dp-mdpi") fun rowWithKindTileKeepsOneHeightAt360Arabic() = cardHeightsStayFixed(1f, 360)
    @Test fun cardKeepsOneHeightAtDoubleFont() = cardHeightsStayFixed(2f)
    @Test @Config(qualifiers = "ar-w320dp-h800dp-mdpi") fun cardKeepsOneHeightAtDoubleFontArabic() = cardHeightsStayFixed(2f)

    @Test fun fullTransferSaysFinishingAndSavedSaysSaved() {
        var progress by mutableStateOf(downloading.copy(bytes = 100_000_000, remainingTransferSeconds = null))
        host(content = { JobRow(progress, {}, {}, {}) }) { _ ->
            compose.onNodeWithText(context.getString(R.string.download_finishing), useUnmergedTree = true).assertExists()
            compose.onNodeWithText(context.getString(R.string.download_transferring), useUnmergedTree = true).assertDoesNotExist()
            assertEquals(context.getString(R.string.download_finishing), DownloadNotifications.phase(context, progress))
            compose.runOnIdle { progress = progress.copy(phase = DownloadPhase.COMPLETED) }; compose.waitForIdle()
            compose.onNodeWithText(context.getString(R.string.download_saved), useUnmergedTree = true).assertExists()
        }
    }

    @Test fun runningCardHasNoPlayAffordanceOnlyCancel() = host(content = { JobRow(downloading, {}, {}, {}) }) { _ ->
        val clickable = compose.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, clickable.size)
        assertTrue(clickable.single().config[SemanticsProperties.ContentDescription].single()
            .startsWith(context.getString(R.string.download_cancel_accessibility, "").trim()))
        compose.onAllNodes(hasContentDescription("Play", substring = true, ignoreCase = true), useUnmergedTree = true).assertCountEquals(0)
        assertEquals(R.drawable.ic_movie, downloading.kindIcon())
        assertEquals(R.drawable.ic_music, downloading.copy(mimeType = "audio/mp4").kindIcon())
        assertEquals(R.drawable.ic_image, downloading.copy(mimeType = "image/jpeg").kindIcon())
    }

    private fun announcesPercentAndTimeLeft() = host(content = { JobRow(downloading, {}, {}, {}) }) { _ ->
        val ring = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
            .fetchSemanticsNodes().single { it.config[SemanticsProperties.ProgressBarRangeInfo].current == .25f }
        val spoken = ring.config[SemanticsProperties.StateDescription]
        assertTrue(spoken, spoken.contains("25%"))
        assertTrue(spoken, spoken.contains(context.getString(R.string.download_transfer_remaining, NezzelFormat.isolate("0:30"))))
    }
    @Test fun talkBackHearsPercentAndTimeLeft() = announcesPercentAndTimeLeft()
    @Test @Config(qualifiers = "ar-w320dp-h800dp-mdpi") fun talkBackHearsPercentAndTimeLeftArabic() = announcesPercentAndTimeLeft()

    @Test fun sizeKeepsNumberAndUnitTogether() {
        assertEquals("5.6 MB", NezzelFormat.size(5_600_000, "en"))
        assertEquals("506 KB", NezzelFormat.size(506_000, "ar"))
        assertFalse(NezzelFormat.size(1_400_000_000, "ar").contains(' '))
    }

    @Test fun rowBytesSharesMatchingUnitsButKeepsDifferentUnits() {
        var progress by mutableStateOf(downloading.copy(bytes = 12_000_000, total = Size.Exact(30_000_000)))
        host(content = { JobRow(progress, {}, {}, {}) }) { _ ->
            compose.onNodeWithText("\u206612\u2069 of \u206630\u00a0MB\u2069", useUnmergedTree = true).assertExists()
            compose.runOnIdle { progress = progress.copy(bytes = 900_000, total = Size.Exact(1_200_000_000)) }
            compose.waitForIdle()
            compose.onNodeWithText("\u2066900\u00a0KB\u2069 of \u20661.2\u00a0GB\u2069", useUnmergedTree = true).assertExists()
        }
    }

    @Test @Config(qualifiers = "ar-w360dp-h800dp-mdpi")
    fun mixedArabicSavedTitleUsesFirstStrongInRowAndActionSheet() {
        val item = family.copy(title = "رحلة Android")
        host(content = { DownloadsScreen(emptyList(), language, {}, {}, {}, {}, {}, null, {}, {},
            libraryLoad = LibraryLoadState.Ready(listOf(item))) }) { _ ->
            compose.onNodeWithText("\u2068رحلة Android\u2069", useUnmergedTree = true).assertExists()
            compose.onNodeWithContentDescription(context.getString(R.string.saved_more, item.title), useUnmergedTree = true).performClick()
            compose.waitForIdle()
            compose.onAllNodesWithText("\u2068رحلة Android\u2069", useUnmergedTree = true).assertCountEquals(2)
        }
    }

    private fun savedSizeStaysOnOneLine() = screen(2f) { _ ->
        listOf("5.6 MB", "506 KB").forEach { size ->
            val node = compose.onNodeWithText(size, substring = true, useUnmergedTree = true).fetchSemanticsNode()
            val layout = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            val result = layout.single()
            val text = result.layoutInput.text.text
            val start = text.indexOf(size)
            assertEquals("Size split across lines: $text", result.getLineForOffset(start), result.getLineForOffset(start + size.length - 1))
            assertFalse("Saved meta clipped: $text", result.hasVisualOverflow)
        }
    }
    @Test fun savedRowSizeStaysOnOneLineAtDoubleFont() = savedSizeStaysOnOneLine()
    @Test @Config(qualifiers = "ar-w320dp-h800dp-mdpi") fun savedRowSizeStaysOnOneLineAtDoubleFontArabic() = savedSizeStaysOnOneLine()

    /** Every meta part on one line on a 360dp phone: percent, short time left and shared-unit bytes; speed stays in the notification. */
    private fun meteredRow(width: Int) =
        host(content = { Column(Modifier.width(width.dp).padding(16.dp)) { JobRow(downloading, {}, {}, {}) } }) { _ ->
            listOf(NezzelFormat.isolate("25%"), context.getString(R.string.download_row_remaining, NezzelFormat.isolate("0:30")), context.getString(R.string.download_row_bytes, NezzelFormat.isolate("25"), NezzelFormat.isolate("100\u00a0MB")))
                .forEach { compose.onNodeWithText(it, useUnmergedTree = true).assertExists() }
            compose.onNodeWithText("/s", substring = true, useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.download_transferring), useUnmergedTree = true).assertDoesNotExist()
        }
    @Test @Config(qualifiers = "en-w360dp-h800dp-mdpi") fun meteredRowShowsPercentTimeLeftAndBytesWithoutSpeed() = meteredRow(360)
    @Test @Config(qualifiers = "en-w412dp-h800dp-mdpi") fun meteredRowWithKindTileShowsAllParts() = meteredRow(412)

    /** Shadow elevations set anywhere in a node's modifier chain (Surface draws its shadow through a graphics layer). */
    private fun shadows(tag: String): List<Float> {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        return node.layoutInfo.getModifierInfo().mapNotNull { Regex("shadowElevation=([0-9.]+)").find(it.modifier.toString())?.groupValues?.get(1)?.toFloat() }
            .filter { it > 0f }
    }
    @Test fun shadowProbeSeesAnElevatedSurface() = host(content = {
        androidx.compose.material3.Surface(Modifier.size(40.dp).testTag("lifted"), shadowElevation = 1.dp) {}
    }) { _ -> assertTrue("Probe can't see a 1dp shadow", shadows("lifted").isNotEmpty()) }

    /** Level 0: the row's edge is its tonal fill, with no shadow in light or dark. */
    private fun rowIsTonalWithNoShadow(dark: Boolean) {
        var surface = 0; var fill = 0
        RuntimeEnvironment.setFontScale(1f)
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(if (dark) ThemePref.Dark else ThemePref.Light, false) {
                surface = MaterialTheme.colorScheme.surface.toArgb()
                fill = (if (dark) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh).toArgb()
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.width(320.dp).padding(16.dp)) { JobRow(downloading, {}, {}, {}, Modifier.testTag("card")) }
                }
            } }
            compose.waitForIdle()
            assertEquals("Row casts a shadow", emptyList<Float>(), shadows("card"))
            val view = c.get().window.decorView
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            val bounds = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInWindow
            val density = c.get().resources.displayMetrics.density
            // Inside the 16dp start padding, clear of text and corners.
            assertEquals("Row fill is not the tonal container", fill, image.getPixel((bounds.left + 8 * density).toInt(), bounds.center.y.toInt()))
            assertNotEquals("Tonal fill must differ from the page", surface, fill)
        } finally { c.pause().stop().destroy() }
    }
    @Test fun rowIsTonalWithNoShadowLight() = rowIsTonalWithNoShadow(false)
    @Test fun rowIsTonalWithNoShadowDark() = rowIsTonalWithNoShadow(true)

    /** Failed is terminal: it may be taller, but Retry and Remove both stay 48dp and inside the row. */
    @Test fun failedRowKeepsRetryAndRemoveReachable() {
        var retried = 0; var removed = 0
        val failed = job.copy(phase = DownloadPhase.FAILED, error = dev.ahmed.savlino.domain.DownloadError.Network, retryable = true)
        host(content = { Column(Modifier.width(320.dp).padding(16.dp)) { JobRow(failed, {}, { removed++ }, { retried++ }, Modifier.testTag("card")) } }) { a ->
            val row = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
            val density = a.resources.displayMetrics.density
            listOf(compose.onNodeWithText(context.getString(R.string.try_again), useUnmergedTree = true),
                compose.onNodeWithContentDescription(context.getString(R.string.download_remove), useUnmergedTree = true)).forEach { node ->
                val b = node.fetchSemanticsNode().boundsInRoot
                assertTrue("Action outside the row: $b in $row", b.left >= row.left - .5f && b.right <= row.right + .5f && b.bottom <= row.bottom + .5f)
            }
            compose.onNodeWithText(context.getString(R.string.try_again), useUnmergedTree = true).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.download_remove), useUnmergedTree = true).performClick()
            assertEquals(1, retried); assertEquals(1, removed)
            assertTrue("Failed row shorter than a list row", row.height / density >= 71.5f)
        }
    }
    /** C1: an OS battery stop names battery and offers Battery settings until Nezzel is exempt. */
    @Test fun batteryStopShowsBatterySettingsUntilExempt() {
        val stopped = job.copy(phase = DownloadPhase.FAILED, error = dev.ahmed.savlino.domain.DownloadError.StoppedByBattery, retryable = true)
        host(content = { Column(Modifier.width(320.dp).padding(16.dp)) { JobRow(stopped, {}, {}, {}) } }) { a ->
            compose.onNodeWithText(context.getString(R.string.download_battery_error), useUnmergedTree = true).assertExists()
            compose.onNodeWithText(context.getString(R.string.battery_settings)).assertHasClickAction().performClick()
            assertEquals(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, Shadows.shadowOf(a).nextStartedActivity?.action)
        }
    }
    @Test fun batteryStopHidesButtonWhenExempt() {
        Shadows.shadowOf(context.getSystemService(android.os.PowerManager::class.java)).setIgnoringBatteryOptimizations(context.packageName, true)
        val stopped = job.copy(phase = DownloadPhase.FAILED, error = dev.ahmed.savlino.domain.DownloadError.StoppedByBattery, retryable = true)
        host(content = { Column(Modifier.width(320.dp).padding(16.dp)) { JobRow(stopped, {}, {}, {}) } }) { _ ->
            compose.onNodeWithText(context.getString(R.string.download_battery_error), useUnmergedTree = true).assertExists()
            compose.onNodeWithText(context.getString(R.string.battery_settings), useUnmergedTree = true).assertDoesNotExist()
        }
    }
    @Test fun otherFailuresShowNoBatteryButton() {
        val failed = job.copy(phase = DownloadPhase.FAILED, error = dev.ahmed.savlino.domain.DownloadError.Network, retryable = true)
        host(content = { Column(Modifier.width(320.dp).padding(16.dp)) { JobRow(failed, {}, {}, {}) } }) { _ ->
            compose.onNodeWithText(context.getString(R.string.battery_settings), useUnmergedTree = true).assertDoesNotExist()
        }
    }
}
