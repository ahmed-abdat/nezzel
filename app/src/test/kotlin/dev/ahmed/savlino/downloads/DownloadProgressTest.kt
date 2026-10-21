package dev.ahmed.savlino.downloads

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDirection
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w320dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadProgressTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val language get() = context.resources.configuration.locales[0].language
    private val rate = "2.5\u00a0MB/s"
    private val remaining get() = if (language == "ar") "متبقٍّ ≈ \u20660:30\u2069" else "≈ \u20660:30\u2069 left"
    private val notificationRemaining get() = if (language == "ar") "متبقٍّ للتنزيل ≈ \u20660:30\u2069" else "≈ \u20660:30\u2069 left to download"
    private val bytes get() = context.getString(R.string.download_row_bytes, "\u206625\u2069", "\u2066100\u00a0MB\u2069")
    private val active get() = DownloadProgress("a", "Actual-file.mp4", DownloadPhase.DOWNLOADING,
        bytes = 25_000_000, total = Size.Exact(100_000_000), bytesPerSecond = 2_500_000.0,
        remainingTransferSeconds = 30, stalled = false)
    private fun render(initial: DownloadProgress, strip: Boolean = false, check: ((DownloadProgress) -> Unit) -> Unit) {
        RuntimeEnvironment.setFontScale(2f)
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var progress by mutableStateOf(initial)
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                if (strip) DownloadStrip(listOf(progress), language) {}
                else DownloadsScreen(listOf(progress), language, {}, {}, {}, {}, {}, null, {}, {})
            } }
            compose.waitForIdle()
            check { next -> compose.runOnIdle { progress = next }; compose.waitForIdle() }
        } finally { c.pause().stop().destroy() }
    }
    private fun text(value: String) = compose.onNodeWithText(value, substring = true, useUnmergedTree = true)
    private fun bars() = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo), useUnmergedTree = true).fetchSemanticsNodes()
    /** Speed never reaches the row (lead 2026-10-19: it stays in the notification). */
    private fun noSpeed() {
        text(rate).assertDoesNotExist()
        text("/s").assertDoesNotExist()
    }
    private fun noTransferMetrics() {
        noSpeed()
        text(remaining).assertDoesNotExist()
        text("25%").assertDoesNotExist()
    }
    private fun valid() = render(active) {
        noSpeed(); text(remaining).assertExists(); text("25%").assertExists()
        assertTrue(bars().any { it.config[SemanticsProperties.ProgressBarRangeInfo].current == .25f })
        // 200% text: the row keeps two meta lines, so bytes are the first part to give way; whatever shows is whole.
        // (The SemiBold percent is left out: Robolectric's synthetic bold reports a phantom width overflow.)
        listOf(remaining, bytes).filter { compose.onAllNodes(hasText(it, substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty() }.forEach { label ->
            val n = text(label).fetchSemanticsNode()
            assertTrue("Metric outside 320dp: ${n.boundsInRoot}", n.boundsInRoot.left >= -.5f && n.boundsInRoot.right <= 320.5f)
            assertNull("Byte ticks must not be live announcements", n.config.getOrNull(SemanticsProperties.LiveRegion))
            val layout = mutableListOf<TextLayoutResult>()
            assertEquals(true, n.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layout))
            // Numbers are LTR tokens; "time left" is a localized sentence with an isolated duration.
            assertEquals(if (label == remaining || label == bytes) TextDirection.Content else TextDirection.Ltr, layout.single().layoutInput.style.textDirection)
            val result = layout.single()
            assertFalse("Metric clipped at 200%: $label; constraints=${result.layoutInput.constraints}, size=${result.size}, widthOverflow=${result.didOverflowWidth}, heightOverflow=${result.didOverflowHeight}", result.hasVisualOverflow)
            assertFalse("Arabic-Indic digit leaked: $label", layout.single().layoutInput.text.text.any { it in '\u0660'..'\u0669' })
        }
        val payload = DownloadNotifications.text(context, active)
        assertTrue(payload, payload.contains(rate)); assertTrue(payload, payload.contains(notificationRemaining))
    }
    @Test fun knownExactTransferShowsMeasuredRateAndApproximateTransferTimeEnglish() = valid()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun knownExactTransferShowsMeasuredRateAndApproximateTransferTimeArabic() = valid()
    private fun unknown() = render(active.copy(total = Size.Unknown)) {
        noSpeed(); text("25\u00a0MB").assertExists(); text("25%").assertDoesNotExist()
        text(remaining).assertDoesNotExist()
        assertTrue(bars().any { it.config[SemanticsProperties.ProgressBarRangeInfo] == ProgressBarRangeInfo.Indeterminate })
    }
    @Test fun unknownTotalKeepsMeasuredRateWithoutPercentOrInventedEtaEnglish() = unknown()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun unknownTotalKeepsMeasuredRateWithoutPercentOrInventedEtaArabic() = unknown()
    @Test fun estimatedTotalNeverPromotesSuppliedEtaToCountdownOrPercent() = render(active.copy(total = Size.Estimated(100_000_000))) {
        noSpeed(); text("25%").assertDoesNotExist()
        text(remaining).assertDoesNotExist()
        assertTrue(bars().any { it.config[SemanticsProperties.ProgressBarRangeInfo] == ProgressBarRangeInfo.Indeterminate })
    }
    private fun transitions() = render(active) { update ->
        text("25%").assertExists(); text(remaining).assertExists()
        listOf(DownloadPhase.JOINING, DownloadPhase.CONVERTING, DownloadPhase.SAVING, DownloadPhase.FAILED, DownloadPhase.CANCELLED, DownloadPhase.COMPLETED).forEach { phase ->
            update(active.copy(phase = phase))
            noTransferMetrics()
            assertFalse("Non-transfer phase cannot keep a percentage", bars().any { it.config[SemanticsProperties.ProgressBarRangeInfo] != ProgressBarRangeInfo.Indeterminate })
        }
        update(active.copy(bytesPerSecond = null, remainingTransferSeconds = null, stalled = null))
        noSpeed(); text(remaining).assertDoesNotExist()
        text("25%").assertExists(); text(bytes).assertExists()
    }
    @Test fun phaseChangesAndSameIdNewAttemptClearVisibleRateAndEtaEnglish() = transitions()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun phaseChangesAndSameIdNewAttemptClearVisibleRateAndEtaArabic() = transitions()
    @Test fun stalledOrInvalidRateNeverShowsEtaOrFabricatedRate() = render(active) { update ->
        text(remaining).assertExists()
        listOf<Double?>(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE).forEach { bad ->
            update(active.copy(bytesPerSecond = bad))
            noSpeed(); text(remaining).assertDoesNotExist()
            listOf("NaN", "Infinity", "0\u00a0B/s").forEach { text(it).assertDoesNotExist() }
        }
        update(active.copy(stalled = true)); noTransferMetrics()
        update(active.copy(waiting = true)); noTransferMetrics()
    }
    @Test fun impossibleCounterOrNonpositiveRemainingNeverShowsEta() = render(active) { update ->
        text(remaining).assertExists()
        listOf(-1L, 100_000_001L).forEach { bad ->
            update(active.copy(bytes = bad)); text(remaining).assertDoesNotExist(); text("100%").assertDoesNotExist()
        }
        listOf(0L, -1L).forEach { bad ->
            update(active.copy(remainingTransferSeconds = bad)); text(remaining).assertDoesNotExist()
        }
    }
    @Test fun fractionalByteRateDoesNotBecomeZeroBytesPerSecond() = render(active.copy(bytesPerSecond = .5, remainingTransferSeconds = null)) {
        val payload = DownloadNotifications.text(context, active.copy(bytesPerSecond = .5, remainingTransferSeconds = null))
        assertTrue(payload, payload.contains("0.5\u00a0B/s")); assertFalse(payload, payload.contains(" 0\u00a0B/s"))
        noSpeed(); text("0\u00a0B/s").assertDoesNotExist(); text(remaining).assertDoesNotExist()
    }
    private fun measuredBytesOnlyForNonExactTotal() = render(active.copy(total = Size.Estimated(100_000_000))) { update ->
        listOf(Size.Estimated(100_000_000), Size.Unknown).forEach { total ->
            update(active.copy(total = total))
            compose.onNodeWithText(dev.ahmed.savlino.locale.NezzelFormat.isolate("25\u00a0MB"), useUnmergedTree = true).assertExists()
            text("100\u00a0MB").assertDoesNotExist()
            text(context.getString(R.string.unknown_size)).assertDoesNotExist()
            noSpeed(); text(remaining).assertDoesNotExist()
        }
    }
    @Test fun nonExactTotalsShowMeasuredBytesAloneEnglish() = measuredBytesOnlyForNonExactTotal()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun nonExactTotalsShowMeasuredBytesAloneArabic() = measuredBytesOnlyForNonExactTotal()

    private fun truthfulStallPhase() = render(active.copy(stalled = true)) { update ->
        val stalledLabel = if (language == "ar") "في انتظار البيانات…" else "Waiting for data…"
        text(stalledLabel).assertExists()
        compose.onNodeWithText(context.getString(R.string.download_transferring), useUnmergedTree = true).assertDoesNotExist()
        noTransferMetrics()
        update(active.copy(stalled = false))
        text(stalledLabel).assertDoesNotExist()
        // A real fraction replaces the "Downloading" word with the numbers themselves.
        text("25%").assertExists(); text(remaining).assertExists()
        update(active.copy(stalled = true, waiting = true))
        text(context.getString(R.string.download_waiting)).assertExists()
        text(stalledLabel).assertDoesNotExist()
        noTransferMetrics()
        DownloadPhase.entries.filter { it != DownloadPhase.DOWNLOADING }.forEach { phase ->
            update(active.copy(phase = phase, stalled = true))
            text(stalledLabel).assertDoesNotExist()
            noTransferMetrics()
        }
    }
    @Test fun stalledTransferWaitingLabelRecoversWithoutLeakingToOtherPhasesEnglish() = truthfulStallPhase()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun stalledTransferWaitingLabelRecoversWithoutLeakingToOtherPhasesArabic() = truthfulStallPhase()

    private fun truthfulStallStrip() = render(active.copy(stalled = true), strip = true) { update ->
        val stalledLabel = if (language == "ar") "في انتظار البيانات…" else "Waiting for data…"
        text(stalledLabel).assertExists()
        text(context.getString(R.string.download_transferring)).assertDoesNotExist()
        update(active.copy(stalled = false))
        text(stalledLabel).assertDoesNotExist()
        text("25%").assertExists()
        update(active.copy(stalled = true, waiting = true))
        text(context.getString(R.string.download_waiting)).assertExists()
        text(stalledLabel).assertDoesNotExist()
    }
    @Test fun homeStripKeepsFactualStallAndWorkWaitingPrecedenceEnglish() = truthfulStallStrip()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun homeStripKeepsFactualStallAndWorkWaitingPrecedenceArabic() = truthfulStallStrip()

    private fun preparingHasNoSize() = render(active.copy(phase = DownloadPhase.PREPARING,
        bytes = 1, total = Size.Exact(1024))) {
        text(context.getString(R.string.download_preparing)).assertExists()
        text("KB").assertDoesNotExist(); text("MB").assertDoesNotExist()
        assertEquals(context.getString(R.string.download_preparing),
            DownloadNotifications.text(context, active.copy(phase = DownloadPhase.PREPARING, bytes = 1, total = Size.Exact(1024))))
    }
    @Test fun preparingNeverShowsTinyBytesOrConfirmedSizeEnglish() = preparingHasNoSize()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun preparingNeverShowsTinyBytesOrConfirmedSizeArabic() = preparingHasNoSize()

    private fun overrunShowsOnlyRealBytes() = render(active.copy(bytes = 160_900_000, total = Size.Estimated(156_700_000))) { update ->
        listOf(Size.Estimated(156_700_000), Size.Exact(156_700_000)).forEach { total ->
            val progress = active.copy(bytes = 160_900_000, total = total)
            update(progress)
            text("160.9\u00a0MB").assertExists(); text("156.7").assertDoesNotExist()
            text("100%").assertDoesNotExist(); text(remaining).assertDoesNotExist()
            assertEquals(dev.ahmed.savlino.locale.NezzelFormat.isolate("160.9\u00a0MB"),
                DownloadNotifications.bytes(context, progress))
            assertTrue(bars().any { it.config[SemanticsProperties.ProgressBarRangeInfo] == ProgressBarRangeInfo.Indeterminate })
        }
    }
    @Test fun byteOverrunNeverShowsSmallerTotalOrBytePercentEnglish() = overrunShowsOnlyRealBytes()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun byteOverrunNeverShowsSmallerTotalOrBytePercentArabic() = overrunShowsOnlyRealBytes()

    private fun hlsFragments() = render(active.copy(total = Size.Estimated(100_000_000),
        fragmentIndex = 25, fragmentCount = 100)) { update ->
        text("25%").assertExists(); text(remaining).assertDoesNotExist()
        assertTrue(bars().any { it.config[SemanticsProperties.ProgressBarRangeInfo].current == .25f })
        val parts = DownloadNotifications.transferParts(context, active.copy(total = Size.Unknown,
            fragmentIndex = 25, fragmentCount = 100))!!
        assertTrue(parts.percent!!.contains("25%")); assertNull(parts.remaining)
        update(active.copy(total = Size.Unknown))
        text("25%").assertDoesNotExist(); text(remaining).assertDoesNotExist()
        text("25\u00a0MB").assertExists()
        text(context.getString(R.string.download_transferring)).assertExists()
        assertTrue(bars().any { it.config[SemanticsProperties.ProgressBarRangeInfo] == ProgressBarRangeInfo.Indeterminate })
    }
    @Test fun hlsReportedFragmentsGivePercentAndMissingFragmentsStayIndeterminateEnglish() = hlsFragments()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun hlsReportedFragmentsGivePercentAndMissingFragmentsStayIndeterminateArabic() = hlsFragments()

}
