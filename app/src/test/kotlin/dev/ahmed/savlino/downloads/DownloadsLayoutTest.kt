package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.renderThemed
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.jobs.*
import dev.ahmed.savlino.locale.NezzelFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w320dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DownloadsLayoutTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val language get() = context.resources.configuration.locales[0].language
    private val image = SavedMedia(Uri.parse("content://media/external/downloads/1"), "Photo.jpg", "Photo", "image/jpeg", 100, 100, null, null, null)
    private fun nodes(a: ComponentActivity): List<SemanticsNode> {
        compose.waitForIdle()
        return semanticsNodes(a, rounds = 4, idleMillis = 100, allWindows = false)
    }
    private fun action(a: ComponentActivity, label: String): SemanticsNode {
        val matches = nodes(a).filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any {
                label in texts(listOf(it)) || it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { text -> text.contains(label) }
            }
        }
        assertTrue("Missing clickable action: $label", matches.isNotEmpty())
        return matches.last()
    }
    private fun click(node: SemanticsNode) { assertEquals(true, node.config[SemanticsActions.OnClick].action?.invoke()) }
    private fun bounded(node: SemanticsNode, minHeight: Float = 48f) {
        val b = node.boundsInRoot
        val density = context.resources.displayMetrics.density
        // M3 keeps 40dp visuals and reserves the 48dp touch target on the control's layout node.
        val touch = maxOf(b.height, node.layoutInfo.height.toFloat())
        assertTrue("Expected >=48dp action: $b, touch $touch", touch / density >= minHeight - .1f)
        assertTrue("Outside 320dp viewport: $b", b.left >= -.5f && b.right <= context.resources.displayMetrics.widthPixels + .5f)
    }
    private fun render(content: @Composable () -> Unit, check: (ComponentActivity) -> Unit) = renderThemed({ nodes(it) }, content, check)
    @Composable private fun screen(load: LibraryLoadState, retry: () -> Unit = {}, paste: () -> Unit = {}) {
        DownloadsScreen(emptyList(), language, {}, {}, {}, {}, {}, null, {}, paste,
            libraryLoad = load, onLibraryRetry = retry)
    }
    @Test fun initialLoadingDoesNotClaimAbsenceOrOfferInactiveSearch() = render({ screen(LibraryLoadState.Loading()) }) { a ->
        val all = nodes(a)
        assertFalse(texts(all).contains(context.getString(R.string.saved_empty_title)))
        assertFalse(all.any { context.getString(R.string.saved_search) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
        assertTrue(all.any { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == ProgressBarRangeInfo.Indeterminate })
    }
    @Test fun firstErrorOffersRetryWithoutEmptyOnboarding() {
        var retries = 0
        render({ screen(LibraryLoadState.Error(), retry = { retries++ }) }) { a ->
            assertFalse(texts(nodes(a)).contains(context.getString(R.string.saved_empty_title)))
            val retry = action(a, context.getString(R.string.try_again)); bounded(retry); click(retry)
            assertEquals(1, retries)
        }
    }
    @Test fun refreshErrorAndPendingLoadingKeepSavedRowsVisible() {
        var load by mutableStateOf<LibraryLoadState>(LibraryLoadState.Error(listOf(image)))
        render({ screen(load, retry = { load = LibraryLoadState.Loading(listOf(image)) }) }) { a ->
            assertTrue(texts(nodes(a)).contains(NezzelFormat.firstStrong("Photo")))
            click(action(a, context.getString(R.string.try_again)))
            assertTrue(texts(nodes(a)).contains(NezzelFormat.firstStrong("Photo")))
            assertFalse(texts(nodes(a)).contains(context.getString(R.string.saved_empty_title)))
        }
    }
    private fun trueEmpty() {
        var pasted = 0
        render({ screen(LibraryLoadState.Ready(emptyList()), paste = { pasted++ }) }) { a ->
            val all = nodes(a)
            assertTrue(texts(all).contains(context.getString(R.string.saved_empty_title)))
            assertFalse("Empty explanation must be path-free", texts(all).any { it.contains(context.getString(R.string.save_path)) })
            assertFalse("Destination prose must not duplicate empty body", texts(all).any { it == context.getString(R.string.files_location, NezzelFormat.isolate(context.getString(R.string.save_path))) })
            assertFalse(all.any { context.getString(R.string.saved_search) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            val paste = action(a, context.getString(R.string.paste_link)); bounded(paste); click(paste)
            assertEquals(1, pasted)
        }
    }
    @Test fun trueEmptyHasSingleExplanationPrimaryPasteAndNoSearch() = trueEmpty()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun arabicTrueEmptyExplanationIsPathFreeAndPasteWorks() = trueEmpty()
    private fun activeCards() {
        RuntimeEnvironment.setFontScale(2f)
        val jobs = listOf("Family-part-01.mp4", "Family-part-02.mp4").mapIndexed { index, name ->
            DownloadProgress("job$index", name, DownloadPhase.DOWNLOADING, bytes = 25_000_000, total = Size.Exact(100_000_000))
        }
        val cancelled = mutableListOf<String>()
        render({ DownloadsScreen(jobs, language, { cancelled += it }, {}, {}, {}, {}, null, {}, {}) }) { a ->
            val all = nodes(a)
            jobs.forEach { job ->
                val title = all.single { texts(listOf(it)).contains(NezzelFormat.isolate(job.filename)) }
                // Large text drops the kind tile: only the 48dp ring shares the title row (320 - 32 - 32 - 12 - 48 = 196dp).
                assertTrue("Filename must get the row's text width: ${title.boundsInRoot}", title.boundsInRoot.width >= 195f)
                val layouts = mutableListOf<TextLayoutResult>()
                assertEquals(true, title.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts))
                assertTrue("Filename must allow two readable lines", layouts.single().layoutInput.maxLines >= 2)
                val cancel = all.singleOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.contains(job.filename) && label.startsWith(context.getString(R.string.download_cancel_accessibility, "").trim()) } }
                assertNotNull("Cancel must identify ${job.filename}", cancel)
                bounded(cancel!!)
                // The ring around Cancel is the progress; it sits at the row's end, beside the title, never over it.
                val density = context.resources.displayMetrics.density
                val ring = all.filter { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }
                    .singleOrNull { it.boundsInRoot.overlaps(cancel.boundsInRoot) }
                assertNotNull("Progress ring belongs around Cancel", ring)
                val r = ring!!.boundsInRoot
                assertFalse("Ring overlaps the title: $r, ${title.boundsInRoot}", r.overlaps(title.boundsInRoot))
                assertTrue("Ring is the 48dp control: $r", r.width / density in 47.5f..48.5f && r.height / density in 47.5f..48.5f)
                val c = cancel.boundsInRoot
                assertTrue("Cancel sits inside the ring: $c in $r", c.left >= r.left - .5f && c.right <= r.right + .5f &&
                    c.top >= r.top - .5f && c.bottom <= r.bottom + .5f)
            }
            val selected = jobs.first()
            val cancel = all.single { it.config.getOrNull(SemanticsActions.OnClick) != null && it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { label -> label.contains(selected.filename) && label.startsWith(context.getString(R.string.download_cancel_accessibility, "").trim()) } }
            click(cancel); assertEquals(listOf(selected.jobId), cancelled)
        }
    }
    @Test fun englishTwoSimilarActiveFilesHaveBoundedContextualCancelAt200Percent() = activeCards()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun arabicTwoSimilarActiveFilesHaveBoundedContextualCancelAt200Percent() = activeCards()
    private fun recentColumn() {
        RuntimeEnvironment.setFontScale(2f)
        val rows = (1..2).map { image.copy(uri = Uri.parse("content://media/external/downloads/$it"), title = "Photo $it") }
        var all = false
        var opened: SavedMedia? = null
        render({ Column(Modifier.fillMaxWidth().padding(16.dp)) { RecentFiles(rows, language, { opened = it }) { all = true } } }) { a ->
            val first = action(a, NezzelFormat.firstStrong("Photo 1"))
            val second = action(a, NezzelFormat.firstStrong("Photo 2"))
            assertTrue("Recent cards must stack at narrow 200%: ${first.boundsInRoot}, ${second.boundsInRoot}", second.boundsInRoot.top >= first.boundsInRoot.bottom)
            assertTrue(first.boundsInRoot.width >= 280f)
            val seeAll = action(a, context.getString(R.string.see_all)); bounded(seeAll); click(seeAll)
            assertTrue(all)
            click(first); assertEquals(rows.first(), opened)
        }
    }
    @Test fun englishRecentsBecomeOneColumnAndActionsWorkAt200Percent() = recentColumn()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun arabicRecentsBecomeOneColumnAndActionsWorkAt200Percent() = recentColumn()
}
