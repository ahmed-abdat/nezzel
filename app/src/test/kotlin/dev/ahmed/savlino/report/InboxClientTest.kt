package dev.ahmed.savlino.report

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class InboxClientTest {
    private val report = Report.failure("https://example.com/watch?v=1", "Failed", "0.5.0", null, 35)
    private fun send(code: Int, location: String? = null, failure: java.io.IOException? = null): Pair<Boolean, List<Request>> {
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            requests += chain.request()
            failure?.let { throw it }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { location?.let { header("Location", it) } }.body("".toResponseBody()).build()
        }.build()
        try { return runBlocking { InboxClient(client, "test").send(report) } to requests }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun postsTheReportJsonOnceToTheInbox() {
        val (sent, requests) = send(204)
        assertTrue(sent)
        val request = requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://nezzel.hululsoft.mr/api/report", request.url.toString())
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
        assertNull(request.header("Cookie"))
        assertEquals(report.json(), Buffer().also { request.body!!.writeTo(it) }.readUtf8())
    }

    @Test fun anythingButNoContentIsAFailureWithoutRetryOrRedirect() {
        listOf(200, 400, 413, 429, 500).forEach { code -> send(code).let { (sent, requests) -> assertFalse(sent); assertEquals(1, requests.size) } }
        send(302, "https://example.com/elsewhere").let { (sent, requests) -> assertFalse(sent); assertEquals(1, requests.size) }
        send(0, failure = java.io.IOException()).let { (sent, requests) -> assertFalse(sent); assertEquals(1, requests.size) }
    }

    @Test fun onlyKnownInboxPathsArePosted() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { InboxClient().post("/other", "{}") } }
    }
}
