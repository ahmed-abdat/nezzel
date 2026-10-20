package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediahttp.*
import kotlinx.coroutines.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PublicImageReferenceTest : PublicImageTestBase() {
    @Test fun cancelBeforeInspectionRegistrationPreventsAllNetworkAndDecode() = fixture { f ->
        f.http.control()
        val operation = f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)
        f.inspector.cancel(operation)
        val dnsBefore = f.http.dnsCalls.get()
        failed(runBlocking { f.inspector.inspect(operation) }, ImageFailure.CANCELLED)
        assertEquals(dnsBefore, f.http.dnsCalls.get())
        assertEquals(1, f.server.requests.size)
    }

    @Test fun replacingSourceClosesOwnedPreviewAndMakesOldSelectionUnresolvable() = fixture { f ->
        val old = inspected(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) })
        val selection = assertNotNull(old.inspection.selection(firstRendition(old).ref))
        assertNotNull(f.store.resolve(selection), "exact returned selection resolves before replacement")
        f.inspector.newOperation(imageSource(revision = 2), IMAGE_IDENTITY)
        assertTrue(assertNotNull(old.preview).isClosed)
        assertNull(f.store.resolve(selection))
        old.close()
    }

    @Test fun generationRecipeRuntimeAndValidityReplacementCannotReuseOldHandle() = fixture { f ->
        val original = inspected(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) })
        val selection = assertNotNull(original.inspection.selection(firstRendition(original).ref))
        assertNotNull(f.store.resolve(selection))
        for (forged in listOf(selection.copy(identity = IMAGE_IDENTITY.copy(generation = 2)),
            selection.copy(identity = IMAGE_IDENTITY.copy(recipeVersion = 2)),
            selection.copy(identity = IMAGE_IDENTITY.copy(runtimeVersion = "other-runtime")),
            selection.copy(validity = MediaInspectionValidity.processLocal()),
            selection.copy(source = imageSource(revision = 2)),
            selection.copy(renditionRef = MediaRenditionRef(selection.renditionRef.item, "unreturned-row"))))
            assertNull(f.store.resolve(forged))
        f.inspector.newOperation(imageSource(), IMAGE_IDENTITY.copy(generation = 2))
        assertNull(f.store.resolve(selection))
        assertTrue(assertNotNull(original.preview).isClosed)
    }

    @Test fun anotherStoreOrProcessCannotResolveSelectionEvenWithMatchingPublicFields() = fixture { f ->
        inspected(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) }).use { result ->
            val selection = assertNotNull(result.inspection.selection(firstRendition(result).ref))
            assertNotNull(f.store.resolve(selection))
            MediaReferenceStore().use { recreated -> assertNull(recreated.resolve(selection)) }
        }
    }

    @Test fun lateDecodeAfterSourceReplacementIsDiscardedAndRecycled() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val jdk = JdkPreviewDecoder()
        val decoder = ImagePreviewDecoder { bytes, mime, active, length ->
            val decoded = jdk.decode(bytes, mime, active, length)
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            decoded
        }
        try {
            fixture(decoder) { f -> runBlocking {
                val oldOperation = f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)
                val task = async(Dispatchers.Default) { f.inspector.inspect(oldOperation) }
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS), "actual decode reached admission barrier")
                    f.inspector.newOperation(imageSource(revision = 2), IMAGE_IDENTITY)
                    release.countDown()
                    failed(withTimeout(3000) { task.await() }, ImageFailure.STALE)
                    assertTrue(jdk.previews.single().isClosed)
                } finally { release.countDown(); task.cancelAndJoin() }
            } }
        } finally { release.countDown() }
    }

    @Test fun lateDecodeAfterCancelIsDiscardedWithoutAUsableReference() {
        val jdk = JdkPreviewDecoder()
        var inspector: PublicImageInspector? = null
        var operation: ImageInspectionOperation? = null
        val decoder = ImagePreviewDecoder { bytes, mime, active, length ->
            jdk.decode(bytes, mime, active, length).also { inspector!!.cancel(operation!!) }
        }
        fixture(decoder) { f ->
            inspector = f.inspector
            operation = f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)
            failed(runBlocking { f.inspector.inspect(operation!!) }, ImageFailure.CANCELLED)
            assertTrue(jdk.previews.single().isClosed)
        }
    }

    @Test fun oneActiveDecodeCannotPublishASecondInspectionOverAnUnreleasedPreview() = fixture { f ->
        val first = inspected(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) })
        val replacement = f.inspector.newOperation(imageSource(revision = 2), IMAGE_IDENTITY)
        assertTrue(assertNotNull(first.preview).isClosed)
        inspected(runBlocking { f.inspector.inspect(replacement) }).use { second ->
            assertFalse(assertNotNull(second.preview).isClosed)
            assertFalse(second.inspection.validity === first.inspection.validity)
            assertNotEquals(firstRendition(first).ref, firstRendition(second).ref)
        }
    }

    @Test fun replacementCannotRunASecondPixelDecodeWhileRetiredDecodeStillOwnsRaster() {
        val firstEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val active = java.util.concurrent.atomic.AtomicInteger()
        val maximum = java.util.concurrent.atomic.AtomicInteger()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val jdk = JdkPreviewDecoder()
        val decoder = ImagePreviewDecoder { bytes, mime, current, length ->
            val count = active.incrementAndGet()
            maximum.accumulateAndGet(count, ::maxOf)
            try {
                if (calls.incrementAndGet() == 1) {
                    firstEntered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                } else secondEntered.countDown()
                jdk.decode(bytes, mime, current, length)
            } finally { active.decrementAndGet() }
        }
        try {
            fixture(decoder) { f -> runBlocking {
                val old = f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)
                val first = async(Dispatchers.Default) { f.inspector.inspect(old) }
                var second: Deferred<MediaInspectResult>? = null
                try {
                    assertTrue(firstEntered.await(3, TimeUnit.SECONDS))
                    val next = f.inspector.newOperation(imageSource(revision = 2), IMAGE_IDENTITY)
                    val secondTask = async(Dispatchers.Default) { secondStarted.countDown(); f.inspector.inspect(next) }
                    second = secondTask
                    assertTrue(secondStarted.await(3, TimeUnit.SECONDS))
                    assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS), "retiring a token cannot release the decoder's still-owned raster")
                    release.countDown()
                    failed(withTimeout(3000) { first.await() }, ImageFailure.STALE)
                    inspected(withTimeout(3000) { secondTask.await() }).close()
                    assertEquals(1, maximum.get(), "process admits one pixel decode at a time")
                } finally { release.countDown(); first.cancelAndJoin(); second?.cancelAndJoin() }
            } }
        } finally { release.countDown() }
    }
}
