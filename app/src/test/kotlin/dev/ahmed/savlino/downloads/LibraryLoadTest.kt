package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w400dp-h1200dp-mdpi")
class LibraryLoadTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val work = LibraryTestWork(compose)
    @org.junit.After fun closeWork() { work.close() }
    private val context get() = RuntimeEnvironment.getApplication()
    private val row = SavedMedia(Uri.parse("content://media/external/downloads/7"), "Holiday.mp4", "Holiday", "video/mp4", 100, 100, 720, null, null)
    private fun await(predicate: () -> Boolean) = work.await(predicate)
    private fun delayed(check: (Channel<Result<List<SavedMedia>>>, () -> LibraryState, () -> Int) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val events = Channel<Result<List<SavedMedia>>>(Channel.UNLIMITED)
        var starts = 0
        var state: LibraryState? = null
        val observe = { flow { starts++; for (result in events) emit(result) } }
        try {
            controller.get().setContent {
                NezzelTheme(ThemePref.Light, false) {
                    val current = rememberLibraryState(remember { SnackbarHostState() }, observe, ioDispatcher = work)
                    state = current
                    DownloadsScreen(emptyList(), "en", {}, {}, {}, {}, {}, null, {}, {},
                        libraryLoad = current.load, onLibraryRetry = current.onRetry)
                }
            }
            compose.waitForIdle()
            check(events, { state!! }, { starts })
        } finally { controller.pause().stop().destroy(); events.close() }
    }
    @Test fun unresolvedFirstQueryIsLoadingThenPopulatedWithoutEmptyOnboarding() = delayed { events, state, starts ->
        assertEquals("Production observation must start", 1, starts())
        assertTrue(state().load is LibraryLoadState.Loading)
        compose.onNodeWithText(context.getString(R.string.saved_empty_title)).assertDoesNotExist()
        events.trySend(Result.success(listOf(row)))
        await { state().load is LibraryLoadState.Ready }
        assertEquals(listOf(row), state().media)
        compose.onNodeWithText(context.getString(R.string.saved_empty_title)).assertDoesNotExist()
    }
    @Test fun delayedSuccessfulEmptyQueryAloneShowsTrueEmptyAndPaste() = delayed { events, state, starts ->
        assertEquals(1, starts())
        assertTrue(state().load is LibraryLoadState.Loading)
        compose.onNodeWithText(context.getString(R.string.saved_empty_title)).assertDoesNotExist()
        events.trySend(Result.success(emptyList()))
        await { state().load is LibraryLoadState.Ready }
        compose.onNodeWithText(context.getString(R.string.saved_empty_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.paste_link)).assertHasClickAction()
    }
    @Test fun firstFailureRetryButtonRecollectsAndRecoversActualRows() = delayed { events, state, starts ->
        assertEquals(1, starts())
        events.trySend(Result.failure(IllegalStateException()))
        await { state().load is LibraryLoadState.Error }
        assertNull(state().load.lastSuccessfulRows)
        compose.onNodeWithText(context.getString(R.string.saved_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.try_again)).performClick()
        await { starts() == 2 }
        assertTrue(state().load is LibraryLoadState.Loading)
        events.trySend(Result.success(listOf(row)))
        await { state().load is LibraryLoadState.Ready }
        assertEquals(listOf(row), state().media)
    }
    @Test fun refreshFailureAndPendingRetryKeepExactSuccessfulRows() = delayed { events, state, starts ->
        assertEquals(1, starts())
        events.trySend(Result.success(listOf(row)))
        await { state().load is LibraryLoadState.Ready }
        events.trySend(Result.failure(IllegalStateException()))
        await { state().load is LibraryLoadState.Error }
        assertEquals(listOf(row), state().media)
        compose.onNodeWithText(context.getString(R.string.try_again)).performClick()
        await { starts() == 2 }
        assertTrue(state().load is LibraryLoadState.Loading)
        assertEquals(listOf(row), state().load.lastSuccessfulRows)
        assertEquals(listOf(row), state().media)
        compose.onNodeWithText(context.getString(R.string.saved_empty_title)).assertDoesNotExist()
        events.trySend(Result.success(listOf(row.copy(size = 200))))
        await { state().load is LibraryLoadState.Ready && state().media.single().size == 200L }
    }
    @Test fun pauseThenResumeWithoutStopRestartsLibraryValidation() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var starts = 0
        val observe = { flow<Result<List<SavedMedia>>> {
            starts++
            emit(Result.success(listOf(row)))
            kotlinx.coroutines.awaitCancellation()
        } }
        try {
            controller.get().setContent { rememberLibraryState(remember { SnackbarHostState() }, observe, ioDispatcher = work) }
            await { starts == 1 }
            controller.pause()
            compose.waitForIdle()
            controller.resume()
            await { starts == 2 }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun defaultRepositoryRetryRequeriesRealProviderWithoutDeletingRows() {
        var fail = true
        var queries = 0
        val provider = object : SavedMediaRepositoryTest.Provider() {
            override fun query(uri: Uri, columns: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): android.database.Cursor {
                queries++
                if (fail) throw IllegalStateException()
                return super.query(uri, columns, selection, args, sort)
            }
        }.apply { attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media" }) }
        ShadowContentResolver.registerProviderInternal("media", provider)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        var state: LibraryState? = null
        try {
            controller.get().setContent { state = rememberLibraryState(remember { SnackbarHostState() }, ioDispatcher = work) }
            await { queries > 0 && state?.load is LibraryLoadState.Error }
            assertTrue("First provider failure must be Error", state!!.load is LibraryLoadState.Error)
            val before = queries
            fail = false
            compose.runOnIdle { state!!.onRetry() }
            await { queries > before && state!!.load is LibraryLoadState.Ready }
            assertEquals(1, state!!.media.size)
            assertEquals(1, provider.db.rawQuery("SELECT _id FROM media", null).use { it.count })
        } finally { controller.pause().stop().destroy(); work.close(); provider.db.close() }
    }
}
