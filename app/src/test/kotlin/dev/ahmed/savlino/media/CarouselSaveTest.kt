package dev.ahmed.savlino.media

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.ui.RecordingPublisher
import dev.ahmed.savlino.ui.noSpaceCheck
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarouselSaveTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val staging get() = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "carousel-save")
    private val source = LinkRevision(LinkInput.parse("https://example.com/synthetic.jpg")!!, 1)
    private val identity = MediaAdapterIdentity("public-image", 1, "fixture", 1)
    private val validity = MediaInspectionValidity.processLocal()
    private val image = MediaImageMetadata(MediaImageFormat.JPEG)
    private fun request(n: Int) = ImageSaveRequest(MediaSelection(source, identity,
        MediaRenditionRef(MediaItemRef(identity.adapterId, "photo-$n"), "original"), MediaKind.IMAGE, validity), image, "Post_$n")
    @After fun stop() { scope.cancel(); staging.deleteRecursively() }

    @Test fun aFailedPhotoDoesNotLosePublishedSiblingsAndReportsTheSavedCount() = runBlocking {
        val publisher = RecordingPublisher()
        val calls = mutableListOf<String>()
        val fetch = ImageFetch { selection, sink ->
            calls += selection.renditionRef.item.itemId
            if (selection.renditionRef.item.itemId == "photo-2") ImageSaveResult.Failure(ImageFailure.HTTP, 404)
            else { sink.write(byteArrayOf(1, 2, 3)); ImageSaveResult.Success(image) }
        }
        val saver = ImageSaver(fetch, publisher, staging, noSpaceCheck, scope)
        assertTrue(saver.saveBatch(listOf(request(1), request(2), request(3))))
        withTimeout(10_000) { saver.awaitIdle() }
        val result = saver.outcome.value as ImageSaveState.BatchFinished
        assertEquals(listOf("photo-1", "photo-2", "photo-3"), calls)
        assertEquals(listOf("Post_1.jpg", "Post_3.jpg"), publisher.names)
        assertEquals(2, result.saved.size)
        assertEquals(3, result.total)
        val context = RuntimeEnvironment.getApplication()
        for ((language, expected) in listOf("en" to "Saved 2 of 3 photos. Some photos could not be saved.",
            "ar" to "تم حفظ 2 من أصل 3. تعذّر حفظ البقية.")) {
            val configuration = android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale.forLanguageTag("$language-u-nu-latn"))
            }
            assertEquals(expected, dev.ahmed.savlino.ui.imageBatchMessage(context.createConfigurationContext(configuration).resources,
                result, language))
        }
        assertFalse(result.cancelled)
        assertEquals(ImageFailure.HTTP, result.failure?.image)
        assertTrue(staging.walkTopDown().none { it.isFile })
    }

    @Test fun cancelDuringSecondPhotoKeepsFirstAndNeverStartsThird() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val publisher = RecordingPublisher()
        val calls = mutableListOf<String>()
        val fetch = object : ImageFetch {
            override suspend fun transfer(selection: MediaSelection, sink: OutputStream): ImageSaveResult {
                calls += selection.renditionRef.item.itemId
                if (calls.size == 2) { entered.complete(Unit); resume.await() }
                sink.write(byteArrayOf(1, 2, 3))
                return ImageSaveResult.Success(image)
            }
            override fun cancel() { resume.complete(Unit) }
        }
        val saver = ImageSaver(fetch, publisher, staging, noSpaceCheck, scope)
        assertTrue(saver.saveBatch(listOf(request(1), request(2), request(3))))
        withTimeout(10_000) { entered.await() }
        val progress = saver.outcome.value as ImageSaveState.Saving
        assertEquals(2, progress.current); assertEquals(3, progress.total)
        assertFalse(saver.saveBatch(listOf(request(4))))
        saver.cancel()
        withTimeout(10_000) { saver.awaitIdle() }
        val result = saver.outcome.value as ImageSaveState.BatchFinished
        assertTrue(result.cancelled)
        assertEquals(1, result.saved.size)
        assertEquals(3, result.total)
        assertEquals(listOf("photo-1", "photo-2"), calls)
        assertEquals(listOf("Post_1.jpg"), publisher.names)
        assertTrue(staging.walkTopDown().none { it.isFile })
    }

    @Test fun numericSuffixSurvivesTheFilenameCapsAndKeepsRealEncoding() {
        for (title in listOf("a".repeat(100), "صورة جميلة ".repeat(30), "😀".repeat(100))) {
            val name = FilenameSanitizer.sanitize(indexedImageTitle(title, 20, "webp"), "webp")
            assertTrue(name, name.endsWith("_20.webp"))
            assertTrue(name, name.removeSuffix(".webp").codePointCount(0, name.length - 5) <= 80)
            assertTrue(name, name.toByteArray(Charsets.UTF_8).size <= 240)
        }
    }
}
