package dev.ahmed.savlino.engine.mediahttp

import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.*

/** Keep-alive is per operation: one token reuses its own socket, never another token's. */
class MediaHttpKeepAliveTest {
    companion object {
        private lateinit var identity: MediaHttpTlsIdentity
        @JvmStatic @BeforeClass fun createIdentity() { identity = MediaHttpTlsIdentity() }
        @JvmStatic @AfterClass fun closeIdentity() { if (::identity.isInitialized) identity.close() }
    }

    private fun keepAlive(test: (MediaHttpHarness) -> Unit) =
        MediaHttpServer(identity, keepAlive = true) { _, socket ->
            socket.reply(headers = mapOf("Connection" to "keep-alive"))
        }.use { server -> MediaHttpHarness(identity, server).use(test) }

    private fun MediaHttpHarness.image(token: OperationToken, index: Int) =
        assertSuccess(client.fetch(token, PublicMediaRequest("https://media.test/image$index", MediaHttpPurpose.IMAGE)),
            byteArrayOf(10, 20, 30)).close()

    private fun awaitCompleted(server: MediaHttpServer, count: Int) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (server.completed.get() < count && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(count, server.completed.get(), "peer saw the pooled socket close")
    }

    @Test fun oneOperationReusesOneSocketForSameHostRequests() = keepAlive { h ->
        h.client.newOperation(MediaHttpOperation.PAGE_AND_CAROUSEL).use { token ->
            repeat(3) { h.image(token, it) }
            val counters = token.budget.snapshot()
            assertEquals(3, counters.requests)
            assertEquals(1, counters.socketsCreated, "three same-host requests share one socket")
            assertEquals(0, counters.socketsClosed, "idle socket stays pooled while the operation lives")
            assertEquals(1, h.server.accepted.get())
            assertEquals(1, h.connects.get())
            assertEquals(listOf("/image0", "/image1", "/image2"), h.server.requests.map { it.target })
        }
    }

    @Test fun separateOperationsNeverShareAPooledSocket() = keepAlive { h ->
        val first = h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE)
        h.image(first, 0)
        assertEquals(0, first.budget.snapshot().socketsClosed, "first socket is idle in its own pool")
        h.client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { second ->
            h.image(second, 1)
            assertEquals(1, second.budget.snapshot().socketsCreated, "second operation opens its own socket")
        }
        assertEquals(2, h.server.accepted.get())
        assertEquals(2, h.connects.get())
        first.close()
        val counters = first.budget.snapshot()
        assertEquals(counters.socketsCreated, counters.socketsClosed)
        awaitCompleted(h.server, 2)
    }

    @Test fun retiringOrCancellingTheOperationClosesItsIdleSocket() {
        for (cancel in listOf(false, true)) keepAlive { h ->
            val token = h.client.newOperation(MediaHttpOperation.PAGE_AND_CAROUSEL)
            repeat(2) { h.image(token, it) }
            assertEquals(1, token.budget.snapshot().socketsCreated)
            assertEquals(0, h.server.completed.get(), "socket still open before retirement")
            if (cancel) h.client.cancel(token.id) else token.close()
            val counters = token.budget.snapshot()
            assertEquals(1, counters.socketsClosed, "retirement evicts the pool")
            awaitCompleted(h.server, 1)
            assertFailure(h.client.fetch(token, PublicMediaRequest("https://media.test/late", MediaHttpPurpose.IMAGE)),
                if (cancel) MediaHttpFailure.CANCELLED else MediaHttpFailure.CLOSED)
            assertEquals(1, h.server.accepted.get(), "a retired token never reconnects")
        }
    }
}
