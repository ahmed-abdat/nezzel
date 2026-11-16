package dev.ahmed.savlino.diagnostics

import android.app.Application
import io.sentry.Breadcrumb
import io.sentry.JsonSerializer
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.DebugImage
import io.sentry.protocol.DebugMeta
import io.sentry.protocol.Device
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import io.sentry.protocol.User
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.StringWriter
import java.util.Date

@RunWith(RobolectricTestRunner::class)
// SDK coverage: SentryReports has no API-level branch.
@Config(sdk = [29], application = Application::class)
class SentryReportsTest {
    private fun frame() = SentryStackFrame().apply {
        module = "dev.ahmed.savlino.Home"; function = "open"; filename = "Home.kt"; lineno = 12; isInApp = true
        absPath = "/SECRET/path"; vars = mapOf("url" to "SECRET"); contextLine = "SECRET"; preContext = listOf("SECRET")
        `package` = "SECRET"; unknown = mapOf("SECRET" to "SECRET")
    }

    /** Every field that could name the person, phone or link carries the marker; the sent JSON may not contain it. */
    @Test fun `a sent crash report is the allow-list only`() {
        val event = SentryEvent(Date(5_000))
        event.release = "0.5.9+1153"
        event.user = User().apply { id = "SECRET"; ipAddress = "SECRET" }
        event.serverName = "SECRET"; event.transaction = "SECRET"; event.dist = "SECRET"; event.environment = "SECRET"
        event.logger = "SECRET"; event.fingerprints = listOf("SECRET")
        event.message = Message().apply { message = "SECRET" }
        event.request = Request().apply { url = "https://SECRET" }
        event.setExtra("SECRET", "SECRET"); event.setTag("SECRET", "SECRET"); event.setModule("SECRET", "1")
        event.addBreadcrumb(Breadcrumb.http("https://SECRET", "GET"))
        event.unknown = mapOf("SECRET" to "SECRET")
        event.threads = listOf(SentryThread().apply { name = "SECRET"; stacktrace = SentryStackTrace(listOf(frame())) })
        event.exceptions = listOf(SentryException().apply {
            type = "IllegalStateException"; module = "java.lang"; value = "https://SECRET/watch"; stacktrace = SentryStackTrace(listOf(frame()))
        })
        event.debugMeta = DebugMeta().apply {
            images = listOf(DebugImage().apply { type = "proguard"; uuid = "11111111-2222-3333-4444-555555555555"; codeFile = "SECRET" },
                DebugImage().apply { type = "elf"; codeFile = "SECRET" })
        }
        event.contexts.setDevice(Device().apply { model = "SECRET"; id = "SECRET"; name = "SECRET" })
        event.contexts.setOperatingSystem(OperatingSystem().apply { name = "Android"; version = "16"; kernelVersion = "SECRET"; build = "SECRET" })
        event.contexts.put("SECRET", "SECRET")

        val json = StringWriter().also { JsonSerializer(SentryOptions.empty()).serialize(SentryReports.scrub(event, "samsung"), it) }.toString()

        assertFalse(json, "SECRET" in json)
        listOf("\"IllegalStateException\"", "\"dev.ahmed.savlino.Home\"", "\"open\"", "\"Home.kt\"", "\"lineno\":12", "\"0.5.9+1153\"",
            "\"brand\":\"samsung\"", "\"Android\"", "\"16\"", "11111111-2222-3333-4444-555555555555").forEach { assertTrue("$it in $json", it in json) }
    }

    @Test fun `reporting starts only with an address, wraps the app's own crash handler once, and off deletes unsent reports`() {
        val app = RuntimeEnvironment.getApplication()
        val original = Thread.getDefaultUncaughtExceptionHandler()
        val cache = File(app.cacheDir, "sentry")
        try {
            CrashReports.install(app)
            val own = Thread.getDefaultUncaughtExceptionHandler()

            SentryReports.apply(app, on = true, dsn = "")
            assertFalse(Sentry.isEnabled())
            assertSame(own, Thread.getDefaultUncaughtExceptionHandler())

            SentryReports.apply(app, on = true, dsn = "https://key@example.invalid/1")
            assertTrue(Sentry.isEnabled())
            val wrapped = Thread.getDefaultUncaughtExceptionHandler()
            assertNotSame(own, wrapped)
            SentryReports.apply(app, on = true, dsn = "https://key@example.invalid/1")
            assertSame(wrapped, Thread.getDefaultUncaughtExceptionHandler())
            File(cache, "unsent.envelope").apply { parentFile!!.mkdirs(); writeText("x") }

            SentryReports.apply(app, on = false, dsn = "https://key@example.invalid/1")
            assertFalse(Sentry.isEnabled())
            assertSame(own, Thread.getDefaultUncaughtExceptionHandler())
            // The SDK may rewrite its own empty state file while closing; what must be gone is every unsent report.
            assertEquals(emptyList<String>(), cache.walkTopDown().filter { it.name.endsWith(".envelope") }.map { it.name }.toList())

            SentryReports.apply(app, on = true, dsn = "https://key@example.invalid/1")
            assertTrue(Sentry.isEnabled())
            assertNotSame(own, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Sentry.close()
            Thread.setDefaultUncaughtExceptionHandler(original)
            cache.deleteRecursively()
        }
    }

    /** Through the real SDK pipeline: what reaches the transport is what would reach the network. */
    @Test fun `a real captured crash leaves the phone without its error text or the phone's identity`() {
        val app = RuntimeEnvironment.getApplication()
        val sent = java.util.concurrent.CopyOnWriteArrayList<String>()
        try {
            SentryReports.apply(app, on = true, dsn = "https://key@example.invalid/1") { options ->
                options.setTransportFactory { o, _ ->
                    object : io.sentry.transport.ITransport {
                        override fun send(envelope: io.sentry.SentryEnvelope, hint: io.sentry.Hint) {
                            sent += java.io.ByteArrayOutputStream().also { o.serializer.serialize(envelope, it) }.toString(Charsets.UTF_8.name())
                        }
                        override fun flush(timeoutMillis: Long) {}
                        override fun getRateLimiter() = io.sentry.transport.RateLimiter(o)
                        override fun close(isRestarting: Boolean) {}
                        override fun close() {}
                    }
                }
            }
            Sentry.captureException(IllegalStateException("https://SECRET/watch /storage/SECRET.mp4"))
            Sentry.flush(5_000)

            val text = sent.joinToString("\n")
            assertTrue(text, "IllegalStateException" in text)
            assertFalse(text, "SECRET" in text)
            listOf("\"user\"", "\"device\"", "\"breadcrumbs\"", "\"threads\"", "installation", "\"model\"").forEach { assertFalse("$it in $text", it in text) }
        } finally {
            Sentry.close()
            File(app.cacheDir, "sentry").deleteRecursively()
        }
    }
}
