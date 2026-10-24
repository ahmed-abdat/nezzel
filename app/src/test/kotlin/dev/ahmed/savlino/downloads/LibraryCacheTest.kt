package dev.ahmed.savlino.downloads

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import dev.ahmed.savlino.statuses.StatusSaver
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LibraryCacheTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val work = LibraryTestWork(compose)
    @org.junit.After fun closeWork() { work.close() }
    private val file get() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, LibraryCache.FILE_NAME)
    private val kept = SavedMedia(Uri.parse("content://media/external/downloads/7"), "Holiday.mp4", "Holiday", "video/mp4",
        100, 200, 720, 900, 61_000, width = 1280, relativePath = "Download/Nezzel/", volumeName = "external_primary")
    private val deleted = SavedMedia(Uri.parse("content://media/external/downloads/8"), "Status.jpg", "Status", "image/jpeg",
        null, 100, null, null, null, relativePath = StatusSaver.RELATIVE_PATH)

    private fun library(cache: LibraryCache, check: (Channel<Result<List<SavedMedia>>>, () -> LibraryState) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val events = Channel<Result<List<SavedMedia>>>(Channel.UNLIMITED)
        var state: LibraryState? = null
        val observe = { flow { for (result in events) emit(result) } }
        try {
            controller.get().setContent { state = rememberLibraryState(remember { SnackbarHostState() }, observe, ioDispatcher = work, cache = cache) }
            compose.waitForIdle()
            check(events) { state!! }
        } finally { controller.pause().stop().destroy(); events.close() }
    }

    @Test fun roundTripKeepsEveryRowFieldIncludingStatusesPath() {
        val cache = LibraryCache(file)
        cache.write(listOf(kept, deleted))
        assertEquals(listOf(kept, deleted), cache.read())
        assertEquals(listOf(MediaFilter.ALL, MediaFilter.VIDEO, MediaFilter.IMAGE, MediaFilter.STATUSES).toSet(),
            availableMediaFilters(cache.read()!!).toSet())
    }

    @Test fun writeKeepsAtMostTheCap() {
        val cache = LibraryCache(file)
        cache.write(List(LibraryCache.MAX_ROWS + 20) { kept.copy(uri = Uri.parse("content://media/external/downloads/$it")) })
        assertEquals(LibraryCache.MAX_ROWS, cache.read()!!.size)
    }

    @Test fun coldStartShowsCachedRowsThenDeletedFileDropsAfterRefresh() {
        val cache = LibraryCache(file).apply { write(listOf(kept, deleted)) }
        library(cache) { events, state ->
            work.await { state().load.lastSuccessfulRows != null }
            assertTrue(state().load is LibraryLoadState.Loading)
            assertEquals(listOf(kept, deleted), state().media)
            events.trySend(Result.success(listOf(kept)))
            work.await { state().load is LibraryLoadState.Ready }
            assertEquals(listOf(kept), state().media)
            work.await { cache.read() == listOf(kept) }
        }
    }

    @Test fun corruptCacheFallsBackToPlainLoading() {
        file.parentFile!!.mkdirs()
        file.writeText("{not json")
        val cache = LibraryCache(file)
        assertNull(cache.read())
        library(cache) { events, state ->
            assertEquals(LibraryLoadState.Loading(), state().load)
            events.trySend(Result.failure(IllegalStateException()))
            work.await { state().load is LibraryLoadState.Error }
            assertNull(state().load.lastSuccessfulRows)
            events.trySend(Result.success(listOf(kept)))
            work.await { state().load is LibraryLoadState.Ready }
            work.await { cache.read() == listOf(kept) }
        }
    }
}
