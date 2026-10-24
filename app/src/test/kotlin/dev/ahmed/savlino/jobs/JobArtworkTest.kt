package dev.ahmed.savlino.jobs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
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

    @After fun tearDown() { SheetArtwork.clear(); SheetArtwork.encoder = { JobArtwork.encode(it) }; root.deleteRecursively() }

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

    @Test fun deleteDuringDecodeIsNotCachedBack() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val codec = object : dev.ahmed.savlino.preview.PreviewArtworkCodec() {
            override fun decode(bytes: ByteArray, maxAxis: Int, maxAllocationBytes: Long): Bitmap? {
                entered.countDown(); release.await()
                return super.decode(bytes, maxAxis, maxAllocationBytes)
            }
        }
        val artwork = JobArtwork(app.filesDir, codec)
        assertTrue(artwork.save("racy", JobArtwork.encode(noisy(200, 200))!!))
        val loading = async(kotlinx.coroutines.Dispatchers.IO) { artwork.load("racy") }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        artwork.delete("racy")
        release.countDown()
        assertNull(loading.await())
        assertNull(artwork.load("racy"))
    }

    @Test fun reusedJobIdShowsTheNewArtwork() = runBlocking {
        val artwork = JobArtwork(app.filesDir)
        fun solid(color: Int) = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        assertTrue(artwork.save("same", JobArtwork.encode(solid(android.graphics.Color.RED))!!))
        assertTrue(android.graphics.Color.red(artwork.load("same")!!.getPixel(80, 80)) > 200)
        assertTrue(artwork.save("same", JobArtwork.encode(solid(android.graphics.Color.BLUE))!!))
        val pixel = artwork.load("same")!!.getPixel(80, 80)
        assertTrue(android.graphics.Color.blue(pixel) > 200 && android.graphics.Color.red(pixel) < 60)
    }

    @Test fun downloadTappedBeforeThePreviewDrewFetchesItAfterwards() = runBlocking {
        JobStore(app).use { store ->
            val loaded = mutableListOf<String>()
            val coordinator = DownloadCoordinator(app, dev.ahmed.savlino.engine.FakeEngine(), store,
                workManager = { error("No background scheduler in this fixture") }, previewArtwork = { loaded += it; noisy(64, 48) })
            val early = command("early")
            SheetArtwork.source(early.inspectionId, "https://example.com/preview.jpg")
            assertTrue(coordinator.enqueue(early))
            kotlinx.coroutines.withTimeout(5_000) { while (!store.artwork.has("early")) kotlinx.coroutines.delay(20) }
            assertEquals(listOf("https://example.com/preview.jpg"), loaded)
        }
    }

    @Test fun confirmBeforeEncodeStillGetsBytes() {
        val bitmap = noisy(320, 180)
        SheetArtwork.show("early", bitmap) // background encode not run yet
        val bytes = SheetArtwork.take("early")!!
        assertTrue(bytes.size in 1..JobArtwork.MAX_BYTES)
        assertTrue(SheetArtwork.take("early") === bytes)
        assertFalse(bitmap.isRecycled)
    }

    @Test fun confirmRacingTheBackgroundEncodeGetsItsBytes() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        SheetArtwork.encoder = { bitmap ->
            if (calls.incrementAndGet() == 1) { entered.countDown(); release.await() }
            JobArtwork.encode(bitmap)
        }
        SheetArtwork.show("race", noisy(320, 180))
        val background = java.util.concurrent.Executors.newSingleThreadExecutor()
        val confirm = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val encoded = background.submit<ByteArray?> { SheetArtwork.take("race") }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)) // background encode is mid-flight
            val confirmed = confirm.submit<ByteArray?> { SheetArtwork.take("race") }
            Thread.sleep(100)
            assertFalse(confirmed.isDone) // the confirm waits instead of reading a half-updated entry
            release.countDown()
            val bytes = encoded.get(5, java.util.concurrent.TimeUnit.SECONDS)!!
            assertTrue(confirmed.get(5, java.util.concurrent.TimeUnit.SECONDS) === bytes)
            assertEquals(1, calls.get())
        } finally { release.countDown(); background.shutdownNow(); confirm.shutdownNow() }
    }
}
