package dev.ahmed.savlino.engine.mediahttp

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import okhttp3.ConnectionSpec
import okhttp3.Protocol
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.*

class MediaHttpClientTest {
    companion object {
        private lateinit var identity: MediaHttpTlsIdentity
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

    private fun request(path: String = "/image", purpose: MediaHttpPurpose = MediaHttpPurpose.IMAGE,
        host: String = "media.test", context: MediaHttpTarget? = null) =
        PublicMediaRequest("https://$host$path", purpose, context)

    @Test fun eachSiteEnforcesItsOwnPageCapAndNearCapBodiesTransferOwnership() {
        val cases = listOf(Triple("facebook", "https://www.facebook.com/synthetic/posts/123", 10 * 1024 * 1024),
            Triple("linkedin", "https://www.linkedin.com/posts/synthetic-activity-123/", 6 * 1024 * 1024),
            Triple("instagram", "https://www.instagram.com/p/SynthPost01/", 3 * 1024 * 1024),
            Triple("x", "https://x.com/synthetic/status/1790000000000000001", 512 * 1024))
        for ((site, url, cap) in cases) for (oversize in listOf(false, true)) {
            fixture(respond = { _, socket ->
                if (oversize) socket.reply(declared = cap.toLong() + 1)
                else socket.reply(body = ByteArray(cap - 1) { 42 }, chunked = true)
            }) { h ->
                h.client.newOperation(if (site == "facebook") MediaHttpOperation.PAGES_AND_IMAGE else MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                    val result = when (site) {
                        "facebook" -> h.client.fetchFacebookPage(token, url)
                        "linkedin" -> h.client.fetchLinkedInPage(token, url)
                        "instagram" -> h.client.fetchInstagramPost(token, dev.ahmed.savlino.engine.instagram.InstagramPostIdentity.parse(url)!!)
                        else -> h.client.fetchXPost(token, dev.ahmed.savlino.engine.x.XPostIdentity.parse(url)!!)
                    }
                    if (oversize) assertFailure(result, MediaHttpFailure.BODY_LIMIT)
                    else {
                        val response = assertSuccess(result)
                        val bytes = response.takeBody()
                        assertEquals(cap - 1, bytes.length)
                        assertEquals(42.toByte(), bytes.bytes[bytes.length - 1])
                        assertFailsWith<IOException> { response.takeBody() }
                    }
                }
            }
        }
    }

    @Test fun nearCapImageTransfersOnlyMeasuredBytesOnce() = fixture(respond = { _, socket ->
        socket.reply(body = ByteArray(2 * 1024 * 1024 - 1) { 73 }, chunked = true)
    }) { h ->
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            val response = assertSuccess(h.client.fetch(token, request()))
            assertEquals(2 * 1024 * 1024 - 1, response.takeBody().length)
            assertFailsWith<IOException> { response.takeBody() }
        }
    }

    @Test fun fragmentsNeverReachTheWireIncludingRedirectLocations() = fixture(respond = { request, socket ->
        if (request.target == "/image?sig=%2f") socket.reply(302, headers = mapOf("Location" to "/final?sig=%2f#part"))
        else socket.reply()
    }) { h ->
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            assertSuccess(h.client.fetch(token, request("/image?sig=%2f#part"))).close()
            assertEquals(listOf("/image?sig=%2f", "/final?sig=%2f"), h.server.requests.map { it.target })
        }
    }

    @Test fun regionalAndBareLinkedinEntriesFetchOnlyWww() {
        for (host in listOf("ae.linkedin.com", "linkedin.com")) fixture { h ->
            h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                assertSuccess(h.client.fetchLinkedInPage(token, "https://$host/posts/synthetic-activity-123/")).close()
                assertEquals("www.linkedin.com", h.server.requests.single().headers["host"])
            }
        }
    }

    @Test fun realConfiguredClientReturnsCompleteBytesAndClosesOwnedResources() = fixture { h ->
        h.control()
        val token = h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE)
        val result = h.client.fetch(token, request())
        val response = assertSuccess(result)
        val body = response.takeBody()
        assertEquals(3, body.length)
        assertContentEquals(byteArrayOf(10, 20, 30), body.bytes.copyOf(body.length))
        assertEquals(3L, response.measuredBytes)
        assertEquals("image/jpeg", response.mimeType)
        response.close()
        response.close()
        assertFailsWith<IOException> { response.takeBody() }
        token.close()
        val counters = token.budget.snapshot()
        assertEquals(1, counters.requests)
        assertEquals(3L, counters.consumedBytes)
        assertTrue(counters.socketsCreated >= 1, "actual configured client created a socket")
        assertEquals(counters.socketsCreated, counters.socketsClosed, "operation closes all owned sockets")
    }

    @Test fun productionFactoryRetainsBothGuardsAndDirectModernTlsConfiguration() {
        fixture { it.control() }
        MediaHttpClient.create().use { client ->
            client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                val configured = assertNotNull(client.configuredClient(token), "production client built")
                assertIs<PublicAddressDns>(configured.dns)
                assertIs<GuardedMediaSocketFactory>(configured.socketFactory)
                assertEquals(Proxy.NO_PROXY, configured.proxy)
                assertEquals(listOf(Protocol.HTTP_1_1), configured.protocols)
                assertEquals(listOf(ConnectionSpec.MODERN_TLS), configured.connectionSpecs)
                assertFalse(configured.followRedirects)
                assertFalse(configured.followSslRedirects)
                assertFalse(configured.retryOnConnectionFailure)
                assertFalse(configured.fastFallback)
                assertNull(configured.cache)
                assertTrue(configured.interceptors.isEmpty(), "no arbitrary application interceptor")
                assertEquals(5000, configured.connectTimeoutMillis)
                assertEquals(5000, configured.readTimeoutMillis)
                assertTrue(configured.callTimeoutMillis in 1..15_000, "whole remaining timeout")
            }
        }
    }

    @Test fun systemProxyPropertiesCannotRerouteTheClient() {
        val oldHost = System.getProperty("https.proxyHost")
        val oldPort = System.getProperty("https.proxyPort")
        try {
            System.setProperty("https.proxyHost", "127.0.0.1")
            System.setProperty("https.proxyPort", "1")
            fixture { h -> h.control(); assertEquals(1, h.connects.get()) }
        } finally {
            if (oldHost == null) System.clearProperty("https.proxyHost") else System.setProperty("https.proxyHost", oldHost)
            if (oldPort == null) System.clearProperty("https.proxyPort") else System.setProperty("https.proxyPort", oldPort)
        }
    }

    @Test fun everyForbiddenOrMixedResolverAnswerIsRejectedBeforeAnySocketCreation() {
        val answers = listOf(listOf(address("127.0.0.1")), listOf(address("10.0.0.1")),
            listOf(address("169.254.169.254")), listOf(address("::1")), listOf(address("2001:db8::1")),
            listOf(PUBLIC_V4, address("192.168.1.1")), listOf(address("127.0.0.1"), PUBLIC_V4),
            listOf(PUBLIC_V4, address("64:ff9b::7f00:1")), listOf(address("::ffff:10.0.0.1")))
        for (answer in answers) fixture(resolver = { host -> if (host == "other.test") answer else listOf(PUBLIC_V4) }) { h ->
            h.control()
            val before = h.connects.get()
            h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                assertFailure(h.client.fetch(token, request(host = "other.test")), MediaHttpFailure.ADDRESS_REJECTED)
                assertEquals(0, token.budget.snapshot().socketsCreated, "full DNS set checked before sockets")
                assertEquals(before, h.connects.get())
                assertEquals(1, h.server.requests.size, "only paired control reached server")
            }
        }
    }

    @Test fun emptyAndExcessiveDnsAnswersCannotCreateSockets() {
        for (answers in listOf(emptyList(), List(17) { PUBLIC_V4 })) {
            fixture(resolver = { host -> if (host == "other.test") answers else listOf(PUBLIC_V4) }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                    assertFailure(h.client.fetch(token, request(host = "other.test")), MediaHttpFailure.ADDRESS_REJECTED)
                    assertEquals(0, token.budget.snapshot().socketsCreated)
                }
            }
        }
    }

    @Test fun publicAPlusPublicWellKnownNat64AnswerPassesTheActualClientFilter() {
        fixture(resolver = { listOf(PUBLIC_V4, address("64:ff9b::808:808")) }) { h ->
            h.control()
            assertEquals(1, h.connects.get())
            assertEquals(1, h.server.requests.size)
        }
    }

    @Test fun rawLiteralAndUnsafeRedirectVectorsNeverReachDnsOrSockets() {
        val targets = listOf("https://127.1/", "https://2130706433/", "https://10.1/",
            "https://[::ffff:7f00:1]/", "https://0x7f000001/", "https://media.123/",
            "http://media.test/image", "https://media.test:444/image", "https://u:p@media.test/image")
        for (target in targets) fixture(respond = { _, socket -> socket.reply(302,
            headers = mapOf("Location" to target)) }) { h ->
            h.control()
            val dnsBefore = h.dnsCalls.get()
            h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                assertFailure(h.client.fetch(token, PublicMediaRequest(target, MediaHttpPurpose.IMAGE)), MediaHttpFailure.INVALID_URL)
                assertEquals(0, token.budget.snapshot().socketsCreated)
                assertEquals(dnsBefore, h.dnsCalls.get(), "initial literal rejected before DNS")
            }
            h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                assertFailure(h.client.fetch(token, request()), MediaHttpFailure.INVALID_URL)
                assertEquals(1, token.budget.snapshot().requests, "no unsafe redirected request")
                assertEquals(1, token.budget.snapshot().socketsCreated)
            }
            assertEquals(2, h.server.requests.size, "one control plus one allowed initial hop")
        }
    }

    @Test fun socketGuardChecksExactEndpointAndAllConnectingFactoryOverloadsFailClosed() = fixture { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            val factory = GuardedMediaSocketFactory(token, h.environment.routeMapper)
            factory.createSocket().use { socket -> socket.connect(InetSocketAddress(PUBLIC_V4, 443), 1000) }
            assertEquals(2, h.connects.get(), "positive guard control connects through mapper")
            for (endpoint in listOf(InetSocketAddress(address("127.0.0.1"), 443),
                InetSocketAddress(PUBLIC_V4, 80), InetSocketAddress.createUnresolved("media.test", 443))) {
                factory.createSocket().use { socket -> assertFailsWith<IOException> { socket.connect(endpoint, 1000) } }
            }
            assertEquals(2, h.connects.get(), "invalid endpoints never reach connector")
            assertFailsWith<IOException> { factory.createSocket("media.test", 443) }
            assertFailsWith<IOException> { factory.createSocket(PUBLIC_V4, 443) }
            assertFailsWith<IOException> { factory.createSocket("media.test", 443, address("127.0.0.1"), 0) }
            assertFailsWith<IOException> { factory.createSocket(PUBLIC_V4, 443, address("127.0.0.1"), 0) }
            assertEquals(2, h.connects.get())
        }
    }

    @Test fun sequentialRouteFallbackRemainsInsideProductionSocketGuard() {
        val deniedFirst = address("9.9.9.9") // Public, deliberately unmapped: fails without egress.
        fixture(resolver = { listOf(deniedFirst, PUBLIC_V4) }) { h ->
            h.control()
            assertTrue(h.connectedAddresses.all { it == PUBLIC_V4 }, "unmapped fallback cannot egress")
            assertEquals(1, h.server.requests.size)
        }
    }

    @Test fun signedRequestTargetAndOnlyFixedPublicHeadersReachWire() = fixture { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            assertSuccess(h.client.fetch(token, request("/a%2Fb?sig=Ab%2fCD%2B%3d&x=1+2"))).close()
            val recorded = h.server.requests.last()
            assertTrue(recorded.target == "/a%2Fb?sig=Ab%2fCD%2B%3d&x=1+2", "signed wire target unchanged")
            assertEquals("identity", recorded.headers["accept-encoding"])
            assertTrue(recorded.headers["user-agent"].orEmpty().isNotEmpty())
            assertTrue(recorded.headers["accept"].orEmpty().contains("image/"))
            for (name in listOf("authorization", "proxy-authorization", "cookie", "referer", "range"))
                assertFalse(recorded.headers.containsKey(name), "unscoped header absent")
        }
    }

    @Test fun relativeRedirectsUseThreeSharedHopsAndFourDirectRequests() = fixture(respond = { request, socket ->
        val index = request.target.removePrefix("/r").toInt()
        if (index < 3) socket.reply(302, headers = mapOf("Location" to "/r${index + 1}")) else socket.reply()
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            assertSuccess(h.client.fetch(token, request("/r0"))).close()
            assertEquals(4, token.budget.snapshot().requests)
            assertEquals(3, token.budget.snapshot().redirects)
        }
        assertEquals(5, h.server.requests.size)
    }

    @Test fun fourthRedirectIsRejectedBeforeFifthDirectRequest() = fixture(respond = { recorded, socket ->
        val index = recorded.target.removePrefix("/r").toInt()
        socket.reply(302, headers = mapOf("Location" to "/r${index + 1}"))
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            assertFailure(h.client.fetch(token, request("/r0")), MediaHttpFailure.REDIRECT_LIMIT)
            assertEquals(4, token.budget.snapshot().requests)
        }
        assertEquals(5, h.server.requests.size)
    }

    @Test fun pageAndImageShareFiveRequestsAndThreeHopsRatherThanResetting() = fixture(respond = { recorded, socket ->
        when (recorded.target) {
            "/page" -> socket.reply(302, headers = mapOf("Location" to "/p1"))
            "/p1" -> socket.reply(302, headers = mapOf("Location" to "/p2"))
            "/image" -> socket.reply(302, headers = mapOf("Location" to "/i1"))
            else -> socket.reply()
        }
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            assertSuccess(h.client.fetch(token, request("/page", MediaHttpPurpose.PAGE))).close()
            assertSuccess(h.client.fetch(token, request())).close()
            assertEquals(5, token.budget.snapshot().requests)
            assertEquals(3, token.budget.snapshot().redirects)
            assertFailure(h.client.fetch(token, request("/second-image")), MediaHttpFailure.RESOURCE_LIMIT)
            assertEquals(5, token.budget.snapshot().requests)
        }
    }

    @Test fun redirectHopBudgetDoesNotResetBetweenPageAndAsset() = fixture(respond = { recorded, socket ->
        when (recorded.target) {
            "/page" -> socket.reply(302, headers = mapOf("Location" to "/p1"))
            "/p1" -> socket.reply(302, headers = mapOf("Location" to "/p2"))
            "/image" -> socket.reply(302, headers = mapOf("Location" to "/i1"))
            "/i1" -> socket.reply(302, headers = mapOf("Location" to "/i2"))
            else -> socket.reply()
        }
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            assertSuccess(h.client.fetch(token, request("/page", MediaHttpPurpose.PAGE))).close()
            assertFailure(h.client.fetch(token, request()), MediaHttpFailure.REDIRECT_LIMIT)
            assertEquals(5, token.budget.snapshot().requests)
        }
    }

    @Test fun redirectLoopMissingLocationAndPartialStatusDoNotReadBodies() {
        for ((status, headers, reason) in listOf(
            Triple(302, mapOf("Location" to "/image"), MediaHttpFailure.REDIRECT_LOOP),
            Triple(302, emptyMap(), MediaHttpFailure.INVALID_URL),
            Triple(206, mapOf("Content-Range" to "bytes 0-2/10"), MediaHttpFailure.HTTP_STATUS))) {
            fixture(respond = { _, socket -> socket.reply(status, headers = headers) }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                    assertFailure(h.client.fetch(token, request()), reason)
                    assertEquals(1, token.budget.snapshot().requests)
                    assertEquals(0L, token.budget.snapshot().consumedBytes)
                }
            }
        }
    }

    @Test fun silent503RetryAfterZeroNeverSendsASecondExchange() = fixture(respond = { _, socket ->
        socket.reply(503, headers = mapOf("Retry-After" to "0"))
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            assertFailure(h.client.fetch(token, request()), MediaHttpFailure.HTTP_STATUS, 503)
            assertEquals(1, token.budget.snapshot().requests)
            assertEquals(0L, token.budget.snapshot().consumedBytes)
        }
        assertEquals(2, h.server.requests.size)
    }

    @Test fun cookiesAndAuthenticationChallengesNeverBecomeClientState() = fixture(respond = { recorded, socket ->
        if (recorded.target == "/page") socket.reply(headers = mapOf("Set-Cookie" to "fixture=secret"))
        else socket.reply(401, headers = mapOf("WWW-Authenticate" to "Basic realm=fixture"))
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            assertSuccess(h.client.fetch(token, request("/page", MediaHttpPurpose.PAGE))).close()
            assertFailure(h.client.fetch(token, request()), MediaHttpFailure.HTTP_STATUS, 401)
            assertFalse(h.server.requests.last().headers.containsKey("cookie"))
            assertFalse(h.server.requests.last().headers.containsKey("authorization"))
            assertEquals(2, token.budget.snapshot().requests)
        }
    }

    @Test fun linkedinContextUsesOnlyOriginAcrossAllowedAssetRedirect() = fixture(respond = { recorded, socket ->
        if (recorded.target == "/asset") socket.reply(302, headers = mapOf("Location" to "/final"))
        else socket.reply()
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            val page = assertSuccess(h.client.fetch(token, request("/posts/fixture?rcm=synthetic", MediaHttpPurpose.PAGE,
                "www.linkedin.com")))
            val target = page.target
            page.close()
            assertSuccess(h.client.fetch(token, request("/asset", host = "media.licdn.com", context = target))).close()
            val asset = h.server.requests.first { it.target == "/asset" }
            assertTrue(asset.headers["referer"] == "https://www.linkedin.com/", "only public origin Referer")
            assertTrue(h.server.requests.last().headers["referer"] == "https://www.linkedin.com/",
                "origin-only context retained on allowed asset hop")
        }
    }

    @Test fun linkedinContextRejectsOffProviderRedirectBeforeDnsOrSocketOfNextHop() = fixture(respond = { recorded, socket ->
        if (recorded.target == "/asset") socket.reply(302, headers = mapOf("Location" to "https://other.test/final"))
        else socket.reply()
    }) { h ->
        h.control()
        // Same fixture and valid context must first fetch an ordinary allowed asset successfully.
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            val page = assertSuccess(h.client.fetch(token, request("/posts/fixture", MediaHttpPurpose.PAGE,
                "www.linkedin.com")))
            val context = page.target
            page.close()
            assertSuccess(h.client.fetch(token, request("/allowed-asset", host = "media.licdn.com", context = context)),
                byteArrayOf(10, 20, 30)).close()
        }
        h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
            val page = assertSuccess(h.client.fetch(token, request("/posts/fixture", MediaHttpPurpose.PAGE,
                "www.linkedin.com")))
            val context = page.target
            page.close()
            val dnsBefore = h.dnsCalls.get()
            val connectsBefore = h.connects.get()
            assertFailure(h.client.fetch(token, request("/asset", host = "media.licdn.com", context = context)),
                MediaHttpFailure.INVALID_CONTEXT)
            assertEquals(dnsBefore + 1, h.dnsCalls.get(), "only allowed asset hop resolved")
            assertEquals(connectsBefore + 1, h.connects.get(), "no socket to off-provider redirect")
            assertEquals(2, token.budget.snapshot().requests, "one page and one asset request")
            assertFalse(h.server.requests.any { it.target == "/final" }, "off-provider target never requested")
        }
    }

    @Test fun anotherOperationCannotReuseLinkedinContextOrExpiredToken() = fixture { h ->
        h.control()
        val first = h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE)
        val page = assertSuccess(h.client.fetch(first, request("/posts/fixture", MediaHttpPurpose.PAGE, "www.linkedin.com")))
        val target = page.target
        page.close()
        first.close()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            assertFailure(h.client.fetch(token, request(host = "media.licdn.com", context = target)), MediaHttpFailure.INVALID_CONTEXT)
            assertEquals(0, token.budget.snapshot().requests)
        }
        assertFailure(h.client.fetch(first, request()), MediaHttpFailure.CLOSED)
    }

    @Test fun defaultHostnameVerificationStillRejectsTrustedWrongHost() = fixture { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            assertFailure(h.client.fetch(token, request(host = "wrong.test")), MediaHttpFailure.NETWORK)
        }
        assertEquals(1, h.server.requests.size, "TLS name rejection occurs before HTTP request")
    }

    @Test fun exactImageAndPageCapsSucceedWithDeclaredAndChunkedBodies() {
        for ((purpose, cap) in listOf(MediaHttpPurpose.IMAGE to 2 * 1024 * 1024,
            MediaHttpPurpose.PAGE to 1024 * 1024)) {
            for (chunked in listOf(false, true)) {
                val body = ByteArray(cap) { 42 }
                fixture(respond = { _, socket -> socket.reply(body = body, chunked = chunked) }) { h ->
                    h.control(purpose)
                    h.client.newOperation(if (purpose == MediaHttpPurpose.PAGE) MediaHttpOperation.PAGE_AND_IMAGE
                        else MediaHttpOperation.DIRECT_IMAGE).use { token ->
                        assertSuccess(h.client.fetch(token, request(purpose = purpose)), body).use { response ->
                            assertEquals(cap.toLong(), response.measuredBytes)
                        }
                        assertEquals(cap.toLong(), token.budget.snapshot().consumedBytes)
                    }
                }
            }
        }
    }

    @Test fun retainedImageAndPageBodiesUseOnlyMeasuredBytes() {
        val body = byteArrayOf(10, 20, 30)
        for (purpose in listOf(MediaHttpPurpose.IMAGE, MediaHttpPurpose.PAGE)) {
            for ((chunked, declared) in listOf(false to 3L, false to null, true to null)) {
                fixture(respond = { _, socket -> socket.reply(body = body, chunked = chunked, declared = declared) }) { h ->
                    h.control(purpose)
                    h.client.newOperation(if (purpose == MediaHttpPurpose.PAGE) MediaHttpOperation.PAGE_AND_IMAGE
                        else MediaHttpOperation.DIRECT_IMAGE).use { token ->
                        assertSuccess(h.client.fetch(token, request(purpose = purpose))).use { response ->
                            val transferred = response.takeBody()
                            assertEquals(body.size, transferred.length)
                            assertContentEquals(body, transferred.bytes.copyOf(transferred.length), "positive complete-body control")
                            assertEquals(body.size, transferred.bytes.size, "retained encoded storage equals measured body")
                            assertEquals(3L, response.measuredBytes)
                        }
                    }
                }
            }
        }
    }

    @Test fun declaredOversizeIsRejectedWithoutConsumingBody() {
        for ((purpose, cap) in listOf(MediaHttpPurpose.IMAGE to 2 * 1024 * 1024,
            MediaHttpPurpose.PAGE to 1024 * 1024)) fixture(respond = { _, socket ->
            socket.reply(declared = cap.toLong() + 1)
        }) { h ->
            h.control(purpose)
            h.client.newOperation(if (purpose == MediaHttpPurpose.PAGE) MediaHttpOperation.PAGE_AND_IMAGE
                else MediaHttpOperation.DIRECT_IMAGE).use { token ->
                assertFailure(h.client.fetch(token, request(purpose = purpose)), MediaHttpFailure.BODY_LIMIT)
                assertEquals(0L, token.budget.snapshot().consumedBytes)
            }
        }
    }

    @Test fun chunkedCapPlusOneIsRejectedEvenWithMisleadingSmallContentLength() {
        for (misleadingLength in listOf(false, true)) fixture(respond = { _, socket ->
            socket.reply(body = ByteArray(2 * 1024 * 1024 + 1), chunked = true,
                headers = if (misleadingLength) mapOf("Content-Length" to "1") else emptyMap())
        }) { h ->
            h.control()
            h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                assertFailure(h.client.fetch(token, request()), MediaHttpFailure.BODY_LIMIT)
                assertTrue(token.budget.snapshot().consumedBytes <= 2 * 1024 * 1024 + 1,
                    "consumption stops at cap plus one, independently of Content-Length")
            }
        }
    }

    @Test fun unknownLengthCountsUntilEofAndRejectsCapPlusOne() {
        for (length in listOf(3, 2 * 1024 * 1024 + 1)) fixture(respond = { _, socket ->
            socket.reply(body = ByteArray(length) { 7 }, declared = null)
        }) { h ->
            h.control()
            h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                val result = h.client.fetch(token, request())
                if (length == 3) assertSuccess(result, byteArrayOf(7, 7, 7)).close()
                else assertFailure(result, MediaHttpFailure.BODY_LIMIT)
            }
        }
    }

    @Test fun truncatedDeclaredBodyAndMalformedChunkNeverReturnPartialSuccess() {
        for (malformedChunk in listOf(false, true)) fixture(respond = { _, socket ->
            if (!malformedChunk) socket.reply(body = byteArrayOf(1, 2, 3), declared = 9)
            else {
                socket.outputStream.write(("HTTP/1.1 200 Fixture\r\nContent-Type: image/jpeg\r\n" +
                    "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nabc").toByteArray())
                socket.outputStream.flush()
            }
        }) { h ->
            h.control()
            h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                assertFailure(h.client.fetch(token, request()), MediaHttpFailure.PARTIAL_BODY)
            }
        }
    }

    @Test fun unsupportedEncodingAndHttpErrorAreRejectedBeforeBodyRead() {
        for ((status, encoding, reason) in listOf(Triple(200, "gzip", MediaHttpFailure.ENCODING),
            Triple(200, "br", MediaHttpFailure.ENCODING), Triple(200, "identity, gzip", MediaHttpFailure.ENCODING),
            Triple(403, "identity", MediaHttpFailure.HTTP_STATUS))) {
            fixture(respond = { _, socket -> socket.reply(status, headers = mapOf("Content-Encoding" to encoding)) }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                    assertFailure(h.client.fetch(token, request()), reason)
                    assertEquals(0L, token.budget.snapshot().consumedBytes)
                }
            }
        }
    }

    @Test fun explicitIdentityEncodingIsAcceptedWithoutTransparentInflation() = fixture(respond = { _, socket ->
        socket.reply(headers = mapOf("Content-Encoding" to "identity"))
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            assertSuccess(h.client.fetch(token, request()), byteArrayOf(10, 20, 30)).close()
            assertEquals(3L, token.budget.snapshot().consumedBytes)
        }
    }

    @Test fun cancellationBeforeCallRegistrationPreventsDnsAndSocketsAndIdCannotRestart() = fixture { h ->
        h.control()
        val dnsBefore = h.dnsCalls.get()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            h.client.cancel(token.id)
            h.client.cancel(token.id)
            assertFailure(h.client.fetch(token, request()), MediaHttpFailure.CANCELLED)
            assertFailure(h.client.fetch(token, request()), MediaHttpFailure.CANCELLED)
            assertEquals(0, token.budget.snapshot().socketsCreated)
            assertEquals(dnsBefore, h.dnsCalls.get())
        }
        h.control()
    }

    @Test fun cancelDuringUninterruptibleDnsReleasesFetchThreadAndAdmitsAnotherOperation() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            fixture(resolver = { host ->
                if (host == "other.test") {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { /* OS-like */ }
                }
                listOf(PUBLIC_V4)
            }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                    MediaHttpFetchTask(h.client, token, request(host = "other.test")).use { task ->
                        await(entered, "raw resolver entered")
                        h.client.cancel(token.id)
                        assertFailure(task.get(), MediaHttpFailure.CANCELLED)
                        assertEquals(0, token.budget.snapshot().socketsCreated)
                        h.control() // Second DNS worker remains usable despite the first OS-like wait.
                        release.countDown()
                        assertFailure(h.client.fetch(token, request()), MediaHttpFailure.CANCELLED)
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test fun dnsWholeDeadlineReleasesFetchEvenWhenResolverIgnoresInterruption() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            fixture(resolver = { host ->
                if (host == "other.test") {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { /* OS-like */ }
                }
                listOf(PUBLIC_V4)
            }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                    val started = System.nanoTime()
                    MediaHttpFetchTask(h.client, token, request(host = "other.test")).use { task ->
                        await(entered, "deadline resolver entered")
                        assertFailure(task.get(18), MediaHttpFailure.TIMEOUT)
                        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(18), "whole DNS deadline bounds wait")
                        assertEquals(0, token.budget.snapshot().socketsCreated)
                        h.control()
                        release.countDown()
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test fun resolverRuntimeFailuresBecomeRedactedTypedFailures() {
        for (failure in listOf(java.util.concurrent.CancellationException("synthetic-secret"),
            IllegalStateException("synthetic-secret"), InterruptedException("synthetic-secret"))) {
            fixture(resolver = { host ->
                if (host == "other.test") throw failure else listOf(PUBLIC_V4)
            }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                    val result = h.client.fetch(token, request(host = "other.test"))
                    assertFailure(result, MediaHttpFailure.NETWORK)
                    assertFalse(result.toString().contains("synthetic-secret"), "resolver exception is redacted")
                }
            }
        }
    }

    @Test fun interruptedDnsWaitReturnsTypedFailureAndRestoresInterruptFlag() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            fixture(resolver = { host ->
                if (host == "other.test") { entered.countDown(); release.await() }
                listOf(PUBLIC_V4)
            }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                    MediaHttpFetchTask(h.client, token, request(host = "other.test")).use { task ->
                        await(entered, "interruptible DNS wait entered")
                        task.interruptWorker()
                        assertFailure(task.get(), MediaHttpFailure.NETWORK)
                        assertTrue(task.interruptRestored.get(), "caller interrupt restored before returning")
                        release.countDown()
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test fun cancellationDuringHeadersRejectsLateSuccessAndClosesAllSockets() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            fixture(respond = { _, socket -> entered.countDown(); release.await(); socket.reply() }) { h ->
                h.control()
                val token = h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE)
                MediaHttpFetchTask(h.client, token, request()).use { task ->
                    await(entered, "server received headers")
                    h.client.cancel(token.id)
                    assertFailure(task.get(), MediaHttpFailure.CANCELLED)
                    release.countDown()
                    assertFailure(h.client.fetch(token, request()), MediaHttpFailure.CANCELLED)
                }
                token.close()
                assertEquals(token.budget.snapshot().socketsCreated, token.budget.snapshot().socketsClosed)
            }
        } finally { release.countDown() }
    }

    @Test fun cancellationDuringBodyDiscardsReceivedPrefixAndClosesOwnedResponse() {
        val prefix = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            fixture(respond = { _, socket ->
                socket.outputStream.write(("HTTP/1.1 200 Fixture\r\nContent-Type: image/jpeg\r\n" +
                    "Content-Length: 100\r\nConnection: close\r\n\r\nx").toByteArray())
                socket.outputStream.flush()
                prefix.countDown()
                release.await()
            }) { h ->
                h.control()
                val token = h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE)
                MediaHttpFetchTask(h.client, token, request()).use { task ->
                    await(prefix, "response prefix written")
                    h.client.cancel(token.id)
                    assertFailure(task.get(), MediaHttpFailure.CANCELLED)
                    release.countDown()
                }
                token.close()
                assertEquals(token.budget.snapshot().socketsCreated, token.budget.snapshot().socketsClosed)
            }
        } finally { release.countDown() }
    }

    @Test fun sameSourceDifferentOperationIdsRemainIsolated() {
        val slowEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val arrivals = AtomicInteger()
        try {
            fixture(respond = { _, socket ->
                if (arrivals.incrementAndGet() == 1) { slowEntered.countDown(); release.await() }
                socket.reply()
            }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { first ->
                    h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { second ->
                        MediaHttpFetchTask(h.client, first, request()).use { task ->
                            await(slowEntered, "first same-source call parked")
                            h.client.cancel(first.id)
                            assertSuccess(h.client.fetch(second, request()), byteArrayOf(10, 20, 30)).close()
                            assertFailure(task.get(), MediaHttpFailure.CANCELLED)
                            release.countDown()
                        }
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test fun activeOperationLimitFailsClosedAndRecoversAfterClose() = fixture { h ->
        h.control()
        val first = h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE)
        val second = h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE)
        try {
            h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { third ->
                assertFailure(h.client.fetch(third, request()), MediaHttpFailure.BUSY)
                assertEquals(0, third.budget.snapshot().socketsCreated)
            }
        } finally { first.close(); second.close() }
        h.control()
    }

    @Test fun twoHungDnsWorkersRejectNewResolutionWithoutQueue() {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        try {
            fixture(resolver = { host ->
                if (host == "other.test") {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { /* OS-like */ }
                }
                listOf(PUBLIC_V4)
            }) { h ->
                h.control()
                val first = h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE)
                val second = h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE)
                MediaHttpFetchTask(h.client, first, request(host = "other.test")).use { one ->
                    MediaHttpFetchTask(h.client, second, request(host = "other.test")).use { two ->
                        await(entered, "both bounded DNS workers occupied")
                        h.client.cancel(first.id); h.client.cancel(second.id)
                        assertFailure(one.get(), MediaHttpFailure.CANCELLED)
                        assertFailure(two.get(), MediaHttpFailure.CANCELLED)
                        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { fresh ->
                            assertFailure(h.client.fetch(fresh, request()), MediaHttpFailure.BUSY)
                            assertEquals(0, fresh.budget.snapshot().socketsCreated)
                        }
                        release.countDown()
                    }
                }
                first.close(); second.close()
            }
        } finally { release.countDown() }
    }

    @Test fun readTimeoutRejectsStalledHeadersWithoutPartialSuccess() {
        val release = CountDownLatch(1)
        try {
            fixture(respond = { _, _ -> release.await() }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                    MediaHttpFetchTask(h.client, token, request()).use { task ->
                        assertFailure(task.get(8), MediaHttpFailure.TIMEOUT)
                        assertEquals(0L, token.budget.snapshot().consumedBytes)
                        release.countDown()
                    }
                }
            }
        } finally { release.countDown() }
    }

    @Test fun wholeOperationDeadlineSpansPageAndTrickledAssetWithoutResettingOnEachRead() {
        val finished = CountDownLatch(1)
        try {
            fixture(respond = response@ { recorded, socket ->
                val page = recorded.target == "/page"
                socket.outputStream.write(("HTTP/1.1 200 Fixture\r\nContent-Type: image/jpeg\r\n" +
                    "Content-Length: ${if (page) 3 else 100}\r\nConnection: close\r\n\r\n").toByteArray())
                repeat(if (page) 3 else 10) {
                    socket.outputStream.write(1)
                    socket.outputStream.flush()
                    if (finished.await(2, TimeUnit.SECONDS)) return@response
                }
            }) { h ->
                h.control()
                h.client.newOperation(MediaHttpOperation.PAGE_AND_IMAGE).use { token ->
                    val started = System.nanoTime()
                    assertSuccess(h.client.fetch(token, request("/page", MediaHttpPurpose.PAGE))).close()
                    MediaHttpFetchTask(h.client, token, request()).use { task ->
                        assertFailure(task.get(18), MediaHttpFailure.TIMEOUT)
                        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(18), "whole deadline did not reset across requests")
                        assertTrue(token.budget.snapshot().consumedBytes < 100, "received prefix was discarded")
                        finished.countDown()
                    }
                }
            }
        } finally { finished.countDown() }
    }

    @Test fun completedTargetResponseAndFailuresHaveRedactedDebugSurfaces() = fixture(respond = { _, socket ->
        socket.reply(302, headers = mapOf("Location" to "https://u:synthetic-secret@other.test/final"))
    }) { h ->
        h.control()
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            val result = h.client.fetch(token, request("/image?token=synthetic-secret"))
            assertFailure(result, MediaHttpFailure.INVALID_URL)
            assertFalse(result.toString().contains("synthetic-secret") || result.toString().contains("media.test"))
        }
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
            val success = h.client.fetch(token, request("/control"))
            val response = assertSuccess(success)
            for (value in listOf(success.toString(), response.toString(), response.target.toString()))
                assertFalse(value.contains("media.test") || value.contains("https:"), "no generated URL rendering")
            response.close()
        }
    }
}
