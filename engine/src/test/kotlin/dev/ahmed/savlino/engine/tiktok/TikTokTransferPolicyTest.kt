package dev.ahmed.savlino.engine.tiktok

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediahttp.*
import dev.ahmed.savlino.engine.mediaimage.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import kotlin.test.*

/** Real transport/Save policy with only the HTTP peer replaced; no socket or DNS required. */
class TikTokTransferPolicyTest {
    private val original = "https://p16-sign.example.tiktokcdn.com/synthetic.jpg"
    private val mirror = "https://p19-sign.example.tiktokcdn-us.com/synthetic.jpg"
    private fun peer(respond: (Request) -> Response) = OkHttpClient.Builder()
        .addInterceptor { respond(it.request()) }.build()
    private fun reply(request: Request, status: Int, bytes: ByteArray = byteArrayOf(), length: Long = bytes.size.toLong()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
            .header("Content-Length", length.toString()).body(bytes.toResponseBody()).build()

    @Test fun expiryRequiresBothUrlsToExpireAndTimeoutKeepsItsRealReasonInEitherOrder() {
        for ((codes, expected) in listOf(listOf(403, 410) to ImageFailure.EXPIRED,
            listOf(403, 0) to ImageFailure.NETWORK, listOf(0, 410) to ImageFailure.NETWORK)) {
            MediaHttpClient.create().use { http -> MediaReferenceStore().use { store ->
                val source = imageSource("https://www.tiktok.com/@synthetic/photo/900000001")
                val validity = MediaInspectionValidity.processLocal()
                val ref = MediaRenditionRef(MediaItemRef(IMAGE_IDENTITY.adapterId, "photo"), "original")
                val selection = MediaSelection(source, IMAGE_IDENTITY, ref, MediaKind.IMAGE, validity)
                store.admit(listOf(StoredImageReference(source, IMAGE_IDENTITY, validity, ref, original,
                    MediaImageMetadata(MediaImageFormat.JPEG), false, null, MediaHostRule.TIKTOK_CDN, listOf(mirror))), null)
                val saver = ImageSaveTransfer(http, store)
                val operation = saver.newOperation(selection)
                val attempted = mutableListOf<String>()
                saver.beforeAdmission = {
                    requireNotNull(operation.token).client { peer { request ->
                        attempted += request.url.host
                        val status = codes[attempted.size - 1]
                        if (status == 0) throw SocketTimeoutException()
                        reply(request, status)
                    } }
                }
                val sink = ByteArrayOutputStream()
                val result = runBlocking { saver.transfer(operation, sink) }
                assertEquals(expected, assertIs<ImageSaveResult.Failure>(result).reason)
                assertEquals(listOf("p16-sign.example.tiktokcdn.com", "p19-sign.example.tiktokcdn-us.com"), attempted)
                assertEquals(0, sink.size())
            } }
        }
    }

    @Test fun samePostHopsAreCanonicalizedButEvilHostsAndOtherPostsAreRefused() {
        for ((hop, accepted) in listOf("https://www.tiktok.com/@synthetic/video/900000001?lang=en" to true,
            "https://www.tiktok.com/@synthetic/video/900000001/" to true,
            "https://www.tiktok.com/@synthetic/photo/900000001" to true,
            "https://evil.example/@synthetic/video/900000001" to false,
            "https://www.tiktok.com.evil.example/@synthetic/video/900000001" to false,
            "https://m.tiktok.com/@synthetic/video/900000001" to false,
            "https://www.tiktok.com/@synthetic/video/900000002" to false)) {
            MediaHttpClient.create().use { http -> http.newOperation(MediaHttpOperation.TIKTOK_PAGE_AND_CAROUSEL).use { token ->
                val attempted = mutableListOf<String>()
                token.client { peer { request ->
                    attempted += request.url.toString()
                    if (attempted.size == 1) reply(request, 302).newBuilder().header("Location", hop).build()
                    else reply(request, 200, tikTokPage())
                } }
                val result = http.fetchTikTokPost(token, assertNotNull(TikTokPostIdentity.parse(
                    "https://www.tiktok.com/@synthetic/photo/900000001")))
                if (accepted) {
                    assertIs<MediaHttpResult.Success>(result).response.close()
                    assertEquals(listOf("https://www.tiktok.com/@synthetic/video/900000001",
                        "https://www.tiktok.com/@synthetic/video/900000001"), attempted)
                } else {
                    assertEquals(MediaHttpFailure.INVALID_CONTEXT, assertIs<MediaHttpResult.Failure>(result).reason)
                    assertEquals(1, attempted.size)
                }
            } }
        }
    }

    @Test fun validatedContentLengthReachesTheBufferBeforeWritesAndOversizeIsRejected() {
        for (declared in listOf(8192L, 20L * 1024 * 1024, 20L * 1024 * 1024 + 1)) {
            MediaHttpClient.create().use { http -> http.newSaveOperation().use { token ->
                val bytes = ByteArray(if (declared <= 20L * 1024 * 1024) declared.toInt() else 8192) { 7 }
                token.client { peer { request -> reply(request, 200, bytes, declared) } }
                val prepared = mutableListOf<Long>()
                val output = MirrorImageBuffer()
                val sink = object : OutputStream() {
                    override fun write(value: Int) { assertEquals(listOf(declared), prepared); output.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        assertEquals(listOf(declared), prepared); output.write(bytes, offset, length)
                    }
                }
                val result = http.transferImage(token, PublicMediaRequest(original, MediaHttpPurpose.IMAGE,
                    hostRule = MediaHostRule.TIKTOK_CDN), sink) { prepared += it; output.prepare(it) }
                if (declared > 20L * 1024 * 1024) {
                    assertEquals(MediaHttpFailure.BODY_LIMIT, assertIs<MediaHttpImageTransferResult.Failure>(result).reason)
                    assertTrue(prepared.isEmpty())
                    assertEquals(0, output.size())
                } else {
                    assertIs<MediaHttpImageTransferResult.Success>(result)
                    assertEquals(listOf(declared), prepared)
                    assertContentEquals(bytes, output.toByteArray())
                }
            } }
        }
    }
}
