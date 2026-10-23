package dev.ahmed.savlino.jobs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], manifest = Config.NONE)
class JobArtworkTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val root get() = File(app.filesDir, "job-art")

    @After fun tearDown() { SheetArtwork.clear(); root.deleteRecursively() }

    private fun noisy(width: Int, height: Int): Bitmap {
        val random = Random(7)
        return Bitmap.createBitmap(IntArray(width * height) { random.nextInt() or (0xFF shl 24) }, width, height, Bitmap.Config.ARGB_8888)
    }

    @Test fun encodeIsSquareAndCapped() {
        val bytes = JobArtwork.encode(noisy(1280, 720))!!
        assertTrue(bytes.size <= JobArtwork.MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(JobArtwork.SIDE, bounds.outWidth)
        assertEquals(JobArtwork.SIDE, bounds.outHeight)
        assertEquals("image/jpeg", bounds.outMimeType)
    }

    @Test fun smallSourceIsNotUpscaled() {
        val bytes = JobArtwork.encode(noisy(90, 120))!!
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(90, bounds.outWidth)
        assertEquals(90, bounds.outHeight)
    }

    @Test fun saveRejectsOversizeAndBadIds() {
        val artwork = JobArtwork(app.filesDir)
        assertFalse(artwork.save("job", ByteArray(JobArtwork.MAX_BYTES + 1)))
        assertFalse(artwork.save("../escape", byteArrayOf(1)))
        assertFalse(File(root, "job").exists())
    }

    @Test fun loadIsNullForMissingOrGarbage() = runBlocking {
        val artwork = JobArtwork(app.filesDir)
        assertNull(artwork.load("missing"))
        assertTrue(artwork.save("garbage", byteArrayOf(1, 2, 3)))
        assertNull(artwork.load("garbage"))
    }

    @Test fun pruneDropsArtworkOfRowsTheStoreNoLongerHas() = runBlocking {
        val store = JobStore(app)
        try {
            store.enqueue(command("kept"))
            val bytes = JobArtwork.encode(noisy(320, 180))!!
            assertTrue(store.artwork.save("kept", bytes))
            assertTrue(store.artwork.save("orphan", bytes))
            File(root, "half.tmp").writeBytes(bytes)
            store.pruneTerminal()
            assertTrue(File(root, "kept").isFile)
            assertFalse(File(root, "orphan").exists())
            assertFalse(File(root, "half.tmp").exists())
            assertTrue(store.artwork.load("kept") != null)
            assertTrue(store.remove("kept").not()) // live row: Remove is terminal-only
            store.fail("kept", dev.ahmed.savlino.domain.DownloadError.Failed)
            assertTrue(store.remove("kept"))
            assertFalse(File(root, "kept").exists())
            assertNull(store.artwork.load("kept"))
        } finally { store.close(); app.deleteDatabase(JobStore.DATABASE_NAME) }
    }

    @Test fun clearRemovesEverything() = runBlocking {
        val artwork = JobArtwork(app.filesDir)
        val bytes = JobArtwork.encode(noisy(200, 200))!!
        assertTrue(artwork.save("a", bytes)); assertTrue(artwork.save("b", bytes))
        artwork.load("a")
        artwork.clear()
        assertNull(artwork.load("a"))
        assertTrue(root.listFiles().isNullOrEmpty())
    }

    @Test fun sheetKeepsOnlyTheLastTwoInspections() {
        val bitmap = noisy(64, 64)
        SheetArtwork.offer("one", bitmap); SheetArtwork.offer("two", bitmap); SheetArtwork.offer("three", bitmap)
        assertNull(SheetArtwork.take("one"))
        assertTrue(SheetArtwork.take("two") != null && SheetArtwork.take("three") != null)
        assertFalse(bitmap.isRecycled)
    }
}
