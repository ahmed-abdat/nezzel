package dev.ahmed.savlino.report

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class ReportTest {
    private val link = "https://m.video.example.com/watch/123?v=secret&t=9#part"

    @Test fun failureSendsTheRegistrableSiteAndTheExactLink() {
        val json = JSONObject(Report.failure(link, "Failed", "0.5.0", "2026.10.01", 35).json())
        assertEquals(setOf("kind", "app_version", "engine_version", "android_sdk", "site", "code", "link"), json.keys().asSequence().toSet())
        assertEquals("failure", json.getString("kind"))
        assertEquals("example.com", json.getString("site"))
        assertEquals("Failed", json.getString("code"))
        assertEquals(35, json.getInt("android_sdk"))
        assertEquals(link, json.getString("link"))
    }

    @Test fun linkIsSentVerbatimOnlyForWebLinks() {
        assertEquals(link, JSONObject(Report.failure(link, "Failed", "0.5.0", null, 35).json()).getString("link"))
        assertNull(Report.failure("content://example.com/x", "Failed", "0.5.0", null, 35).link)
        assertNull(Report.failure("https://user@example.com/x", "Failed", "0.5.0", null, 35).link)
        assertNull(Report.failure("https://example.com/" + "a".repeat(Report.LINK_CAP), "Failed", "0.5.0", null, 35).link)
    }

    @Test fun siteIsTheRegistrableNameOnly() {
        mapOf("https://www.example.com/a" to "example.com", "https://example.com" to "example.com",
            "https://news.example.co.uk/a" to "example.co.uk", "https://a.b.example.com.mr/x" to "example.com.mr",
            "https://EXAMPLE.org./x" to "example.org", "https://192.168.1.10/x" to null, "https://localhost/x" to null,
            "not a link" to null).forEach { (input, site) -> assertEquals(input, site, Report.site(input)) }
    }

    @Test fun unknownEngineAndOddVersionsAreLeftOutRatherThanSent() {
        val report = Report.failure(link, "bad code!", "1.0 https://example.com", "x/y", 35)
        assertEquals("?", report.appVersion)
        assertNull(report.engineVersion)
        assertNull(report.code)
        assertFalse(JSONObject(report.json()).has("engine_version"))
    }

    @Test fun crashTraceKeepsFramesAndDropsMessagesUrlsAndPaths() {
        val stored = """
            Timestamp: 2026-10-24
            App: 0.5.0 (500)
            Android SDK: 35
            Device model: Phone One
            Thread: main
            java.lang.IllegalStateException: https://example.com/watch?v=1
            java.lang.IllegalStateException
              at dev.ahmed.savlino.ui.Home.render:42
              at dev.ahmed.savlino.ui.Home.data:7
              at https://example.com/x.run:1
              at /storage/emulated/0/Movies/title.run:2
            Caused by: java.io.IOException
            Caused by: content://media/external/1
            Secret video title
            www.example.com/watch
        """.trimIndent()
        val trace = Report.crash(stored, "0.5.0", "2026.10.01", 35).trace!!
        assertEquals("""
            Timestamp: 2026-10-24
            App: 0.5.0 (500)
            Android SDK: 35
            Thread: main
            java.lang.IllegalStateException
              at dev.ahmed.savlino.ui.Home.render:42
              at dev.ahmed.savlino.ui.Home.data:7
            Caused by: java.io.IOException
        """.trimIndent(), trace)
        val json = JSONObject(Report.crash(stored, "0.5.0", null, 35).json())
        assertEquals("crash", json.getString("kind"))
        assertFalse(json.has("site")); assertFalse(json.has("link")); assertFalse(json.has("code"))
    }

    @Test fun traceIsCappedAndEmptyTraceIsLeftOut() {
        val long = (0 until 2000).joinToString("\n") { "  at safe.Worker.run:$it" }
        assertTrue(Report.crash(long, "0.5.0", null, 35).trace!!.length <= Report.TRACE_CAP)
        assertNull(Report.crash("only a message https://example.com", "0.5.0", null, 35).trace)
    }

    @Test fun sanitizerStripsUrlLikeAndPathLikeText() {
        assertEquals("failed at", Report.sanitize("failed at https://example.com/a?b=c"))
        assertEquals("open", Report.sanitize("open /data/user/0/app/files/x"))
        assertEquals("see", Report.sanitize("see www.example.com"))
        assertEquals("read", Report.sanitize("read content://media/1"))
        assertEquals("dev.ahmed.Foo.run:12", Report.sanitize("dev.ahmed.Foo.run:12"))
    }
}
