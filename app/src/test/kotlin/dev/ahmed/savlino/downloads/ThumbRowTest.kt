package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.LocalReducedMotion
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The tile shows the job's kept artwork when there is one, with a badge for audio and failed; else today's kind icon. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w384dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbRowTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val video = DownloadProgress("a", "Holiday clip.mp4", DownloadPhase.DOWNLOADING, bytes = 25_000_000, total = Size.Exact(100_000_000),
        bytesPerSecond = 2_500_000.0, remainingTransferSeconds = 30, stalled = false)
    private val audio = video.copy(jobId = "b", filename = "Evening song.m4a", mimeType = "audio/mp4")
    private val failed = DownloadProgress("c", "City at night.mp4", DownloadPhase.FAILED, error = DownloadError.Network, retryable = true)
    private val asked = mutableListOf<String>()

    private fun render(job: DownloadProgress, art: Bitmap?, check: () -> Unit) = render(job, { art }, check)

    private fun render(job: DownloadProgress, art: () -> Bitmap?, check: () -> Unit) {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            c.get().setContent { NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(LocalReducedMotion provides true, LocalJobArtwork provides { id -> asked += id; art() }) {
                    JobRow(job, {}, {}, {})
                }
            } }
            compose.waitForIdle(); check()
        } finally { c.pause().stop().destroy() }
    }
    private fun tagged(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
    private val picture = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)

    @Test fun noArtworkKeepsTheKindIcon() = render(video, null) {
        assertEquals("a", asked.first())
        assertTrue(tagged("job-art").isEmpty())
    }

    @Test fun artworkByJobIdAndNoBadgeOnVideo() = render(video, picture) {
        assertEquals(listOf("a"), asked)
        assertEquals(1, tagged("job-art").size)
        assertTrue(tagged("job-art-badge-audio").isEmpty() && tagged("job-art-badge-failed").isEmpty())
    }

    @Test fun audioAndFailedKeepTheirMeaningAsABadge() {
        render(audio, picture) { assertEquals(1, tagged("job-art-badge-audio").size) }
        render(failed, picture) { assertEquals(1, tagged("job-art-badge-failed").size); assertEquals(1, tagged("job-art").size) }
        // Without artwork the failed tile is still the error icon on its own, no badge.
        render(failed, null) { assertTrue(tagged("job-art").isEmpty() && tagged("job-art-badge-failed").isEmpty()) }
    }

    @Test fun artworkSavedAfterTheRowShowsStillAppears() {
        var saved: Bitmap? = null
        render(video, { saved }) {
            assertTrue(tagged("job-art").isEmpty())
            saved = picture
            // The retry looks again a second after the first look; effects may run on the test clock, so move it past.
            compose.mainClock.advanceTimeBy(1_100)
            compose.waitUntil(5_000) { tagged("job-art").isNotEmpty() }
        }
    }

    @Test fun aFinishedJobLooksOnce() = render(failed, null) {
        // Retry waits live on the test clock (0 ms, then 1 s). A finished row stops after the first look.
        compose.mainClock.advanceTimeBy(1_500); compose.waitForIdle()
        assertEquals(listOf("c"), asked)
    }

    @Test fun artworkIsDecorative() = render(audio, picture) {
        (tagged("job-art") + tagged("job-art-badge-audio")).forEach {
            assertFalse(it.config.contains(SemanticsProperties.ContentDescription))
        }
        assertTrue(compose.onAllNodes(hasContentDescription("", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .none { node -> node.config[SemanticsProperties.ContentDescription].any { it.isBlank() } })
    }

    @Test fun largeTextDropsTheTileAndNeverLoads() {
        RuntimeEnvironment.setFontScale(2f)
        try { render(video, picture) { assertTrue(tagged("job-art").isEmpty()); assertTrue(asked.isEmpty()) } }
        finally { RuntimeEnvironment.setFontScale(1f) }
    }
}
