package dev.ahmed.savlino

import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.downloads.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.ui.SwitchSetting
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.OptionsSheetContent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Width-driven layouts and single targets from the UI review round (findings #2, #5, #7, #9). */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ResponsiveRowsTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val language get() = context.resources.configuration.locales[0].language
    private val density get() = context.resources.displayMetrics.density
    private fun nodes(a: ComponentActivity): List<SemanticsNode> {
        compose.waitForIdle()
        return semanticsNodes(a, rounds = 4, idleMillis = 100)
    }
    private fun render(content: @Composable () -> Unit, check: (ComponentActivity) -> Unit) = renderThemed({ nodes(it) }, content, check)

    // #7: the running job leads Home even when a newer one is queued behind it.
    private fun job(id: String, phase: DownloadPhase, waiting: Boolean = false) =
        DownloadProgress(id, "$id.mp4", phase, bytes = 10_000_000, total = Size.Exact(100_000_000), waiting = waiting)

    @Test fun runningJobsLeadInStartOrderThenTheQueueInRunOrderThenFailuresNewestFirst() {
        val running = job("running", DownloadPhase.DOWNLOADING)
        val joining = job("joining", DownloadPhase.JOINING)
        val preparing = job("preparing", DownloadPhase.PREPARING)
        val queued = job("queued", DownloadPhase.PREPARING, waiting = true)
        val waiting = job("waiting", DownloadPhase.DOWNLOADING, waiting = true)
        val paused = job("paused", DownloadPhase.DOWNLOADING).copy(paused = true)
        val failed = job("failed", DownloadPhase.FAILED)
        val failedLater = job("failed-later", DownloadPhase.FAILED)
        // Submission order, oldest first, as the journal reports it.
        val jobs = listOf(running, failed, queued, joining, paused, waiting, preparing, failedLater)
        // Without a start record running jobs keep submission order: oldest on top.
        assertEquals(listOf(running, joining, preparing, queued, paused, waiting, failedLater, failed), visibleJobs(jobs))
        // A job that started later than a newer one (resumed, retried) sits below it: rows never jump.
        assertEquals(listOf(preparing, running, joining, queued, paused, waiting, failedLater, failed),
            visibleJobs(jobs, listOf("preparing", "running", "joining")))
    }

    @Test fun startOrderKeepsTheFirstStartedJobOnTopWhenAnOlderOneResumes() {
        var jobs by mutableStateOf(listOf(job("older", DownloadPhase.DOWNLOADING).copy(paused = true), job("newer", DownloadPhase.DOWNLOADING)))
        var order = emptyList<String>()
        render({ order = visibleJobs(jobs, rememberStartOrder(jobs)).map { it.jobId } }) { a ->
            assertEquals(listOf("newer", "older"), order)
            jobs = jobs.map { it.copy(paused = false) }
            nodes(a)
            assertEquals(listOf("newer", "older"), order)
        }
    }

    @Test fun slotWaitingJobDoesNotLookRunning() {
        val queued = job("queued", DownloadPhase.PREPARING, waiting = true)
        render({ DownloadStrip(listOf(queued), language) {} }) { a ->
            val row = nodes(a).filter { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }
            assertTrue("A queued row claims no progress, not even a spinner: " + row, row.isEmpty())
            assertTrue(texts(nodes(a)).toString(), texts(nodes(a)).any { context.getString(R.string.download_waiting) in it })
        }
    }

    @Test fun homeStripShowsEveryRunningJobAndCountsTheQueueBeyond() {
        val first = job("first", DownloadPhase.DOWNLOADING)
        val second = job("second", DownloadPhase.DOWNLOADING)
        val queued = job("queued", DownloadPhase.PREPARING, waiting = true)
        val failed = job("failed", DownloadPhase.FAILED)
        render({ DownloadStrip(listOf(first, failed, second, queued), language) {} }) { a ->
            val shown = texts(nodes(a))
            listOf(first, second).forEach { assertTrue(shown.toString(), shown.contains(NezzelFormat.isolate(it.filename))) }
            listOf(queued, failed).forEach { assertFalse(shown.toString(), shown.contains(NezzelFormat.isolate(it.filename))) }
            val firstTop = nodes(a).first { NezzelFormat.isolate(first.filename) in texts(listOf(it)) }.boundsInRoot.top
            val secondTop = nodes(a).first { NezzelFormat.isolate(second.filename) in texts(listOf(it)) }.boundsInRoot.top
            assertTrue("Oldest started on top", firstTop < secondTop)
            assertTrue(shown.contains(context.resources.getQuantityString(R.plurals.downloads_running, 3, NezzelFormat.count(3, language))))
        }
        // Two running and nothing beyond: two rows, no count.
        render({ DownloadStrip(listOf(first, second), language) {} }) { a ->
            val shown = texts(nodes(a))
            listOf(first, second).forEach { assertTrue(shown.toString(), shown.contains(NezzelFormat.isolate(it.filename))) }
            assertFalse(shown.toString(), shown.any { "running" in it })
        }
    }

    @Test fun homeStripShowsTheTransferringJobNotTheNewestQueuedOneAndKeepsTheCount() {
        val running = job("running", DownloadPhase.DOWNLOADING)
        val queued = job("queued", DownloadPhase.PREPARING, waiting = true)
        var opened = 0
        render({ DownloadStrip(listOf(running, queued), language) { opened++ } }) { a ->
            val shown = texts(nodes(a))
            assertTrue(shown.toString(), shown.contains(NezzelFormat.isolate(running.filename)))
            assertFalse(shown.toString(), shown.contains(NezzelFormat.isolate(queued.filename)))
            val count = context.resources.getQuantityString(R.plurals.downloads_running, 2, NezzelFormat.count(2, language))
            val action = nodes(a).last { it.config.getOrNull(SemanticsActions.OnClick) != null && count in texts(descendants(it)) }
            assertEquals(true, action.config[SemanticsActions.OnClick].action?.invoke())
            assertEquals(1, opened)
        }
    }

    // #9: the whole settings row is one Switch target; the switch inside only draws.
    @Test fun switchSettingRowIsOneToggleTarget() {
        var checked by mutableStateOf(false)
        val changes = mutableListOf<Boolean>()
        render({ SwitchSetting("Title", "Help text", checked) { changes += it; checked = it } }) { a ->
            val targets = nodes(a).filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(targets.toString(), 1, targets.size)
            val row = targets.single()
            assertEquals(Role.Switch, row.config.getOrNull(SemanticsProperties.Role))
            assertEquals(ToggleableState.Off, row.config.getOrNull(SemanticsProperties.ToggleableState))
            assertTrue(texts(descendants(row)).containsAll(listOf("Title", "Help text")))
            assertTrue(row.layoutInfo.height / density >= 71.9f)
            assertEquals(true, row.config[SemanticsActions.OnClick].action?.invoke())
            assertEquals(listOf(true), changes)
            val after = nodes(a).single { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(ToggleableState.On, after.config.getOrNull(SemanticsProperties.ToggleableState))
        }
    }

    // #5: saved rows give way by available text width: 96dp thumbnail, then 72dp, then stacked above the text.
    private val video = SavedMedia(Uri.parse("content://media/external/downloads/7"), "Trip.mp4", "Trip", "video/mp4",
        68_000_000, 0, 720, null, 225_000, width = 1280)
    private fun metaWidth(): Float {
        var width = 0f
        render({ DownloadsScreen(emptyList(), language, {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(listOf(video))) }) { a ->
            val meta = nodes(a).filter { node -> texts(listOf(node)).any { "720p" in it && "MB" in it && "MP4" !in it } }
            assertTrue("Missing meta line", meta.isNotEmpty())
            width = meta.maxOf { it.boundsInRoot.width } / density
        }
        return width
    }

    @Test fun savedRowKeepsThumbnailBesideTextAtDefaultSize() {
        val width = metaWidth()
        // 360 - 32 gutter - 96 thumbnail - 24 gaps - 48 menu = 160dp of text.
        assertTrue("Expected side-by-side text column: $width", width in 150f..170f)
    }

    @Test fun savedRowShrinksTheThumbnailBeforeTheTextAt130Percent() {
        RuntimeEnvironment.setFontScale(1.3f)
        val width = metaWidth()
        // 72dp thumbnail: 360 - 32 - 72 - 24 - 48 = 184dp of text.
        assertTrue("Expected the small-thumbnail text column: $width", width in 180f..190f)
    }

    // Below the large-text threshold, but too narrow even with the small thumbnail: width alone stacks the row.
    @Test @Config(qualifiers = "en-w320dp-h1600dp-mdpi") fun savedRowStacksOnANarrowScreenAt115Percent() {
        RuntimeEnvironment.setFontScale(1.15f)
        val width = metaWidth()
        assertTrue("Expected the full row width for text: $width", width >= 285f)
    }

    // #2: "Last used" never squeezes the quality title; it wraps under it when both don't fit.
    private val url = "https://youtube.com/watch?v=one"
    private fun lastUsed() {
        RuntimeEnvironment.setFontScale(1.3f)
        val formats = listOf(Format("720", "mp4", true, true, height = 720, size = Size.Exact(68_000_000)),
            Format("1080", "mp4", true, true, height = 1080, size = Size.Exact(140_000_000)))
        val inspection = Inspection(LinkRevision(LinkInput.parse(url)!!, 1), "fixture-v1", formats,
            listOf(PresetChoice(Preset.BEST, "1080"), PresetChoice(Preset.BALANCED, "720")))
        val vm = LinkViewModel(SavedStateHandle(), engine = FakeEngine(InspectResult.Success(inspection)),
            currentEngineVersion = { "fixture-v1" }, initialPreset = Preset.BALANCED).also {
            it.edit(url); it.checkLink(); shadowOf(Looper.getMainLooper()).idle()
        }
        try {
            render({ Column(Modifier.fillMaxWidth()) { OptionsSheetContent(vm, language) {} } }) { a ->
                val all = nodes(a)
                val badge = all.single { it.config.getOrNull(SemanticsProperties.TestTag) == "last_used_badge" }
                val row = all.single { r -> r.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton && descendants(r).any { it.id == badge.id } }
                val title = descendants(row).first { it.config.contains(SemanticsActions.GetTextLayoutResult) && descendants(badge).none { b -> b.id == it.id } }
                val layouts = mutableListOf<TextLayoutResult>()
                assertEquals(true, title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts))
                assertEquals("Quality title must stay on one line: ${texts(listOf(title))}", 1, layouts.single().lineCount)
                assertFalse("Badge overlaps title: ${badge.boundsInRoot} ${title.boundsInRoot}", badge.boundsInRoot.overlaps(title.boundsInRoot))
                assertTrue("Badge leaves the row: ${badge.boundsInRoot} ${row.boundsInRoot}",
                    badge.boundsInRoot.left >= row.boundsInRoot.left - .5f && badge.boundsInRoot.right <= row.boundsInRoot.right + .5f)
            }
        } finally { vm.dismissOptions() }
    }
    @Test fun lastUsedBadgeNeverSqueezesTheTitleInEnglish() = lastUsed()
    @Test @Config(qualifiers = "ar-w360dp-h1600dp-mdpi") fun lastUsedBadgeNeverSqueezesTheTitleInArabic() = lastUsed()
}
