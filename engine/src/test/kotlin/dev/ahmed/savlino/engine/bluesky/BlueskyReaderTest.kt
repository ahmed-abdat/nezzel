package dev.ahmed.savlino.engine.bluesky

import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import org.junit.Test
import kotlin.test.*

class BlueskyReaderTest : PublicImageTestBase() {
    private val did = "did:plc:abcdefghijklmnopqrstuvwx"
    private val source = LinkInput.parse("https://bsky.app/profile/$did/post/3synthetic001")!!
    private val reader = BlueskyReader(source)
    private fun body(name: String): MediaHttpBody {
        val bytes = javaClass.getResourceAsStream("/bluesky/$name.json")!!.use { it.readBytes() }
        return MediaHttpBody(bytes + ByteArray(32) { 120 }, bytes.size)
    }
    private fun failure(plan: ImageReadPlan, reason: ImageFailure, phase: ImagePhase = ImagePhase.PAGE,
        status: Int? = null) {
        val failed = assertIs<ImageReadPlan.Failure>(plan)
        assertEquals(reason, failed.reason)
        assertEquals(phase, failed.phase)
        assertEquals(status, failed.statusCode)
        assertNull(failed.fallback)
    }
    private fun readPage(result: MediaHttpResult): ImagePageRead = when (result) {
        is MediaHttpResult.Failure -> ImagePageRead.Failed(ImageReadPlan.Failure(
            mapTransport(result.reason), ImagePhase.PAGE, result.statusCode))
        is MediaHttpResult.Success -> result.response.use { ImagePageRead.Body(it.takeBody()) }
    }

    @Test fun refusedIdentityNeverRequestsOrReadsAPage() {
        fixture { f ->
            f.http.client.newOperation(BlueskyReader.operation).use { token ->
                failure(BlueskyReader(LinkInput.parse("https://bsky.app/profile/synthetic.test")!!).read(f.http.client, token) {
                    error("Refused identity reached page reader")
                }, ImageFailure.SOURCE_REFUSED)
                assertTrue(f.server.requests.isEmpty())
            }
        }
    }
    @Test fun badRequestIsPrivate() = privateStatus(400)
    @Test fun missingIsPrivate() = privateStatus(404)
    @Test fun goneIsPrivate() = privateStatus(410)
    private fun privateStatus(status: Int) = failure(assertNotNull(reader.pageFailure(
        MediaHttpResult.Failure(MediaHttpFailure.HTTP_STATUS, status))), ImageFailure.PRIVATE)
    @Test fun otherFailuresKeepTheSharedMapping() {
        for (status in listOf(401, 403, 429, 500))
            assertNull(reader.pageFailure(MediaHttpResult.Failure(MediaHttpFailure.HTTP_STATUS, status)))
        assertNull(reader.pageFailure(MediaHttpResult.Failure(MediaHttpFailure.NETWORK, 404)))
        assertNull(reader.pageFailure(MediaHttpResult.Failure(MediaHttpFailure.CANCELLED)))
    }
    @Test fun privateStatusOnEitherApiReadStopsBeforeSharedMapping() {
        for (status in listOf(400, 404, 410)) for (resolve in listOf(false, true)) {
            fixture(respond = { _, socket -> socket.reply(status = status) }) { f ->
                f.http.client.newOperation(BlueskyReader.operation).use { token ->
                    val target = if (resolve) BlueskyReader(LinkInput.parse(
                        "https://bsky.app/profile/synthetic.test/post/3synthetic001")!!) else reader
                    failure(target.read(f.http.client, token) { error("Private status reached shared mapping") }, ImageFailure.PRIVATE)
                    assertEquals(1, f.server.requests.size)
                }
            }
        }
    }
    @Test fun otherHttpStatusesUseSharedPageMapping() {
        for (status in listOf(401, 403, 429, 500)) fixture(respond = { _, socket -> socket.reply(status = status) }) { f ->
            f.http.client.newOperation(BlueskyReader.operation).use { token ->
                failure(reader.read(f.http.client, token, ::readPage), ImageFailure.HTTP, status = status)
            }
        }
    }
    @Test fun directDidReadsOneThreadWhileHandleResolvesThenReadsThread() {
        for (resolve in listOf(false, true)) fixture(respond = { request, socket ->
            val page = if (request.target.startsWith("/xrpc/com.atproto.identity.resolveHandle")) "resolve-handle" else "single"
            val bytes = body(page)
            socket.reply(body = bytes.bytes.copyOf(bytes.length), headers = mapOf("Content-Type" to "application/json"))
        }) { f ->
            f.http.client.newOperation(BlueskyReader.operation).use { token ->
                val target = if (resolve) BlueskyReader(LinkInput.parse(
                    "https://bsky.app/profile/synthetic.test/post/3synthetic001")!!) else reader
                assertPhotos(target.read(f.http.client, token, ::readPage), 1)
                assertEquals(if (resolve) 2 else 1, f.server.requests.size)
                assertEquals("/xrpc/app.bsky.feed.getPostThread?uri=at%3A%2F%2Fdid%3Aplc%3Aabcdefghijklmnopqrstuvwx%2Fapp.bsky.feed.post%2F3synthetic001&depth=0",
                    f.server.requests.last().target)
            }
        }
    }
    @Test fun invalidHandleResolutionNeverReadsThread() {
        fixture(respond = { _, socket -> socket.reply(body = "{\"did\":\"did:key:invalid\"}".toByteArray(),
            headers = mapOf("Content-Type" to "application/json")) }) { f ->
            f.http.client.newOperation(BlueskyReader.operation).use { token ->
                val handle = BlueskyReader(LinkInput.parse("https://bsky.app/profile/synthetic.test/post/3synthetic001")!!)
                failure(handle.read(f.http.client, token, ::readPage), ImageFailure.SOURCE_REFUSED)
                assertEquals(1, f.server.requests.size)
            }
        }
    }
    @Test fun parserFailuresKeepReasonAndPhase() {
        failure(reader.parse(body("private"), did), ImageFailure.PRIVATE)
        failure(reader.parse(body("no-media"), did), ImageFailure.NO_MEDIA)
        val original = body("single")
        val unsafe = String(original.bytes, 0, original.length).replace("cdn.bsky.app", "example.org").toByteArray()
        failure(reader.parse(MediaHttpBody(unsafe, unsafe.size), did), ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
    }
    @Test fun videoKeepsExactSourceWithoutUnavailablePhotosOrInfo() {
        val video = assertIs<ImageReadPlan.Video>(reader.parse(body("video"), did))
        assertSame(source, video.target)
        assertFalse(video.photosUnavailable)
        assertNull(video.infoJson)
    }
    @Test fun photosKeepExactOriginalsPreviewsDiscoveryAndMetadata() {
        assertPhotos(reader.parse(body("single"), did), 1)
        assertPhotos(reader.parse(body("album"), did), 2)
        assertPhotos(reader.parse(body("record-with-media"), did), 2)
    }
    private fun assertPhotos(plan: ImageReadPlan, count: Int) {
        val photos = assertIs<ImageReadPlan.Photos>(plan)
        assertEquals((1..count).map { "https://cdn.bsky.app/img/feed_fullsize/plain/$did/bafkreisynthetic$it@jpeg" }, photos.candidates)
        assertEquals((1..count).map { "https://cdn.bsky.app/img/feed_thumbnail/plain/$did/bafkreisynthetic$it@jpeg" }, photos.previewCandidates)
        assertEquals(ImageDiscovery(count, DiscoveryCompleteness.COMPLETE), photos.discovery)
        assertEquals("Synthetic caption", photos.metadata.title, "metadata.title")
        assertEquals("Synthetic author", photos.metadata.uploader, "metadata.uploader")
        assertNull(photos.metadata.durationSeconds, "metadata.durationSeconds")
        assertTrue(photos.metadata.thumbnailUrl == null, "metadata.thumbnailUrl must be absent")
        assertNull(photos.pageContext)
        assertNull(photos.videoTarget)
    }
}
