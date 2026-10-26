package dev.ahmed.savlino.playback

import android.net.Uri
import dev.ahmed.savlino.viewer.ViewerItem
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackResumeStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun item(id: Int = 1, saved: Boolean = true) = ViewerItem(Uri.parse("content://test/items/$id"), true,
        "video/mp4", resumePolicy = if (saved) ResumePolicy.SAVED else ResumePolicy.SESSION_ONLY)
    private val file get() = File(folder.root, "playback.json")

    @Test fun restartRestoresPositionWithoutStoringUri() = runBlocking {
        PlaybackResumeStore(file).save(item(), 123, 12_000, 100_000)
        assertEquals(12_000, PlaybackResumeStore(file).position(item(), 123, 100_000))
        assertFalse(file.readText().contains("content:"))
        assertEquals(0, PlaybackResumeStore(file).position(item(), 124, 100_000))
    }
    @Test fun unannotatedStatusAndUnknownSizeNeverTouchDisk() = runBlocking {
        val store = PlaybackResumeStore(file)
        val plain = ViewerItem(Uri.parse("content://test/status/1"), true, "video/mp4")
        store.save(plain, 123, 12_000, 100_000)
        store.save(item(saved = false), 123, 12_000, 100_000)
        store.save(item(), null, 12_000, 100_000)
        assertFalse(file.exists())
    }
    @Test fun completionAndLoopRemovalSurviveRestart() = runBlocking {
        val store = PlaybackResumeStore(file)
        store.save(item(), 123, 50_000, 100_000)
        store.save(item(), 123, 95_000, 100_000) // 5%, not 10 seconds
        assertEquals(0, PlaybackResumeStore(file).position(item(), 123, 100_000))
        store.save(item(), 123, 50_000, 100_000)
        store.clear(item(), 123)
        assertEquals(0, PlaybackResumeStore(file).position(item(), 123, 100_000))
    }
    @Test fun clampsNegativePositionsAndChangedDuration() = runBlocking {
        val store = PlaybackResumeStore(file)
        store.save(item(), 123, -7, 100_000)
        assertEquals(0, store.position(item(), 123, 100_000))
        store.save(item(), 123, 60_000, 100_000)
        assertEquals(0, store.position(item(), 123, 50_000))
    }
    @Test fun lruEvictsLeastRecentlyUsedAtTwoHundred() = runBlocking {
        var now = 0L
        val store = PlaybackResumeStore(file) { ++now }
        for (id in 1..200) store.save(item(id), 123, 12_000, 100_000)
        assertEquals(12_000, store.position(item(1), 123, 100_000))
        store.save(item(201), 123, 12_000, 100_000)
        val restarted = PlaybackResumeStore(file)
        assertEquals(0, restarted.position(item(2), 123, 100_000))
        assertEquals(12_000, restarted.position(item(1), 123, 100_000))
    }
    @Test fun corruptAndUnreadableFilesFailOpen() = runBlocking {
        file.writeText("{broken")
        assertEquals(0, PlaybackResumeStore(file).position(item(), 123, 100_000))
        val directory = folder.newFolder("not-a-file")
        val store = PlaybackResumeStore(directory)
        assertEquals(0, store.position(item(), 123, 100_000))
        store.save(item(), 123, 12_000, 100_000) // write errors must not escape
    }
    @Test fun concurrentWritesAreSerializedAndAtomic() = runBlocking {
        val store = PlaybackResumeStore(file)
        coroutineScope { (1..40).map { id -> async(Dispatchers.Default) { store.save(item(id), 123, 12_000, 100_000) } }.awaitAll() }
        val restarted = PlaybackResumeStore(file)
        for (id in 1..40) assertEquals(12_000, restarted.position(item(id), 123, 100_000))
        assertFalse(File(file.parentFile, file.name + ".tmp").exists())
    }
}
