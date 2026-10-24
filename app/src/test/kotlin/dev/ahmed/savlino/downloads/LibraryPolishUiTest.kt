package dev.ahmed.savlino.downloads

import android.content.pm.ProviderInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import dev.ahmed.savlino.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.locale.NezzelFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w384dp-h1200dp-mdpi")
class LibraryPolishUiTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private fun nodes(a: ComponentActivity) = semanticsNodes(a)
    private fun action(a: ComponentActivity, label: String) = nodes(a).lastOrNull { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any {
            label in texts(listOf(it)) || label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
    }
    private fun click(a: ComponentActivity, label: String) {
        val target = action(a, label) ?: error("Missing action: $label")
        compose.runOnIdle { assertEquals(true, target.config[SemanticsActions.OnClick].action?.invoke()) }
        compose.waitForIdle()
    }
    /** Robolectric focuses the first field of a dialog window and its cursor blink keeps Compose's idle wait spinning, so dialog steps settle the looper directly. */
    private fun tap(a: ComponentActivity, label: String) {
        val target = action(a, label) ?: error("Missing action: $label")
        assertEquals(true, target.config[SemanticsActions.OnClick].action?.invoke())
        settle(a)
    }
    private fun settle(a: ComponentActivity) = repeat(3) { compose.mainClock.advanceTimeBy(50); semanticsNodes(a) }
    private fun until(a: ComponentActivity, check: (List<androidx.compose.ui.semantics.SemanticsNode>) -> Boolean) {
        repeat(100) {
            if (check(semanticsNodes(a))) return
            compose.mainClock.advanceTimeBy(50) // Drives the sheet's hide animation without an idle wait.
            Thread.sleep(20)
        }
        fail("Condition not met")
    }
    private fun field(a: ComponentActivity) = semanticsNodes(a).single { it.config.getOrNull(SemanticsActions.SetText) != null }
    private fun fixture(check: (SavedMediaRepository, SavedMediaRenameTest.RenameProvider) -> Unit) {
        val provider = SavedMediaRenameTest.RenameProvider().apply { attachInfo(app, ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", provider)
        try { check(SavedMediaRepository(app.contentResolver, app.packageName), provider) } finally { provider.db.close() }
    }
    private fun screen(rows: List<SavedMedia>, repo: SavedMediaRepository, check: (ComponentActivity) -> Unit) =
        renderThemed({ compose.waitForIdle(); nodes(it) }, {
            DownloadsScreen(emptyList(), app.resources.configuration.locales[0].language, {}, {}, {}, {}, {}, null, {}, {},
                libraryLoad = LibraryLoadState.Ready(rows), renameRepository = repo)
        }, check)

    @Test fun singleTypeHidesChipsAndSortMenuHasOneSelectedChoiceAndPersists() = fixture { repo, _ ->
        AppPreferences(app).savedSort = SavedSort.NEWEST
        screen(repo.query(), repo) { a ->
            assertNull(action(a, app.getString(R.string.filter_all)))
            click(a, app.getString(R.string.saved_sort_accessibility, app.getString(R.string.saved_sort_newest)))
            val largest = action(a, app.getString(R.string.saved_sort_largest))!!
            assertFalse(largest.config[SemanticsProperties.Selected])
            assertTrue(action(a, app.getString(R.string.saved_sort_newest))!!.config[SemanticsProperties.Selected])
            click(a, app.getString(R.string.saved_sort_largest))
            assertEquals(SavedSort.LARGEST, AppPreferences(app).savedSort)
        }
        screen(repo.query(), repo) { a ->
            assertNotNull(action(a, app.getString(R.string.saved_sort_accessibility, app.getString(R.string.saved_sort_largest))))
        }
    }
    @Test fun filterResetsWhenScreenIsLaunchedAgain() = fixture { repo, provider ->
        provider.insertRow(2, "audio/mpeg", 200)
        screen(repo.query(), repo) { a ->
            click(a, app.getString(R.string.audio_group))
            assertFalse(texts(nodes(a)).contains(NezzelFormat.firstStrong("Saved 1")))
        }
        screen(repo.query(), repo) { a ->
            assertTrue(texts(nodes(a)).contains(NezzelFormat.firstStrong("Saved 1")))
            assertTrue(action(a, app.getString(R.string.filter_all))!!.config[SemanticsProperties.Selected])
        }
    }
    @Test fun ownedFileOffersRenameWithSelectedStemAndImmutableExtensionAndInvalidInputCannotWrite() = fixture { repo, provider ->
        val rows = repo.query()
        screen(rows, repo) { a ->
            click(a, app.getString(R.string.saved_more, rows.single().displayName))
            compose.waitUntil(5000) { action(a, app.getString(R.string.saved_rename)) != null }
            tap(a, app.getString(R.string.saved_rename))
            until(a) { nodes -> nodes.any { it.config.getOrNull(SemanticsActions.SetText) != null } }
            val field = field(a)
            assertEquals("Saved 1", field.config[SemanticsProperties.EditableText].text)
            assertEquals(TextRange(0, 7), field.config[SemanticsProperties.TextSelectionRange])
            assertTrue(texts(semanticsNodes(a)).contains(app.getString(R.string.saved_rename_extension, NezzelFormat.isolate(".mp4"))))
            field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("  "))
            settle(a)
            assertNotNull(action(a, app.getString(R.string.saved_rename))!!.config.getOrNull(SemanticsProperties.Disabled))
            assertEquals(0, provider.updates)
            field(a).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Holiday"))
            settle(a)
            tap(a, app.getString(R.string.saved_rename))
            until(a) { provider.name(1) == "Holiday.mp4" }
            until(a) { texts(it).contains(app.getString(R.string.saved_renamed, NezzelFormat.isolate("Holiday.mp4"))) }
        }
        screen(repo.query(), repo) { a ->
            assertTrue(texts(nodes(a)).contains(NezzelFormat.firstStrong("Holiday")))
            assertFalse(texts(nodes(a)).contains(NezzelFormat.firstStrong("Saved 1")))
        }
    }
    @Test fun securityFailureClosesDialogAndRemovesRenameFromReopenedSheet() = fixture { repo, provider ->
        val rows = repo.query()
        screen(rows, repo) { a ->
            val more = app.getString(R.string.saved_more, rows.single().displayName)
            click(a, more)
            compose.waitUntil(5000) { action(a, app.getString(R.string.saved_rename)) != null }
            tap(a, app.getString(R.string.saved_rename))
            until(a) { nodes -> nodes.any { it.config.getOrNull(SemanticsActions.SetText) != null } }
            provider.denyUpdate = true
            tap(a, app.getString(R.string.saved_rename))
            until(a) { texts(it).contains(app.getString(R.string.saved_rename_failed)) }
            assertFalse(semanticsNodes(a).any { it.config.getOrNull(SemanticsActions.SetText) != null })
            provider.denyUpdate = false
            click(a, more)
            compose.waitForIdle()
            assertNull(action(a, app.getString(R.string.saved_rename)))
            assertEquals("Saved 1.mp4", provider.name(1))
            assertEquals(1, provider.updates)
        }
    }

    @Test fun foreignRowDoesNotOfferRenameInSavedSheet() = fixture { repo, provider ->
        val rows = repo.query()
        provider.db.execSQL("UPDATE media SET owner_package_name = 'other.app'")
        screen(rows, repo) { a ->
            click(a, app.getString(R.string.saved_more, rows.single().displayName))
            compose.waitForIdle()
            assertNull(action(a, app.getString(R.string.saved_rename)))
            assertNotNull(action(a, app.getString(R.string.share_file)))
        }
        assertEquals(0, provider.updates)
    }
    @Test fun groupedPostMemberDoesNotOfferRename() = fixture { repo, provider ->
        provider.insertRow(2, "video/mp4", 101)
        provider.db.execSQL("UPDATE media SET _display_name = 'Post_01.mp4' WHERE _id = 1")
        provider.db.execSQL("UPDATE media SET _display_name = 'Post_02.mp4' WHERE _id = 2")
        val rows = repo.query()
        assertTrue(repo.canRename(rows.first().uri))
        screen(rows, repo) { a ->
            click(a, app.getString(R.string.saved_more, "Post_01"))
            assertNotNull(action(a, app.getString(R.string.share_file)))
            assertNull(action(a, app.getString(R.string.saved_rename)))
        }
        assertEquals(0, provider.updates)
    }
}
