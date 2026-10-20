package dev.ahmed.savlino.update

import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [29], application = android.app.Application::class)
class UpdateHttpTest {
    private val release = Release("0.4.5", 99999, "update.apk", 6, "https://github.com/asset", "a".repeat(64), emptyList(), emptyList())
    private fun download(code: Int, range: String?, body: String, expected: String?, seed: String = "abc") = runBlocking {
        val file = File.createTempFile("update", ".part")
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { if (range != null) header("Content-Range", range) }
                .body(body.toResponseBody()).build()
        }.build()
        try {
            file.writeText(seed)
            val progress = mutableListOf<Long>()
            UpdateHttp("test", client).use { http ->
                if (expected == null) {
                    try { http.download(release, file) { progress += it }; fail("Invalid response accepted") }
                    catch (_: IOException) {}
                    assertEquals(seed, file.readText())
                } else {
                    http.download(release, file) { progress += it }
                    assertEquals(expected, file.readText())
                    assertEquals(6L, progress.last())
                }
            }
            assertEquals("bytes=3-", requests.single().header("Range"))
        } finally { file.delete(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun matching206AppendsAndReportsTotalProgress() = download(206, "bytes 3-5/6", "def", "abcdef")
    @Test fun server200RestartsInsteadOfAppending() = download(200, null, "uvwxyz", "uvwxyz")
    @Test fun wrongRangeStartEndOrTotalIsRejected() {
        for (range in listOf("bytes 2-5/6", "bytes 3-4/6", "bytes 3-5/7", "bytes 3-5/*", "garbage"))
            download(206, range, "def", null)
    }
    @Test fun wrongResponseLengthIsRejectedBeforeAppend() = download(206, "bytes 3-5/6", "toolong", null)
    @Test fun unexpectedStatusIsRejected() = download(416, "bytes */6", "", null)
    @Test fun rangeSurvivesAllowedRedirect() = runBlocking {
        val file = File.createTempFile("update", ".part")
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            requests += chain.request()
            val redirect = requests.size == 1
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (redirect) 302 else 206).message("test")
                .apply { if (redirect) header("Location", "https://release-assets.githubusercontent.com/asset") else header("Content-Range", "bytes 3-5/6") }
                .body((if (redirect) "" else "def").toResponseBody()).build()
        }.build()
        try {
            file.writeText("abc")
            UpdateHttp("test", client).use { it.download(release, file) {} }
            assertEquals("abcdef", file.readText())
            assertEquals(listOf("bytes=3-", "bytes=3-"), requests.map { it.header("Range") })
        } finally { file.delete(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }
    @Test fun streamedBodiesMustFitAndCompleteTheRemainingSize() = runBlocking {
        for (body in listOf("d", "defg")) {
            val file = File.createTempFile("update", ".part")
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("test")
                    .header("Content-Range", "bytes 3-5/6").body(object : ResponseBody() {
                        override fun contentType(): MediaType? = null
                        override fun contentLength() = -1L
                        override fun source(): okio.BufferedSource = okio.Buffer().writeUtf8(body)
                    }).build()
            }.build()
            try {
                file.writeText("abc")
                UpdateHttp("test", client).use { http ->
                    try { http.download(release, file) {}; fail("Invalid streamed length accepted") }
                    catch (_: IOException) {}
                }
                assertTrue(file.length() <= 6)
                assertTrue(file.readText().startsWith("abc"))
            } finally { file.delete(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

}
