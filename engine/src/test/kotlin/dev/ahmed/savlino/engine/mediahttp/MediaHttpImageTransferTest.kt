package dev.ahmed.savlino.engine.mediahttp

import dev.ahmed.savlino.domain.*
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.*

/** Real TLS consumer checks. Removing a guard must change wire traffic, bytes, or ownership effects. */
class MediaHttpImageTransferTest {
    companion object {
        private lateinit var identity: MediaHttpTlsIdentity
        private const val CAP = 20 * 1024 * 1024
        @JvmStatic @BeforeClass fun createIdentity() { identity = MediaHttpTlsIdentity() }
        @JvmStatic @AfterClass fun closeIdentity() { if (::identity.isInitialized) identity.close() }
    }

    private fun fixture(resolver: (String) -> List<InetAddress> = { listOf(PUBLIC_V4) },
        respond: (RecordedMediaRequest, SSLSocket) -> Unit = { _, socket -> socket.reply() },
        test: (MediaHttpHarness) -> Unit) {
        MediaHttpServer(identity) { request, socket ->
            if (request.target == "/control") socket.reply() else respond(request, socket)
        }.use { server -> MediaHttpHarness(identity, server, resolver).use(test) }
    }

    private fun image(path: String = "/image", host: String = "media.test", context: MediaHttpTarget? = null) =
        PublicMediaRequest("https://$host$path", MediaHttpPurpose.IMAGE, context)

    private fun binding(url: String = "https://www.linkedin.com/posts/synthetic-activity-123", revision: Long = 1,
        generation: Long = 1, validity: MediaInspectionValidity = MediaInspectionValidity.processLocal()) =
        MediaHttpSaveBinding(LinkRevision(checkNotNull(LinkInput.parse(url)), revision),
            MediaAdapterIdentity("public-image", 1, "fixture", generation), validity)

    private fun post(h: MediaHttpHarness, binding: MediaHttpSaveBinding): MediaHttpPostContext {
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            val page = assertSuccess(h.client.fetchLinkedInPage(token,
                "https://www.linkedin.com/posts/synthetic-activity-123?tracking=synthetic"))
            page.use {
                return assertNotNull(h.client.rememberLinkedInPost(token, it.target, binding),
                    "completed validated post must issue an opaque stored context")
            }
        }
    }

    private fun success(result: MediaHttpImageTransferResult): MediaHttpImageTransferResult.Success =
        assertIs<MediaHttpImageTransferResult.Success>(result, "complete streamed transfer must succeed")

    private fun failure(result: MediaHttpImageTransferResult, reason: MediaHttpFailure, status: Int? = null) {
        val failed = assertIs<MediaHttpImageTransferResult.Failure>(result)
        assertEquals(reason, failed.reason)
        if (status != null) assertEquals(status, failed.statusCode)
    }

    /** Real private staging file, fixed chunks, independent digest; publication/cleanup belongs to caller. */
    private class StagingSink(private val onWrite: () -> Unit = {}) : OutputStream(), Closeable {
        val path = Files.createTempFile("media-save-", ".part")
        private val file = Files.newOutputStream(path)
        var bytes = 0L; private set
        var largestWrite = 0; private set
        var wasClosed = false; private set
        override fun write(value: Int) { error("bulk streaming required") }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!wasClosed)
            file.write(buffer, offset, length)
            bytes += length
            largestWrite = maxOf(largestWrite, length)
            onWrite()
        }
        fun digest(): String {
            file.flush()
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        override fun close() { if (!wasClosed) { wasClosed = true; file.close(); Files.deleteIfExists(path) } }
    }

    /** Generate the response without a full-body array; gate after the first chunk to prove early sink writes. */
    private fun SSLSocket.streamBody(length: Int, declared: Long? = length.toLong(), chunked: Boolean = false,
        afterFirst: () -> Unit = {}) {
        val output = outputStream
        val framing = if (chunked) "Transfer-Encoding: chunked\r\n" else
            declared?.let { "Content-Length: $it\r\n" }.orEmpty()
        output.write(("HTTP/1.1 200 Fixture\r\nContent-Type: image/jpeg\r\n" + framing +
            "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
        val buffer = ByteArray(8192) { 42 }
        var sent = 0
        while (sent < length) {
            val count = minOf(buffer.size, length - sent)
            if (chunked) output.write("${count.toString(16)}\r\n".toByteArray())
            output.write(buffer, 0, count)
            if (chunked) output.write("\r\n".toByteArray())
            output.flush()
            if (sent == 0) afterFirst()
            sent += count
        }
        if (chunked) output.write("0\r\n\r\n".toByteArray())
        output.flush()
    }

    @Test fun saveStreamsTwentyMiBBeforeEofWithMeasuredDigestAndCallerOwnedSink() {
        val firstWrite = CountDownLatch(1)
        val bodyReleased = CountDownLatch(1)
        fixture(respond = { _, socket ->
            socket.streamBody(CAP) {
                if (firstWrite.await(3, TimeUnit.SECONDS)) bodyReleased.countDown()
            }
        }) { h ->
            h.control()
            StagingSink { firstWrite.countDown() }.use { sink ->
                h.client.newSaveOperation().use { token ->
                    val result = success(h.client.transferImage(token, image(), sink))
                    assertEquals(0L, bodyReleased.count, "sink consumed a chunk before origin sent the full body")
                    assertEquals(CAP.toLong(), sink.bytes)
                    assertEquals(CAP.toLong(), result.measuredBytes)
                    assertEquals("d1ce7c80d977d445718429515b0e7e587880529d81dffde0d734030af6386e64", result.completeBodySha256)
                    assertEquals(sink.digest(), result.completeBodySha256)
                    assertEquals("image/jpeg", result.mimeType)
                    assertTrue(sink.largestWrite in 1..8192, "fixed bounded sink chunks")
                    assertFalse(sink.wasClosed, "transport never closes caller staging sink")
                    assertEquals(CAP.toLong(), token.budget.snapshot().consumedBytes)
                    assertEquals(1, token.budget.snapshot().requests)
                }
            }
        }
    }

    @Test fun saveAcceptsDeclaredChunkedAndUnknownLengthWithoutInventingImageValidation() {
        for (framing in listOf("declared", "chunked", "unknown")) fixture(respond = { _, socket ->
            socket.streamBody(24577, declared = if (framing == "unknown") null else 24577,
                chunked = framing == "chunked")
        }) { h ->
            h.control()
            StagingSink().use { sink -> h.client.newSaveOperation().use { token ->
                val result = success(h.client.transferImage(token, image(), sink))
                assertEquals(24577L, result.measuredBytes)
                assertEquals("7c5441e0bbf2dd701c46bbc1a682ed63dbe82e93eab5539c29aa7647121c598c", result.completeBodySha256)
                assertEquals(sink.digest(), result.completeBodySha256)
                assertEquals(24577L, sink.bytes)
            } }
        }
    }

    @Test fun saveDeclaredOversizeRejectsBeforeAnySinkWriteOrBodyRead() = fixture(respond = { _, socket ->
        socket.reply(declared = CAP.toLong() + 1)
    }) { h ->
        h.control()
        StagingSink().use { sink -> h.client.newSaveOperation().use { token ->
            failure(h.client.transferImage(token, image(), sink), MediaHttpFailure.BODY_LIMIT)
            assertEquals(0L, sink.bytes)
            assertEquals(0L, token.budget.snapshot().consumedBytes)
            assertFalse(sink.wasClosed)
        } }
    }

    @Test fun saveUnknownOrChunkedCapPlusOneStopsConsumptionAndNeverSucceeds() {
        for (chunked in listOf(false, true)) fixture(respond = { _, socket ->
            socket.streamBody(CAP + 1, declared = null, chunked = chunked)
        }) { h ->
            h.control()
            StagingSink().use { sink -> h.client.newSaveOperation().use { token ->
                failure(h.client.transferImage(token, image(), sink), MediaHttpFailure.BODY_LIMIT)
                assertEquals(CAP.toLong() + 1, token.budget.snapshot().consumedBytes)
                assertTrue(sink.bytes in 1..CAP.toLong(), "only bounded partial staging bytes, no oversize success")
                assertFalse(sink.wasClosed)
            } }
        }
    }

    @Test fun saveTruncatedDeclaredBodyAndMalformedChunkCannotReturnSuccess() {
        for (malformed in listOf(false, true)) fixture(respond = { _, socket ->
            if (malformed) {
                socket.outputStream.write(("HTTP/1.1 200 Fixture\r\nContent-Type: image/jpeg\r\n" +
                    "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nabc").toByteArray())
                socket.outputStream.flush()
            } else socket.reply(body = byteArrayOf(1, 2, 3), declared = 9000)
        }) { h ->
            h.control()
            StagingSink().use { sink -> h.client.newSaveOperation().use { token ->
                failure(h.client.transferImage(token, image(), sink), MediaHttpFailure.PARTIAL_BODY)
                assertTrue(sink.bytes < 9000)
                assertFalse(sink.wasClosed)
            } }
        }
    }

    @Test fun saveRejectsHttpPartialAndUnsupportedEncodingBeforeSinkWrites() {
        for ((status, encoding, reason) in listOf(Triple(403, "identity", MediaHttpFailure.HTTP_STATUS),
            Triple(206, "identity", MediaHttpFailure.HTTP_STATUS), Triple(200, "gzip", MediaHttpFailure.ENCODING),
            Triple(200, "identity, br", MediaHttpFailure.ENCODING))) fixture(respond = { _, socket ->
            socket.reply(status, headers = mapOf("Content-Encoding" to encoding))
        }) { h ->
            h.control()
            StagingSink().use { sink -> h.client.newSaveOperation().use { token ->
                failure(h.client.transferImage(token, image(), sink), reason, if (status != 200) status else null)
                assertEquals(0L, sink.bytes)
                assertEquals(0L, token.budget.snapshot().consumedBytes)
            } }
        }
    }

    @Test fun saveCancellationDuringSinkWriteStopsLaterChunksAndLateSuccess() = fixture(respond = { _, socket ->
        socket.streamBody(32768)
    }) { h ->
        h.control()
        h.client.newSaveOperation().use { token ->
            val writes = AtomicInteger()
            StagingSink { if (writes.incrementAndGet() == 1) h.client.cancel(token.id) }.use { sink ->
                failure(h.client.transferImage(token, image(), sink), MediaHttpFailure.CANCELLED)
                assertEquals(1, writes.get(), "no writes after cancellation linearization")
                assertTrue(sink.bytes in 1..8192L)
                assertFalse(sink.wasClosed)
                assertEquals(token.budget.snapshot().socketsCreated, token.budget.snapshot().socketsClosed)
            }
        }
    }

    @Test fun saveCancellationBeforeRegistrationAndRetiredTokenCannotStartNetwork() = fixture { h ->
        h.control()
        StagingSink().use { sink -> h.client.newSaveOperation().use { token ->
            h.client.cancel(token.id)
            val before = h.dnsCalls.get()
            repeat(2) { failure(h.client.transferImage(token, image(), sink), MediaHttpFailure.CANCELLED) }
            assertEquals(before, h.dnsCalls.get())
            assertEquals(0L, sink.bytes)
        } }
    }

    @Test fun saveCancellationDuringDnsHeadersOrBodyClosesResourcesAndStopsWrites() {
        for (phase in listOf("dns", "headers", "body")) {
            val reached = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture(resolver = { host ->
                if (phase == "dns" && host == "other.test") {
                    reached.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
                listOf(PUBLIC_V4)
            }, respond = { _, socket ->
                if (phase == "headers") {
                    reached.countDown()
                    release.await(3, TimeUnit.SECONDS)
                    socket.reply()
                } else socket.streamBody(32768) {
                    reached.countDown()
                    release.await(3, TimeUnit.SECONDS)
                }
            }) { h ->
                h.control()
                val executor = Executors.newSingleThreadExecutor()
                try {
                    StagingSink().use { sink -> h.client.newSaveOperation().use { token ->
                        val future = executor.submit<MediaHttpImageTransferResult> {
                            h.client.transferImage(token, image(host = "other.test"), sink)
                        }
                        try {
                            assertTrue(reached.await(3, TimeUnit.SECONDS), "Save reached the cancellable phase")
                            h.client.cancel(token.id)
                            val bytesAtCancelReturn = sink.bytes
                            release.countDown()
                            failure(future.get(3, TimeUnit.SECONDS), MediaHttpFailure.CANCELLED)
                            assertEquals(bytesAtCancelReturn, sink.bytes, "no writes after cancel returned")
                            assertFalse(sink.wasClosed)
                            assertEquals(token.budget.snapshot().socketsCreated, token.budget.snapshot().socketsClosed)
                        } finally { release.countDown(); future.cancel(true) }
                    } }
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                    assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "Save worker stopped")
                }
            }
        }
    }

    @Test fun saveFiveSecondIdleTimeoutStopsAnIncompleteBody() {
        val release = CountDownLatch(1)
        try {
            fixture(respond = { _, socket ->
                socket.streamBody(32768) { release.await(10, TimeUnit.SECONDS) }
            }) { h ->
                h.control()
                StagingSink().use { sink -> h.client.newSaveOperation().use { token ->
                    val started = System.nanoTime()
                    failure(h.client.transferImage(token, image(), sink), MediaHttpFailure.TIMEOUT)
                    val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
                    assertTrue(elapsed in 4000..9000, "idle body expires without waiting for whole Save deadline")
                    assertTrue(sink.bytes in 1..8192L)
                    assertFalse(sink.wasClosed)
                    release.countDown()
                } }
            }
        } finally { release.countDown() }
    }

    @Test fun saveSinkFailureReturnsTypedFailureAndLeavesSinkCleanupToCaller() = fixture(respond = { _, socket ->
        socket.streamBody(16384)
    }) { h ->
        h.control()
        val writes = AtomicInteger()
        val sink = object : OutputStream() {
            override fun write(value: Int) = error("bulk writes required")
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes.incrementAndGet(); throw IOException("synthetic sink failure")
            }
            override fun close() = error("caller owns sink closure")
        }
        h.client.newSaveOperation().use { token ->
            failure(h.client.transferImage(token, image(), sink), MediaHttpFailure.NETWORK)
            assertEquals(1, writes.get())
            assertEquals(token.budget.snapshot().socketsCreated, token.budget.snapshot().socketsClosed)
        }
    }

    @Test fun saveGetsOneHundredTwentySecondDeadlineWhileAutomaticKeepsFifteenAndIdleFive() = fixture { h ->
        h.control()
        h.client.newSaveOperation().use { save ->
            h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { automatic ->
                val saveClient = assertNotNull(h.client.configuredClient(save))
                val automaticClient = assertNotNull(h.client.configuredClient(automatic))
                assertTrue(saveClient.callTimeoutMillis in 110_000..120_000, "Save gets its own whole deadline")
                assertTrue(automaticClient.callTimeoutMillis in 1..15_000)
                assertEquals(5000, saveClient.connectTimeoutMillis)
                assertEquals(5000, saveClient.readTimeoutMillis)
                // Shift only the private start timestamp: exercise expiry without wall-clock waiting.
                for (token in listOf(save, automatic)) {
                    val start = OperationToken::class.java.getDeclaredField("started").apply { isAccessible = true }
                    start.setLong(token, start.getLong(token) - TimeUnit.SECONDS.toNanos(16))
                }
                assertNull(save.checkActive(), "Save survives the old automatic whole limit")
                assertEquals(MediaHttpFailure.TIMEOUT, automatic.checkActive())
            }
        }
    }

    @Test fun automaticFetchRejectsSaveTokensBeforeDnsOrSocketCreation() = fixture { h ->
        h.control()
        for (purpose in listOf(MediaHttpPurpose.IMAGE, MediaHttpPurpose.PAGE)) {
            h.client.newSaveOperation().use { save ->
                val dnsBefore = h.dnsCalls.get()
                val connectsBefore = h.connects.get()
                assertFailure(h.client.fetch(save, PublicMediaRequest("https://media.test/automatic", purpose)),
                    MediaHttpFailure.INVALID_CONTEXT)
                assertEquals(dnsBefore, h.dnsCalls.get(), "Save token never enters automatic DNS")
                assertEquals(connectsBefore, h.connects.get(), "Save token never opens an automatic socket")
                assertEquals(0, save.budget.snapshot().requests)
                assertEquals(0, save.budget.snapshot().socketsCreated)
            }
        }
    }

    @Test fun linkedinPageFetchRejectsSaveTokensBeforeDnsOrSocketCreation() = fixture { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { automatic ->
            assertSuccess(h.client.fetchLinkedInPage(automatic, "https://www.linkedin.com/posts/allowed")).close()
        }
        h.client.newSaveOperation().use { save ->
            val dnsBefore = h.dnsCalls.get()
            val connectsBefore = h.connects.get()
            assertFailure(h.client.fetchLinkedInPage(save, "https://www.linkedin.com/posts/allowed"),
                MediaHttpFailure.INVALID_CONTEXT)
            assertEquals(dnsBefore, h.dnsCalls.get(), "Save token never enters page DNS")
            assertEquals(connectsBefore, h.connects.get(), "Save token never opens a page socket")
            assertEquals(0, save.budget.snapshot().requests)
            assertEquals(0, save.budget.snapshot().socketsCreated)
        }
    }

    @Test fun automaticStillRejectsBodiesAboveTwoMiBWhileSaveStreamsSameRepresentation() = fixture(respond = { _, socket ->
        socket.streamBody(2 * 1024 * 1024 + 1)
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { automatic ->
            assertFailure(h.client.fetch(automatic, image()), MediaHttpFailure.BODY_LIMIT)
            assertEquals(0L, automatic.budget.snapshot().consumedBytes)
        }
        StagingSink().use { sink -> h.client.newSaveOperation().use { save ->
            assertEquals(2097153L, success(h.client.transferImage(save, image(), sink)).measuredBytes)
            assertEquals(2097153L, sink.bytes)
        } }
    }

    @Test fun linkedinSignupAndLoginRedirectsAreNamedLoginWalls() {
        fun url(value: String) = checkNotNull(MediaHttpPolicy.parse(value))
        // A members-only post sends logged-out visitors to /signup/cold-join (owner's link, 2026-10-21).
        listOf("https://www.linkedin.com/signup/cold-join", "https://www.linkedin.com/authwall", "https://www.linkedin.com/login",
            "https://linkedin.com/uas/login").forEach { assertTrue(MediaHttpPolicy.isLinkedInLoginWall(url(it)), it) }
        listOf("https://www.linkedin.com/posts/synthetic-activity-123", "https://other.test/login",
            "https://www.linkedin.com/feed/update/urn:li:activity:123").forEach { assertFalse(MediaHttpPolicy.isLinkedInLoginWall(url(it)), it) }
    }

    @Test fun linkedinPageTransitionsAllowOnlyShortlinkToPostThenPostsAndActivity() {
        fun url(value: String) = checkNotNull(MediaHttpPolicy.parse(value))
        assertTrue(MediaHttpPolicy.allowsLinkedInPageHop(url("https://lnkd.in/p/fixture"),
            url("https://www.linkedin.com/posts/synthetic-activity-123")))
        assertTrue(MediaHttpPolicy.allowsLinkedInPageHop(url("https://www.linkedin.com/posts/synthetic-activity-123"),
            url("https://www.linkedin.com/feed/update/urn:li:activity:123")))
        for ((from, to) in listOf("https://lnkd.in/p/fixture" to "https://lnkd.in/p/other",
            "https://www.linkedin.com/posts/fixture" to "https://lnkd.in/p/fixture",
            "https://www.linkedin.com/posts/fixture" to "https://other.test/posts/fixture",
            "https://www.linkedin.com/posts/fixture" to "https://www.linkedin.com/login",
            "https://www.linkedin.com/posts/fixture" to "https://www.linkedin.com/posts/",
            "https://www.linkedin.com/posts/fixture" to "https://www.linkedin.com/feed/update/urn:li:activity:abc")) {
            assertFalse(MediaHttpPolicy.allowsLinkedInPageHop(url(from), url(to)))
        }
        // Post A can never redirect into post B; only a shortlink establishes which post is fetched.
        for ((from, to) in listOf(
            "https://www.linkedin.com/posts/synthetic-activity-123" to "https://www.linkedin.com/posts/other-activity-999",
            "https://www.linkedin.com/posts/synthetic-activity-123" to "https://www.linkedin.com/feed/update/urn:li:activity:999",
            "https://www.linkedin.com/feed/update/urn:li:activity:123" to "https://www.linkedin.com/feed/update/urn:li:activity:999",
            "https://www.linkedin.com/feed/update/urn:li:activity:123" to "https://www.linkedin.com/posts/other-activity-999",
            "https://www.linkedin.com/posts/start" to "https://www.linkedin.com/posts/final")) {
            assertFalse(MediaHttpPolicy.allowsLinkedInPageHop(url(from), url(to)), "post identity must survive redirects")
        }
        assertTrue(MediaHttpPolicy.allowsLinkedInPageHop(url("https://www.linkedin.com/feed/update/urn:li:activity:123"),
            url("https://www.linkedin.com/posts/synthetic-activity-123")))
        assertTrue(MediaHttpPolicy.allowsLinkedInPageHop(url("https://www.linkedin.com/posts/start"),
            url("https://www.linkedin.com/posts/start/")))
    }

    @Test fun linkedinPageOffProviderAndInvalidPathRedirectsStopBeforeNextDns() {
        for (location in listOf("https://other.test/posts/fixture", "https://lnkd.in/p/fixture",
            "/login", "/signup/cold-join", "/posts/", "/feed/update/urn:li:activity:abc", "/posts/elsewhere",
            "/feed/update/urn:li:activity:999")) fixture(respond = { request, socket ->
            if (request.target == "/posts/allowed") socket.reply(headers = mapOf("Content-Type" to "text/html"))
            else socket.reply(302, headers = mapOf("Location" to location))
        }) { h ->
            h.control()
            h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertSuccess(h.client.fetchLinkedInPage(token, "https://www.linkedin.com/posts/allowed")).close()
            }
            h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                val before = h.dnsCalls.get()
                // LinkedIn's own login or sign-up wall is named, never followed.
                assertFailure(h.client.fetchLinkedInPage(token, "https://www.linkedin.com/posts/start"),
                    if (location == "/login" || location == "/signup/cold-join") MediaHttpFailure.LOGIN_REQUIRED else MediaHttpFailure.INVALID_CONTEXT)
                assertEquals(before + 1, h.dnsCalls.get(), "next forbidden hop never resolves")
                assertEquals(1, token.budget.snapshot().requests)
            }
        }
    }

    @Test fun linkedinPageAndPreviewKeepSharedThreeHopFiveRequestBudgetAndResolvedPostReferer() = fixture(respond = { request, socket ->
        when (request.target) {
            "/p/start" -> socket.reply(302, headers = mapOf("Location" to "https://www.linkedin.com/posts/synthetic-activity-123"))
            "/posts/synthetic-activity-123" -> socket.reply(302,
                headers = mapOf("Location" to "/feed/update/urn:li:activity:123?tracking=synthetic"))
            "/asset" -> socket.reply(302, headers = mapOf("Location" to "/asset-final?sig=Ab%2fCD%2B%3d"))
            else -> socket.reply()
        }
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            val context = assertSuccess(h.client.fetchLinkedInPage(token, "https://lnkd.in/p/start")).use { it.target }
            assertSuccess(h.client.fetch(token, image("/asset", "media.licdn.com", context))).close()
            assertEquals(5, token.budget.snapshot().requests)
            assertEquals(3, token.budget.snapshot().redirects)
            val assets = h.server.requests.filter { it.headers["host"] == "media.licdn.com" }
            assertEquals(2, assets.size)
            assertTrue(assets.all { it.headers["referer"] == "https://www.linkedin.com/feed/update/urn:li:activity:123" })
            assertEquals("/asset-final?sig=Ab%2fCD%2B%3d", assets.last().target)
        }
    }

    @Test fun freshSaveUsesStoredCanonicalPostAfterInspectionRetiresAndPreservesSignedAssetBytes() = fixture { h ->
        h.control()
        val binding = binding()
        val stored = post(h, binding)
        h.client.newSaveOperation().use { token ->
            val context = assertNotNull(h.client.bindSaveContext(token, stored, binding))
            StagingSink().use { sink ->
                success(h.client.transferImage(token, image("/asset?sig=Ab%2fCD%2B%3d", "media.licdn.com", context), sink))
                val request = h.server.requests.last()
                assertEquals("https://www.linkedin.com/posts/synthetic-activity-123", request.headers["referer"])
                assertEquals("/asset?sig=Ab%2fCD%2B%3d", request.target)
                for (name in listOf("cookie", "authorization", "proxy-authorization", "range")) assertNull(request.headers[name])
            }
        }
    }

    @Test fun freshSaveRejectsOtherSourceGenerationValidityAndForgedContextBeforeDns() = fixture { h ->
        h.control()
        val original = binding()
        val stored = post(h, original)
        for (current in listOf(binding(revision = 2, validity = original.validity),
            binding(url = "https://www.linkedin.com/posts/other", validity = original.validity),
            binding(generation = 2, validity = original.validity), binding())) {
            h.client.newSaveOperation().use { token ->
                val before = h.dnsCalls.get()
                assertNull(h.client.bindSaveContext(token, stored, current))
                val forged = MediaHttpTarget("https://www.linkedin.com/posts/synthetic-activity-123", token.id, MediaHttpPurpose.PAGE)
                StagingSink().use { sink ->
                    failure(h.client.transferImage(token, image(host = "media.licdn.com", context = forged), sink),
                        MediaHttpFailure.INVALID_CONTEXT)
                    assertEquals(0L, sink.bytes)
                }
                assertEquals(before, h.dnsCalls.get())
            }
        }
    }

    @Test fun storedPostContextCannotBeForgedOrImportedFromAnotherClient() = fixture { h ->
        h.control()
        val binding = binding()
        val genuine = post(h, binding)
        val forged = MediaHttpPostContext("https://www.linkedin.com/posts/synthetic-activity-123", binding)
        MediaHttpClient.forTesting(h.environment).use { other ->
            other.newSaveOperation().use { token ->
                val before = h.dnsCalls.get()
                assertNull(other.bindSaveContext(token, genuine, binding))
                assertNull(other.bindSaveContext(token, forged, binding))
                assertEquals(before, h.dnsCalls.get())
            }
        }
        h.client.newSaveOperation().use { token -> assertNull(h.client.bindSaveContext(token, forged, binding)) }
    }

    @Test fun forgettingOneOfSixtyFourStoredPostsAdmitsANewOneAndForgottenCannotBindForSave() = fixture { h ->
        h.control()
        // Strongly held here, so only an explicit forget (never GC) can free a slot.
        val stored = (0 until 64).map { index -> binding(revision = index.toLong()).let { it to post(h, it) } }
        val newest = binding(revision = 64)
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            assertSuccess(h.client.fetchLinkedInPage(token, "https://www.linkedin.com/posts/synthetic-activity-123")).use { page ->
                assertNull(h.client.rememberLinkedInPost(token, page.target, newest), "64 stored contexts fill the cap")
                h.client.forgetLinkedInPost(stored.first().second)
                assertNotNull(h.client.rememberLinkedInPost(token, page.target, newest), "forget frees exactly one slot")
                assertNull(h.client.rememberLinkedInPost(token, page.target, binding(revision = 65)), "cap applies again")
            }
        }
        val before = h.dnsCalls.get()
        h.client.newSaveOperation().use { token ->
            assertNull(h.client.bindSaveContext(token, stored.first().second, stored.first().first))
        }
        h.client.newSaveOperation().use { token ->
            assertNotNull(h.client.bindSaveContext(token, stored[1].second, stored[1].first), "unforgotten control still binds")
        }
        assertEquals(before, h.dnsCalls.get())
    }

    @Test fun rememberedPostRequiresAnIssuedPageTargetRatherThanCallerCanonicalText() = fixture { h ->
        h.control()
        val binding = binding()
        post(h, binding) // Positive factory control before forged-target rejection.
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            val before = h.dnsCalls.get()
            for (url in listOf("https://www.linkedin.com/posts/synthetic-activity-123",
                "https://www.linkedin.com/login", "https://other.test/posts/fixture")) {
                val forged = MediaHttpTarget(url, token.id, MediaHttpPurpose.PAGE)
                assertNull(h.client.rememberLinkedInPost(token, forged, binding), "target must have actual page provenance")
            }
            assertEquals(before, h.dnsCalls.get())
        }
    }

    @Test fun saveContextOffOriginRedirectFailsBeforeNextDnsWithoutLeakingReferer() = fixture(respond = { request, socket ->
        if (request.target == "/asset") socket.reply(302, headers = mapOf("Location" to "https://other.test/final"))
        else socket.reply()
    }) { h ->
        h.control()
        val binding = binding()
        val stored = post(h, binding)
        h.client.newSaveOperation().use { token ->
            val context = assertNotNull(h.client.bindSaveContext(token, stored, binding))
            val before = h.dnsCalls.get()
            StagingSink().use { sink ->
                failure(h.client.transferImage(token, image("/asset", "media.licdn.com", context), sink), MediaHttpFailure.INVALID_CONTEXT)
                assertEquals(before + 1, h.dnsCalls.get())
                assertEquals(0L, sink.bytes)
                assertFalse(h.server.requests.any { it.target == "/final" })
            }
        }
    }
}
