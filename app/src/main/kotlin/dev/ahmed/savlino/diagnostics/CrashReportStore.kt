package dev.ahmed.savlino.diagnostics

import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Collections
import java.util.IdentityHashMap

internal data class CrashMetadata(val versionName: String, val versionCode: Int, val sdk: Int)

/**
 * Local, bounded diagnostics: one file, overwritten by the next crash. Never uses Throwable.toString/message or
 * stack-frame filenames, and no device model.
 */
internal class CrashReportStore(private val directory: File, private val metadata: CrashMetadata,
                                 private val now: () -> Long = System::currentTimeMillis) {
    fun record(thread: Thread, error: Throwable) = synchronized(lock) {
        val report = render(thread, error)
        check(directory.isDirectory || directory.mkdirs())
        val existing = reports()
        // Monotonic filenames preserve order even for simultaneous crashes or a clock moving backwards.
        val last = existing.firstOrNull()?.name?.removePrefix("crash-")?.removeSuffix(".txt")?.toLongOrNull() ?: 0L
        val sequence = maxOf(now(), last + 1)
        existing.drop(KEEP - 1).forEach { check(it.delete()) }
        File(directory, "crash-${sequence.toString().padStart(19, '0')}.txt").writeBytes(report)
    }

    fun hasReports(): Boolean = synchronized(lock) { reports().any { it.length() > 0 } }

    /** Reads at most 16 KiB, even if a stored file was corrupted. Newest report comes first. */
    fun latestReports(): String? = synchronized(lock) {
        reports().take(KEEP).mapNotNull { file ->
            val bytes = ByteArray(MAX_BYTES)
            val count = file.inputStream().use { input ->
                var total = 0
                while (total < bytes.size) {
                    val read = input.read(bytes, total, bytes.size - total)
                    if (read <= 0) break
                    total += read
                }
                total
            }
            if (count == 0) null else String(bytes, 0, count, Charsets.UTF_8)
        }.takeIf { it.isNotEmpty() }?.joinToString("\n\n---\n\n")
    }

    fun deleteReports() = synchronized(lock) { reports().forEach { check(it.delete()) } }

    /** The stored report, if any; one older than [MAX_AGE_MS] is deleted instead. */
    fun pending(): String? = synchronized(lock) {
        val newest = reports().firstOrNull() ?: return null
        val written = newest.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: 0L
        if (now() - written > MAX_AGE_MS || written > now() + MAX_AGE_MS) { deleteReports(); return null }
        latestReports()
    }

    /** Reads the pending report and deletes it, for a sender that owns it from here on. */
    fun takePending(): String? = synchronized(lock) { pending().also { deleteReports() } }

    private fun reports(): List<File> = directory.listFiles().orEmpty()
        .filter { it.isFile && reportName.matches(it.name) }.sortedByDescending { it.name }

    private fun render(thread: Thread, error: Throwable): ByteArray {
        val output = ByteArrayOutputStream(MAX_BYTES)
        fun line(text: String) {
            val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
            var length = minOf(bytes.size, MAX_BYTES - output.size())
            // Do not split a UTF-8 code point at the byte cap.
            if (length < bytes.size) while (length > 0 && bytes[length].toInt() and 0xc0 == 0x80) length--
            output.write(bytes, 0, length)
        }
        // Day only: the hour adds nothing to fixing a crash.
        line("Timestamp: ${LocalDate.ofInstant(Instant.ofEpochMilli(now()), ZoneOffset.UTC)}")
        line("App: ${label(metadata.versionName)} (${metadata.versionCode})")
        line("Android SDK: ${metadata.sdk}")
        // Names are mutable and can contain a URL or an unquoted title. Only runtime patterns survive.
        val threadName = thread.name
        line("Thread: ${if (safeThread.matches(threadName)) threadName else "[redacted]"}")
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = error
        var depth = 0
        while (current != null && output.size() < MAX_BYTES && depth < 32 && seen.add(current)) {
            line((if (depth == 0) "" else "Caused by: ") + identifier(current.javaClass.name))
            for (frame in current.stackTrace) {
                if (output.size() >= MAX_BYTES) break
                line("  at ${identifier(frame.className)}.${identifier(frame.methodName)}:${frame.lineNumber}")
            }
            current = current.cause
            depth++
        }
        // Suppressed exception messages and native/Python diagnostics are deliberately never rendered.
        return output.toByteArray()
    }

    private fun label(value: String) = if (safeLabel.matches(value)) value else "[redacted]"
    private fun identifier(value: String) = if (value.length <= 256 && safeIdentifier.matches(value)) value else "[redacted]"

    companion object {
        private const val KEEP = 1
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val MAX_BYTES = 16 * 1024
        // Settings and the handler may hold different store objects; IO must still serialize process-wide.
        private val lock = Any()
        private val reportName = Regex("crash-[0-9]{19}\\.txt")
        private val safeLabel = Regex("[\\p{L}\\p{N} ._()+-]{1,120}")
        private val safeIdentifier = Regex("[A-Za-z0-9_.$<>-]+")
        private val safeThread = Regex("main|Thread-[0-9]+|DefaultDispatcher-worker-[0-9]+|pool-[0-9]+-thread-[0-9]+|WM\\.task-[0-9]+|androidx\\.work-[0-9]+")
    }
}

/** Recording is best effort; the original Android handler retains normal process termination. */
internal class CrashExceptionHandler(
    private val store: CrashReportStore,
    private val previous: Thread.UncaughtExceptionHandler,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { store.record(thread, error) }
        catch (_: Throwable) { /* No raw exception/report logging, including IO and out-of-memory failures. */ }
        finally { previous.uncaughtException(thread, error) }
    }
}
