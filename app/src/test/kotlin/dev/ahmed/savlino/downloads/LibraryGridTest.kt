package dev.ahmed.savlino.downloads

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import dev.ahmed.savlino.viewer.LocalMediaPictures
import dev.ahmed.savlino.viewer.MediaPictures
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context
import org.robolectric.RuntimeEnvironment
import java.util.Locale
import android.content.pm.ProviderInfo
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import dev.ahmed.savlino.*
import dev.ahmed.savlino.locale.NezzelFormat
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w384dp-h832dp")
class LibraryGridTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
    private val activity get() = controller.get()
    private fun s(id: Int) = activity.getString(id)
    @org.junit.Before fun bindAudio() { dev.ahmed.savlino.playback.registerAudioServiceBinding() }
    @After fun close() { controller.pause().stop().destroy() }

    private fun media(n: Int, name: String, mime: String) = SavedMedia(Uri.parse("content://media/external/downloads/$n"), name,
        name.substringBeforeLast('.'), mime, 1000L * n, 1000L - n, null, null, if (mime.startsWith("video")) 21_000L else null)
    private val photos = (1..4).map { media(it, "Photo $it.jpg", "image/jpeg") }
    private val clip = media(5, "Clip.mp4", "video/mp4")
    private val song = media(6, "Song.m4a", "audio/mp4")

    @Test fun gridKeepsOrderInRowsOfThreeWithAudioAsTilesToo() {
        val lines = gridLines(listOf(photos[0], photos[1], song, photos[2], photos[3], clip, photos[0]))
        assertEquals(listOf(3, 3, 1), lines.map { it.items.size })
        assertEquals(listOf(photos[0], photos[1], song), lines[0].items)
        assertEquals(listOf(photos[0]), lines[2].items)
    }

    @Test fun viewIsRememberedPerFilterWithGridForImagesAndStatuses() {
        val prefs = AppPreferences(activity)
        assertTrue(prefs.libraryGrid(MediaFilter.IMAGE.name))
        assertTrue(prefs.libraryGrid(MediaFilter.STATUSES.name))
        assertFalse(prefs.libraryGrid(MediaFilter.ALL.name))
        assertFalse(prefs.libraryGrid(MediaFilter.VIDEO.name))
        LibraryView(prefs, MediaFilter.VIDEO).toggle()
        LibraryView(prefs, MediaFilter.IMAGE).toggle()
        assertTrue(AppPreferences(activity).libraryGrid(MediaFilter.VIDEO.name))
        assertFalse(AppPreferences(activity).libraryGrid(MediaFilter.IMAGE.name))
        assertFalse(AppPreferences(activity).libraryGrid(MediaFilter.ALL.name))
        assertTrue(LibraryView(prefs, MediaFilter.VIDEO).grid)
    }

    private var opened = mutableListOf<SavedMedia>()
    private fun show(items: List<SavedMedia>) {
        activity.setContent {
            NezzelTheme(ThemePref.Light, false) {
                CompositionLocalProvider(LocalMediaPictures provides MediaPictures { _, _, _ -> Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888) }) {
                    DownloadsScreen(emptyList(), "en", {}, {}, { opened += it }, {}, {}, null, {}, {}, libraryLoad = LibraryLoadState.Ready(items))
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun toggleSwitchesAllToGridAndTapOpensTheViewerOverTheFilter() {
        show(photos + clip + song)
        compose.onNodeWithContentDescription(s(R.string.library_show_grid)).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(s(R.string.library_show_list)).assertExists()
        assertTrue(AppPreferences(activity).libraryGrid(MediaFilter.ALL.name))
        compose.onNode(hasContentDescription("Photo 2", substring = true) and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_image_n, "2", "6")).assertIsDisplayed()
        assertTrue(opened.isEmpty())
    }

    @Test fun savedFilesOpenWithDurableResume() {
        val pages = viewerItems(listOf(photos[0], clip, song))
        assertEquals(3, pages.size)
        assertTrue(pages.all { it.resumePolicy == dev.ahmed.savlino.playback.ResumePolicy.SAVED })
        assertEquals(dev.ahmed.savlino.playback.ResumePolicy.SESSION_ONLY, dev.ahmed.savlino.viewer.ViewerItem(clip.uri, true, "video/mp4").resumePolicy)
    }
    @Test fun audioPlaysInTheViewerNotThePhonePlayer() {
        show(listOf(photos[0], song))
        compose.onAllNodes(hasText("Song", substring = true) and hasClickAction()).onFirst().performClick()
        compose.waitForIdle()
        assertTrue(opened.isEmpty())
        compose.onNodeWithContentDescription(activity.getString(R.string.viewer_audio_n, "2", "2")).assertIsDisplayed()
        // The music screen shows Play, or its failure card when the JVM can't decode the fixture: either way, inside the viewer.
        assertTrue(compose.onAllNodesWithContentDescription(s(R.string.viewer_play)).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithText(s(R.string.viewer_retry)).fetchSemanticsNodes().isNotEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LibraryPolishTest {
    private fun file(id: Int, name: String, mime: String = "video/mp4", time: Long = id.toLong(), size: Long? = 10) =
        SavedMedia(Uri.parse("content://media/external/downloads/$id"), name, name.substringBeforeLast('.'), mime,
            size, time, null, null, null, relativePath = "Download/Nezzel/", volumeName = "external_primary")
    private val mixed = listOf(file(1, "أَحْمَد_01.jpg", "image/jpeg", 100, 40),
        file(2, "أَحْمَد_02.mp4", time = 105, size = 50), file(3, "Song.mp3", "audio/mpeg", 102, 70))
    private fun ids(rows: List<SavedMedia>) = rows.map { it.uri.lastPathSegment }

    @Test fun typeAndArabicSearchKeepTheWholeMixedPostWithOriginalPositions() {
        for (filter in listOf(MediaFilter.IMAGE, MediaFilter.VIDEO)) {
            val result = savedLibraryItems(mixed, filter, "احمد 02", SavedSort.NEWEST, Locale("ar"))
            assertEquals(listOf("1", "2"), ids(result))
            assertEquals(listOf(SavedPostPosition(1, 2), SavedPostPosition(2, 2)), result.map { it.postPosition })
        }
        assertEquals(listOf("3"), ids(savedLibraryItems(mixed, MediaFilter.AUDIO, "song", SavedSort.NEWEST, Locale.ENGLISH)))
        assertTrue(savedLibraryItems(mixed, MediaFilter.AUDIO, "احمد", SavedSort.NEWEST, Locale("ar")).isEmpty())
    }
    @Test fun newestUsesNewestSiblingAndLargestUsesWholePostBytesWithUnknownLast() {
        val input = mixed + file(4, "Unknown.mp4", time = 200, size = null) + file(5, "Small.mp4", time = 90, size = 80)
        assertEquals(listOf("4", "1", "2", "3", "5"), ids(savedLibraryItems(input)))
        assertEquals(listOf("1", "2", "5", "3", "4"), ids(savedLibraryItems(input, sort = SavedSort.LARGEST)))
    }
    @Test fun largestKeepsPartiallyUnknownGroupsBehindKnownSizes() {
        val input = mixed.map { if (it.uri.lastPathSegment == "2") it.copy(size = null) else it }
        assertEquals(listOf("3", "1", "2"), ids(savedLibraryItems(input, sort = SavedSort.LARGEST)))
    }
    @Test fun alphabeticalUsesArabicCollationRatherThanUnicodeCodePointOrder() {
        val input = listOf(file(1, "بدر.mp4"), file(2, "أحمد.mp4"), file(3, "احمد.mp4"), file(4, "آدم.mp4"))
        assertEquals(listOf("2", "4", "3", "1"), ids(savedLibraryItems(input, sort = SavedSort.NAME, locale = Locale("ar"))))
    }
    @Test fun alphabeticalUsesLatinCollationAndKeepsGroupedSiblingsTogether() {
        val input = listOf(file(1, "Zulu.mp4"), file(2, "été_01.jpg", "image/jpeg"),
            file(3, "été_02.mp4"), file(4, "Apple.mp4"))
        assertEquals(listOf("4", "2", "3", "1"), ids(savedLibraryItems(input, sort = SavedSort.NAME, locale = Locale.FRENCH)))
    }
    @Test fun chipsOnlyIncludePresentKindsAndHideForSingleKindOrEmptyLibrary() {
        assertEquals(listOf(MediaFilter.ALL, MediaFilter.VIDEO, MediaFilter.AUDIO, MediaFilter.IMAGE), availableMediaFilters(mixed))
        assertEquals(listOf(MediaFilter.ALL, MediaFilter.VIDEO, MediaFilter.IMAGE), availableMediaFilters(mixed.take(2)))
        assertTrue(availableMediaFilters(mixed.take(1)).isEmpty())
        assertTrue(availableMediaFilters(emptyList()).isEmpty())
    }
    @Test fun statusesChipEndsTheRowAndShowsOnlyStatusSaves() {
        val status = file(9, "Status_01.jpg", "image/jpeg", 110).copy(relativePath = "Download/Nezzel/Statuses/")
        val statusNoSlash = file(10, "Status_02.mp4", time = 111).copy(relativePath = "Download/Nezzel/Statuses")
        assertEquals(listOf(MediaFilter.ALL, MediaFilter.VIDEO, MediaFilter.AUDIO, MediaFilter.IMAGE, MediaFilter.STATUSES),
            availableMediaFilters(mixed + status))
        // One kind plus status saves still offers the Statuses chip.
        assertEquals(listOf(MediaFilter.ALL, MediaFilter.VIDEO, MediaFilter.STATUSES), availableMediaFilters(listOf(mixed[1], statusNoSlash)))
        assertEquals(listOf("10", "9"), ids(savedLibraryItems(mixed + status + statusNoSlash, MediaFilter.STATUSES)))
        // Status files stay in All and in their kind's chip.
        assertTrue("9" in ids(savedLibraryItems(mixed + status, MediaFilter.IMAGE)))
        assertFalse(file(11, "Statuses.mp4").copy(relativePath = "Download/Nezzel/").fromStatuses)
    }
    @Test fun sortDefaultsToNewestAndSurvivesNewPreferenceInstance() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("nezzel-portable", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(SavedSort.NEWEST, AppPreferences(app).savedSort)
        for (sort in SavedSort.entries) {
            AppPreferences(app).savedSort = sort
            assertEquals(sort, AppPreferences(app).savedSort)
        }
        prefs.edit().putString("saved_sort", "removed_choice").commit()
        assertEquals(SavedSort.NEWEST, AppPreferences(app).savedSort)
    }
}

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
    /**
     * Opens a file's sheet and waits for its Rename row. The row appears after `canRename` resolves on Dispatchers.IO; the Compose test
     * dispatcher is unconfined, so that coroutine resumes and writes its state on the IO thread. Written while the main thread is still
     * composing the sheet, the write is lost and the row never shows. Holding the query until the sheet has settled, then letting the main
     * thread sit idle while the result lands, makes the order fixed.
     */
    private fun openSheetWithRename(a: ComponentActivity, provider: SavedMediaRenameTest.RenameProvider, more: String) {
        val gate = java.util.concurrent.CountDownLatch(1)
        provider.queryGate = gate
        try {
            click(a, more)
            until(a) { action(a, app.getString(R.string.share_file)) != null }
        } finally {
            provider.queryGate = null
            gate.countDown()
        }
        Thread.sleep(200)
        until(a) { action(a, app.getString(R.string.saved_rename)) != null }
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
            openSheetWithRename(a, provider, app.getString(R.string.saved_more, rows.single().displayName))
            tap(a, app.getString(R.string.saved_rename))
            until(a) { nodes -> nodes.any { it.config.getOrNull(SemanticsActions.SetText) != null } }
            val field = field(a)
            assertEquals("Saved 1", field.config[SemanticsProperties.EditableText].text)
            assertEquals(TextRange(0, 7), field.config[SemanticsProperties.TextSelectionRange])
            assertTrue(texts(semanticsNodes(a)).contains(app.getString(R.string.saved_rename_extension, NezzelFormat.isolate(".mp4"))))
            field.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("  "))
            settle(a)
            assertNotNull(action(a, app.getString(R.string.saved_rename))!!.config.getOrNull(SemanticsProperties.Disabled))
            assertTrue(texts(semanticsNodes(a)).contains(app.getString(R.string.saved_rename_invalid)))
            field(a).config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("a/b"))
            settle(a)
            assertTrue(texts(semanticsNodes(a)).contains(app.getString(R.string.saved_rename_slash)))
            assertFalse(texts(semanticsNodes(a)).contains(app.getString(R.string.saved_rename_invalid)))
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
            openSheetWithRename(a, provider, more)
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
