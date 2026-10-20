package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.renderThemed
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w320dp-h1600dp-mdpi")
class SavedSelectionTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val rows = listOf(
        SavedMedia(Uri.parse("content://media/external/downloads/1"), "Family.mp4", "Family", "video/mp4", 100, 100, null, null, null),
        SavedMedia(Uri.parse("content://media/external/downloads/2"), "Song.m4a", "Song", "audio/mp4", 100, 100, null, null, null),
    )
    private fun nodes(a: ComponentActivity): List<SemanticsNode> {
        compose.waitForIdle()
        return semanticsNodes(a, rounds = 4, idleMillis = 100)
    }
    private fun row(a: ComponentActivity, title: String) = nodes(a).single { node ->
        node.config.getOrNull(SemanticsActions.OnLongClick) != null && texts(descendants(node)).any { it.contains(title) }
    }
    private fun click(a: ComponentActivity, label: String) {
        val node = nodes(a).last { node -> node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any {
            texts(listOf(it)).any { text -> text.contains(label) } || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        } }
        compose.runOnIdle { assertEquals(true, node.config[SemanticsActions.OnClick].action?.invoke()) }
    }
    private fun longPress(a: ComponentActivity, title: String) {
        val node = row(a, title)
        compose.runOnIdle { assertEquals(true, node.config[SemanticsActions.OnLongClick].action?.invoke()) }
    }
    private fun tapRow(a: ComponentActivity, title: String) {
        val node = row(a, title)
        compose.runOnIdle { node.config[SemanticsActions.OnClick].action!!.invoke() }
    }
    private fun selectedCount(a: ComponentActivity, title: String) {
        val count = nodes(a).single { title in texts(listOf(it)) }
        assertEquals(LiveRegionMode.Polite, count.config.getOrNull(SemanticsProperties.LiveRegion))
    }
    private fun render(check: (ComponentActivity, () -> Int, () -> List<SavedMedia>) -> Unit) {
        var opened = 0
        var shared = emptyList<SavedMedia>()
        renderThemed({ nodes(it) }, {
            DownloadsScreen(emptyList(), context.resources.configuration.locales[0].language, {}, {}, { opened++ }, {}, {}, null, {}, {},
                libraryLoad = LibraryLoadState.Ready(rows), onShareSelected = { shared = it })
        }) { check(it, { opened }, { shared }) }
    }
    @Test fun longPressSelectsTapTogglesWithoutOpenAndShareUsesSelection() = render { a, opened, shared ->
        longPress(a, "Family")
        selectedCount(a, "1 selected")
        assertEquals(Role.Checkbox, row(a, "Family").config.getOrNull(SemanticsProperties.Role))
        assertEquals(ToggleableState.On, row(a, "Family").config.getOrNull(SemanticsProperties.ToggleableState))
        assertNull(row(a, "Family").config[SemanticsActions.OnClick].label)
        assertNull(row(a, "Family").config[SemanticsActions.OnLongClick].label)
        tapRow(a, "Song")
        selectedCount(a, "2 selected")
        assertEquals(0, opened())
        click(a, context.getString(R.string.share_file))
        assertEquals(rows, shared())
        tapRow(a, "Family")
        selectedCount(a, "1 selected")
        assertEquals(ToggleableState.Off, row(a, "Family").config.getOrNull(SemanticsProperties.ToggleableState))
    }
    @Test fun selectAllCloseAndBackRestoreOpenBehaviour() = render { a, opened, _ ->
        longPress(a, "Family")
        click(a, context.getString(R.string.saved_select_all))
        selectedCount(a, "2 selected")
        compose.runOnIdle { a.onBackPressedDispatcher.onBackPressed() }
        assertFalse(texts(nodes(a)).contains("2 selected"))
        click(a, "Family"); assertEquals(1, opened())
        longPress(a, "Song")
        click(a, context.getString(R.string.saved_selection_close))
        click(a, "Song"); assertEquals(2, opened())
    }
    @Test fun filterChangeClearsSelectionAndSelectAllOnlySelectsVisibleFiles() = render { a, _, shared ->
        longPress(a, "Family")
        click(a, context.getString(R.string.audio_group))
        assertFalse(texts(nodes(a)).any { it.endsWith(" selected") })
        longPress(a, "Song")
        click(a, context.getString(R.string.saved_select_all))
        click(a, context.getString(R.string.share_file))
        assertEquals(listOf(rows[1]), shared())
    }
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun arabicSelectionUsesPluralCategoriesAndLatinDigits() = render { a, _, _ ->
        longPress(a, "Family")
        selectedCount(a, "ملف واحد محدد")
        click(a, context.getString(R.string.saved_select_all))
        selectedCount(a, "ملفان محددان")
        listOf(0, 3, 11, 100).zip(listOf("لا ملفات محددة", "3 ملفات محددة", "11 ملفًا محددًا", "100 ملف محدد")).forEach { (count, expected) ->
            assertEquals(expected, context.resources.getQuantityString(R.plurals.saved_selected, count, count.toString()))
        }
    }
    @Test fun searchEditClearsSelection() = render { a, _, _ ->
        click(a, context.getString(R.string.saved_search))
        longPress(a, "Family")
        val field = nodes(a).single { it.config.getOrNull(SemanticsActions.SetText) != null }
        compose.runOnIdle { field.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("Song")) }
        assertFalse(texts(nodes(a)).any { it.endsWith(" selected") })
    }
    private fun largeSelectionActions() {
        RuntimeEnvironment.setFontScale(2f)
        render { a, _, _ ->
            longPress(a, "Family")
            val labels = listOf(R.string.saved_selection_close, R.string.saved_select_all, R.string.share_file, R.string.saved_delete)
            labels.forEach { id ->
                val label = context.getString(id)
                val action = nodes(a).single { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    descendants(node).any { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() } }
                val bounds = action.boundsInRoot
                val density = context.resources.displayMetrics.density
                assertTrue("$label: $bounds", bounds.width / density >= 47.5f && bounds.height / density >= 47.5f)
                assertTrue("$label outside viewport: $bounds", bounds.left >= -.5f && bounds.right <= context.resources.displayMetrics.widthPixels + .5f)
            }
        }
    }
    @Test fun englishSelectionActionsRemainReachableAtLargeText() = largeSelectionActions()
    @Test @Config(qualifiers = "ar-w320dp-h1600dp-mdpi") fun arabicSelectionActionsRemainReachableAtLargeText() = largeSelectionActions()
    @Test @Config(sdk = [33]) fun modernSelectionDeleteUsesSystemConfirmationCallbackDirectly() {
        var deleted = emptyList<SavedMedia>()
        renderThemed({ nodes(it) }, {
            DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(rows),
                onDeleteSelected = { items, done -> deleted = items; done(true) })
        }) { a ->
            longPress(a, "Family"); click(a, context.getString(R.string.saved_select_all))
            click(a, context.getString(R.string.saved_delete))
            assertEquals(rows, deleted)
            assertFalse(texts(nodes(a)).contains("2 selected"))
        }
    }
    @Test @Config(sdk = [33]) fun cancelledSystemDeleteKeepsSelectionAndReenablesActions() {
        var completed: ((Boolean) -> Unit)? = null
        var shared = emptyList<SavedMedia>()
        renderThemed({ nodes(it) }, {
            DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(rows),
                onShareSelected = { shared = it }, onDeleteSelected = { _, done -> completed = done })
        }) { a ->
            longPress(a, "Family")
            click(a, context.getString(R.string.saved_select_all))
            click(a, context.getString(R.string.saved_delete))
            compose.runOnIdle { completed!!.invoke(false) }
            selectedCount(a, "2 selected")
            click(a, context.getString(R.string.share_file))
            assertEquals(rows, shared)
        }
    }
    @Test fun deleteConfirmationPassesSnapshotAndClearsOnlyAfterCompletion() {
        var deleted = emptyList<SavedMedia>()
        var completed: ((Boolean) -> Unit)? = null
        renderThemed({ nodes(it) }, {
            DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(rows),
                onDeleteSelected = { items, done -> deleted = items; completed = done })
        }) { a ->
            longPress(a, "Family"); click(a, context.getString(R.string.saved_select_all))
            click(a, context.getString(R.string.saved_delete))
            assertTrue(deleted.isEmpty())
            click(a, context.getString(R.string.saved_delete))
            assertEquals(rows, deleted)
            selectedCount(a, "2 selected")
            compose.runOnIdle { completed!!.invoke(true) }
            assertFalse(texts(nodes(a)).contains("2 selected"))
        }
    }
}
