package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediahttp.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class PublicImageSaveTest : PublicImageTestBase() {
    private class Sink(private val afterWrite: () -> Unit = {}) : OutputStream() {
        val path = Files.createTempFile("image-proof-save-", ".part")
        private val stream = Files.newOutputStream(path)
        var bytes = 0L; private set
        var writes = 0; private set
        var closed = false; private set
        override fun write(value: Int) = error("bulk transfer required")
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            stream.write(buffer, offset, length); bytes += length; writes++; afterWrite()
        }
        fun contents(): ByteArray { stream.flush(); return Files.readAllBytes(path) }
        override fun close() { if (!closed) { closed = true; stream.close(); Files.deleteIfExists(path) } }
    }
    private fun selection(f: PublicImageFixture): MediaSelection {
        val result = inspected(runBlocking { f.inspector.inspect(f.inspector.newOperation(imageSource(), IMAGE_IDENTITY)) })
        return assertNotNull(result.inspection.selection(firstRendition(result).ref))
    }
    private fun transferFailure(result: ImageSaveResult, reason: ImageFailure) =
        assertEquals(reason, assertIs<ImageSaveResult.Failure>(result).reason)

    @Test fun saveRefetchesExactSelectionIntoPrivateSinkAndVerifiesSameCompleteRepresentation() {
        val bytes = raster(51, 27)
        fixture(respond = { _, socket -> socket.reply(body = bytes, headers = mapOf("Content-Type" to "image/png")) }) { f ->
            val selection = selection(f)
            Sink().use { sink ->
                val result = assertIs<ImageSaveResult.Success>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                assertContentEquals(bytes, sink.contents())
                assertEquals(imageDigest(bytes), assertNotNull(result.image.proof).completeBodySha256)
                assertEquals(Size.Exact(bytes.size.toLong()), result.image.size)
                assertEquals(2, f.server.requests.size, "inspection then explicit separate Save fetch")
                assertFalse(sink.closed, "caller owns staging cleanup/publication")
            }
        }
    }

    @Test fun differentBytesOrDimensionsCannotSilentlyReplaceInspectedProof() {
        val representation = AtomicReference(raster())
        fixture(respond = { _, socket -> socket.reply(body = representation.get(), headers = mapOf("Content-Type" to "image/png")) }) { f ->
            val selection = selection(f)
            for (changed in listOf(raster(color = 0x112233), raster(40, 30))) {
                representation.set(changed)
                Sink().use { sink ->
                    transferFailure(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) }, ImageFailure.PROOF_MISMATCH)
                    assertFalse(sink.closed)
                }
            }
        }
    }

    @Test fun staleSourceAdapterValidityAndForeignItemFailBeforeSaveDnsOrWrites() = fixture { f ->
        val selection = selection(f)
        assertNotNull(f.store.resolve(selection))
        for (forged in listOf(selection.copy(source = imageSource(revision = 2)),
            selection.copy(identity = IMAGE_IDENTITY.copy(generation = 2)),
            selection.copy(validity = MediaInspectionValidity.processLocal()),
            selection.copy(renditionRef = MediaRenditionRef(MediaItemRef(IMAGE_IDENTITY.adapterId, "foreign-item"), "foreign-row")))) {
            val before = f.http.dnsCalls.get()
            Sink().use { sink ->
                transferFailure(runBlocking { f.saver.transfer(f.saver.newOperation(forged), sink) }, ImageFailure.STALE)
                assertEquals(0L, sink.bytes)
            }
            assertEquals(before, f.http.dnsCalls.get())
        }
    }

    @Test fun saveOversizeDeclaredBodyNeverWritesStagingOrBecomesSuccess() {
        val requests = AtomicInteger()
        fixture(respond = { _, socket ->
            if (requests.incrementAndGet() == 1) socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
            else socket.reply(declared = 20L * 1024 * 1024 + 1)
        }) { f ->
            val selection = selection(f)
            Sink().use { sink ->
                transferFailure(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) }, ImageFailure.BUDGET)
                assertEquals(0L, sink.bytes); assertFalse(sink.closed)
            }
        }
    }

    @Test fun saveTruncationAndInvalidEncodingNeverReturnPartialSuccess() {
        for (encoded in listOf(false, true)) {
            val requests = AtomicInteger()
            fixture(respond = { _, socket ->
                if (requests.incrementAndGet() == 1) socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
                else if (encoded) socket.reply(headers = mapOf("Content-Encoding" to "gzip"))
                else socket.reply(body = byteArrayOf(1, 2, 3), declared = 100)
            }) { f ->
                val selection = selection(f)
                Sink().use { sink ->
                    assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
                    assertFalse(sink.closed)
                }
            }
        }
    }

    @Test fun refetchedMimeSignatureAnimationAndDimensionsAreValidatedAgainBeforeSuccess() {
        for ((bytes, mime, expected) in listOf(
            Triple(raster(), "image/jpeg", ImageFailure.INVALID_IMAGE),
            Triple(animatedPng(), "image/png", ImageFailure.ANIMATED),
            Triple(bombPng(20000, 1), "image/png", ImageFailure.DIMENSIONS))) {
            val requests = AtomicInteger()
            fixture(respond = { _, socket ->
                if (requests.incrementAndGet() == 1) socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png"))
                else socket.reply(body = bytes, headers = mapOf("Content-Type" to mime))
            }) { f ->
                val selection = selection(f)
                Sink().use { sink ->
                    transferFailure(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) }, expected)
                    assertFalse(sink.closed, "validation failure leaves caller staging cleanup ownership intact")
                }
                assertEquals(2, f.server.requests.size)
            }
        }
    }

    @Test fun callerStagingIoFailureCannotReturnSuccessfulImageOrCloseCallerSink() = fixture { f ->
        val selection = selection(f)
        var closed = false
        val sink = object : OutputStream() {
            override fun write(value: Int) = throw java.io.IOException("synthetic staging failure")
            override fun write(buffer: ByteArray, offset: Int, length: Int) = throw java.io.IOException("synthetic staging failure")
            override fun close() { closed = true }
        }
        val failure = assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
        assertEquals(ImageFailure.STAGING, failure.reason)
        assertFalse(closed)
    }

    @Test fun stagingSinkEnospcReturnsSanitizedInsufficientSpaceWithoutClosingCallerSink() = fixture { f ->
        val selection = selection(f)
        var closed = false
        val sink = object : OutputStream() {
            override fun write(value: Int) = throw java.io.IOException("ENOSPC private diagnostics")
            override fun write(buffer: ByteArray, offset: Int, length: Int) = write(0)
            override fun close() { closed = true }
        }
        val failure = assertIs<ImageSaveResult.Failure>(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) })
        assertEquals("INSUFFICIENT_SPACE", failure.reason.name)
        assertNull(failure.statusCode)
        assertFalse(failure.toString().contains("private diagnostics"))
        assertFalse(closed)
    }

    @Test fun saveCancellationBeforeNetworkAndDuringWriteStopsWritesAndLateCompletion() = fixture { f ->
        val selection = selection(f)
        val cancelled = f.saver.newOperation(selection)
        f.saver.cancel(cancelled)
        val before = f.http.dnsCalls.get()
        Sink().use { sink ->
            transferFailure(runBlocking { f.saver.transfer(cancelled, sink) }, ImageFailure.CANCELLED)
            assertEquals(0L, sink.bytes)
        }
        assertEquals(before, f.http.dnsCalls.get())
        val operation = f.saver.newOperation(selection)
        Sink { f.saver.cancel(operation) }.use { sink ->
            transferFailure(runBlocking { f.saver.transfer(operation, sink) }, ImageFailure.CANCELLED)
            assertEquals(1, sink.writes)
            assertFalse(sink.closed)
        }
    }

    @Test fun replacementAfterResolveButBeforeFirstByteIsStaleWithoutDnsRequestOrWrite() = fixture { f ->
        val selection = selection(f)
        val requests = f.server.requests.size
        val dns = f.http.dnsCalls.get()
        f.saver.beforeAdmission = { f.inspector.newOperation(imageSource(revision = 2), IMAGE_IDENTITY) }
        Sink().use { sink ->
            transferFailure(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) }, ImageFailure.STALE)
            assertEquals(0L, sink.bytes); assertEquals(0, sink.writes)
        }
        assertEquals(dns, f.http.dnsCalls.get(), "stale Save never resolves")
        assertEquals(requests, f.server.requests.size, "stale Save never requests")
    }

    @Test fun replacementImmediatelyAfterAdmissionStopsTheTokenBeforeTheHttpCallStarts() = fixture { f ->
        val selection = selection(f)
        val requests = f.server.requests.size
        val dns = f.http.dnsCalls.get()
        val operation = f.saver.newOperation(selection)
        var terminalAfterReplacement: MediaHttpFailure? = null
        f.saver.afterAdmission = {
            f.inspector.newOperation(imageSource(revision = 2), IMAGE_IDENTITY)
            // Retirement stopped the admitted token inside its critical section, before any HTTP start.
            terminalAfterReplacement = operation.token?.checkActive()
        }
        Sink().use { sink ->
            transferFailure(runBlocking { f.saver.transfer(operation, sink) }, ImageFailure.STALE)
            assertEquals(0L, sink.bytes); assertEquals(0, sink.writes)
        }
        assertEquals(MediaHttpFailure.CANCELLED, terminalAfterReplacement)
        assertEquals(dns, f.http.dnsCalls.get(), "stopped token never resolves")
        assertEquals(requests, f.server.requests.size, "stopped token never starts a request")
    }

    @Test fun replacementWhileSaveResolvesCancelsItsTokenBeforeAnyRequestOrWrite() {
        var replaceDuringDns: (() -> Unit)? = null
        fixture(resolver = { _ -> replaceDuringDns?.also { replaceDuringDns = null }?.invoke(); listOf(PUBLIC_V4) }) { f ->
            val selection = selection(f)
            val requests = f.server.requests.size
            replaceDuringDns = { f.inspector.newOperation(imageSource(revision = 2), IMAGE_IDENTITY) }
            Sink().use { sink ->
                transferFailure(runBlocking { f.saver.transfer(f.saver.newOperation(selection), sink) }, ImageFailure.STALE)
                assertEquals(0L, sink.bytes); assertEquals(0, sink.writes)
            }
            assertNull(replaceDuringDns, "replacement really happened during the Save's own DNS")
            assertEquals(requests, f.server.requests.size, "retirement cancelled the admitted Save before its request")
        }
    }

    @Test fun replacingReferenceDuringFinalSinkWriteRejectsLateSaveSuccess() = fixture { f ->
        val selection = selection(f)
        val operation = f.saver.newOperation(selection)
        Sink { f.inspector.newOperation(imageSource(revision = 2), IMAGE_IDENTITY) }.use { sink ->
            transferFailure(runBlocking { f.saver.transfer(operation, sink) }, ImageFailure.STALE)
            assertEquals(1, sink.writes)
            assertFalse(sink.closed)
        }
    }
}
