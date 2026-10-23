package dev.ahmed.savlino.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.NezzelDarkColors
import dev.ahmed.savlino.ui.theme.NezzelLightColors
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Option C: the card is the progress. The fill needs a real fraction, grows from the start edge, and keeps the row at 72dp. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FillRowTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val running = DownloadProgress("a", "Holiday clip.mp4", DownloadPhase.DOWNLOADING, bytes = 25_000_000, total = Size.Exact(100_000_000),
        bytesPerSecond = 2_500_000.0, remainingTransferSeconds = 30, stalled = false, qualityHeight = 720)
    private val paused = running.copy(phase = DownloadPhase.PREPARING, paused = true, hasPartial = true,
        bytesPerSecond = null, remainingTransferSeconds = null, stalled = null)
    private val waiting = DownloadProgress("a", "Holiday clip.mp4", DownloadPhase.PREPARING, waiting = true, hasPartial = true, total = Size.Exact(100_000_000))
    private val restarting = DownloadProgress("a", "Holiday clip.mp4", DownloadPhase.PREPARING, total = Size.Exact(100_000_000), restartEpoch = 1)
    private val unknownTotal = DownloadProgress("a", "Holiday clip.mp4", DownloadPhase.DOWNLOADING, bytes = 5_000_000, stalled = false)
    private val failed = DownloadProgress("a", "Holiday clip.mp4", DownloadPhase.FAILED, error = DownloadError.Network, retryable = true)

    private fun render(job: DownloadProgress, offline: Boolean = false, check: () -> Unit) {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(LocalOffline provides offline, LocalReducedMotion provides true) { JobRow(job, {}, {}, {}, Modifier.testTag("card")) }
            } }
            compose.waitForIdle(); check()
        } finally { c.pause().stop().destroy() }
    }
    private fun fill() = compose.onAllNodesWithTag("job-fill", useUnmergedTree = true).fetchSemanticsNodes()

    @Test fun onlyARealFractionDrawsAFill() {
        assertEquals(.25f, running.fillFraction()!!, .001f)
        assertEquals(.25f, paused.fillFraction()!!, .001f)
        assertNull(paused.copy(bytes = null).fillFraction())
        assertNull(waiting.fillFraction()); assertNull(restarting.fillFraction()); assertNull(unknownTotal.fillFraction()); assertNull(failed.fillFraction())
        render(running) { assertEquals(1, fill().size) }
        render(paused) { assertEquals(1, fill().size) }
        for (job in listOf(waiting, restarting, unknownTotal, failed)) render(job, offline = true) { assertEquals(job.phase.toString(), 0, fill().size) }
    }

    @Test fun cardCarriesTheRealFractionForTalkBack() {
        render(running) {
            val range = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
                .fetchSemanticsNodes().single().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(.25f, range.current, .001f)
        }
        render(waiting, offline = true) {
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true)
                .assertCountEquals(0)
        }
    }

    @Test fun everyStateIsOneFlat72dpRowWithTheButtonsBesideTheText() {
        for (job in listOf(running, paused, waiting, restarting, failed)) render(job, offline = true) {
            val card = compose.onNodeWithTag("card").fetchSemanticsNode()
            assertEquals(job.phase.toString(), 72f, card.size.height / 1f, 1f)
            val title = compose.onAllNodesWithText("Holiday clip.mp4", substring = true, useUnmergedTree = true).fetchSemanticsNodes().first()
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().forEach { button ->
                assertTrue("Buttons sit beside the text, never under it: ${button.boundsInRoot} vs ${title.boundsInRoot}",
                    button.boundsInRoot.top < title.boundsInRoot.bottom)
                assertTrue("48dp target", button.size.width >= 48 && button.size.height >= 48)
            }
        }
    }

    @Test @Config(qualifiers = "en-w384dp-h800dp-mdpi")
    fun fillGrowsFromTheLeftEdgeInEnglish() = render(running) {
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        val fill = fill().single().boundsInRoot
        assertEquals(card.left, fill.left, 1f)
        assertEquals(card.width * .25f, fill.width, 2f)
    }

    @Test @Config(qualifiers = "ar-w384dp-h800dp-mdpi")
    fun fillGrowsFromTheRightEdgeInArabic() = render(running) {
        val card = compose.onNodeWithTag("card").fetchSemanticsNode().boundsInRoot
        val fill = fill().single().boundsInRoot
        assertEquals(card.right, fill.right, 1f)
        assertEquals(card.width * .25f, fill.width, 2f)
    }

    @Test @Config(qualifiers = "ar-w384dp-h800dp-mdpi")
    fun filenameStaysLtrAndStartAlignedInArabic() = render(running) {
        val title = compose.onAllNodesWithText("Holiday clip.mp4", substring = true, useUnmergedTree = true).fetchSemanticsNodes().first()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        title.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val style = layouts.single().layoutInput.style
        assertEquals(androidx.compose.ui.text.style.TextDirection.Ltr, style.textDirection)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Right, style.textAlign)
    }

    @Test fun failedRowHasTryAgainAndRemoveButtonsAndNoPill() = render(failed) {
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(dev.ahmed.savlino.R.string.try_again), useUnmergedTree = true).assertExists()
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(dev.ahmed.savlino.R.string.download_remove), useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText(RuntimeEnvironment.getApplication().getString(dev.ahmed.savlino.R.string.try_again), useUnmergedTree = true).assertCountEquals(0)
    }

    private fun contrast(a: Color, b: Color): Float {
        val hi = maxOf(a.luminance(), b.luminance()); val lo = minOf(a.luminance(), b.luminance())
        return (hi + .05f) / (lo + .05f)
    }

    @Test fun textStaysReadableOnTheFillInBothThemes() {
        for ((scheme, light) in listOf(NezzelLightColors to true, NezzelDarkColors to false)) for (paused in listOf(false, true)) {
            val base = rowBase(scheme, light)
            val onFill = fillColor(scheme, paused, light).compositeOver(base)
            val text = listOf("onSurface" to scheme.onSurface, "onSurfaceVariant" to scheme.onSurfaceVariant) +
                if (paused) listOf("primary" to scheme.primary) else listOf("primary" to scheme.primary)
            for ((name, color) in text) {
                assertTrue("$name on the ${if (paused) "paused " else ""}fill, light=$light: ${contrast(color, onFill)}", contrast(color, onFill) >= 4.5f)
                assertTrue("$name on the bare row, light=$light", contrast(color, base) >= 4.5f)
            }
        }
    }
}
