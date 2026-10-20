package dev.ahmed.savlino.diagnostics

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashReportStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val metadata = CrashMetadata("0.4.2", 310, 35, "SM-A346B")
    private fun store(directory: File = File(temporary.root, "crash")) = CrashReportStore(directory, metadata)
    private fun failure(): Throwable = try {
        throw IllegalStateException("https://example.invalid/x content://private/1 /storage/title 'Secret title'",
            IllegalArgumentException("https://example.invalid/x content://private/2 /data/private \"Secret title\""))
    } catch (error: IllegalStateException) { error }

    @Test fun messagesAndCauseMessagesNeverReachDiskButFramesAndMetadataDo() {
        val store = store()
        store.record(Thread("main"), failure())
        val text = store.latestReports()!!
        listOf("https:", "example.invalid", "content:", "/storage", "/data", "Secret title").forEach {
            assertFalse("Leaked $it", it in text)
        }
        listOf("0.4.2", "310", "35", "SM-A346B", "Thread: main", "Timestamp:",
            "java.lang.IllegalStateException", "Caused by: java.lang.IllegalArgumentException",
            "dev.ahmed.savlino.diagnostics.CrashReportStoreTest.failure").forEach { assertTrue("Missing $it", it in text) }
        val files = File(temporary.root, "crash").listFiles()!!
        assertEquals(1, files.size)
        assertEquals(text, files.single().readText())
    }

    @Test fun customThreadNamesAndForgedFramePathsCannotLeakPrivateData() {
        val store = store()
        val error = failure().apply {
            stackTrace = arrayOf(StackTraceElement("https://example.invalid/x", "content://private/2", "/data/private/Secret title", 9),
                StackTraceElement("safe.Worker", "run", "/storage/Secret title", 42))
        }
        store.record(Thread("Unquoted private video title"), error)
        val text = store.latestReports()!!
        assertTrue("Thread: [redacted]" in text)
        assertTrue("safe.Worker.run:42" in text)
        listOf("https:", "content:", "/data", "/storage", "Secret title", "Unquoted private video title").forEach {
            assertFalse("Leaked $it", it in text)
        }
    }

    @Test fun oversizedStacksAreCappedInBytes() {
        val store = store()
        val error = IllegalStateException().apply {
            stackTrace = Array(4000) { StackTraceElement("safe.Worker", "run", "Worker.kt", it) }
        }
        store.record(Thread("DefaultDispatcher-worker-2"), error)
        val file = File(temporary.root, "crash").listFiles()!!.single()
        assertTrue(file.length() in 1..16384)
        assertTrue(file.readText().contains("safe.Worker.run:0"))
        assertFalse(file.readText().contains("safe.Worker.run:3999"))
    }

    @Test fun onlyLastThreeReportsAreKeptAndSharedNewestFirst() {
        val store = store()
        repeat(5) { index ->
            // Different instances still serialize rotation, reads and deletion for this process.
            store().record(Thread("main"), IllegalStateException().apply {
                stackTrace = arrayOf(StackTraceElement("safe.Crash$index", "run", "Worker.kt", index))
            })
        }
        assertEquals(3, File(temporary.root, "crash").listFiles()!!.size)
        val text = store.latestReports()!!
        assertFalse("safe.Crash0" in text); assertFalse("safe.Crash1" in text)
        assertTrue(text.indexOf("safe.Crash4") < text.indexOf("safe.Crash3"))
        assertTrue(text.indexOf("safe.Crash3") < text.indexOf("safe.Crash2"))
        store.deleteReports()
        assertFalse(store.hasReports())
        assertNull(store.latestReports())
        assertTrue(File(temporary.root, "crash").listFiles().orEmpty().isEmpty())
    }

    @Test(timeout = 2000) fun cyclicCausesAndSuppressedMessagesCannotExpandOrLeak() {
        val first = IllegalStateException("https://example.invalid/x")
        val second = IllegalArgumentException("content://private/1")
        first.initCause(second); second.initCause(first)
        first.addSuppressed(IllegalStateException("Secret title"))
        val store = store()
        store.record(Thread("main"), first)
        val text = store.latestReports()!!
        assertTrue(text.contains("java.lang.IllegalStateException"))
        assertTrue(text.contains("java.lang.IllegalArgumentException"))
        assertFalse(text.contains("https:")); assertFalse(text.contains("content:")); assertFalse(text.contains("Secret title"))
        assertTrue(text.toByteArray().size <= 16384)
    }

    @Test fun recordingAlwaysDelegatesOriginalThrowableAfterWriting() {
        val store = store(); val thread = Thread("main"); val error = failure()
        var calls = 0
        val handler = CrashExceptionHandler(store, Thread.UncaughtExceptionHandler { receivedThread, receivedError ->
            calls++
            assertSame(thread, receivedThread); assertSame(error, receivedError)
            assertTrue(store.hasReports())
        })
        handler.uncaughtException(thread, error)
        assertEquals(1, calls)
    }

    @Test fun failedWriteStillDelegatesOriginalThrowable() {
        val blockedDirectory = temporary.newFile("not-a-directory")
        val thread = Thread("main"); val error = failure(); var calls = 0
        val handler = CrashExceptionHandler(store(File(blockedDirectory, "crash")), Thread.UncaughtExceptionHandler { t, e ->
            calls++; assertSame(thread, t); assertSame(error, e)
        })
        handler.uncaughtException(thread, error)
        assertEquals(1, calls)
    }
}
