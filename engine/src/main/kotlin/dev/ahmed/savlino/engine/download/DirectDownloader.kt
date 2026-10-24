package dev.ahmed.savlino.engine.download

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.EngineEvent
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

internal class DirectRequest(val url: String, val headers: Map<String, String>) {
    override fun toString() = "DirectRequest"
}
internal interface DirectResponse : Closeable {
    val code: Int
    fun header(name: String): String?
    val body: InputStream
}
internal interface DirectTransport : Closeable {
    fun execute(url: String, headers: Map<String, String>): DirectResponse
    fun cancel()
}
internal class DirectPolicyRejected : IOException("Transfer policy rejected")

internal sealed interface DirectResult {
    data object Complete : DirectResult
    data object Fallback : DirectResult
    data object Expired : DirectResult
    data class Failed(val error: DownloadError, val cause: DownloadFailureCause = error.cause) : DirectResult
}
internal class DirectDownloader(private val transport: () -> DirectTransport,
    private val saveRecord: (File, DirectResume.Record) -> Unit = DirectResume::write) {
    fun download(request: DirectRequest, directory: File, cancelled: () -> Boolean,
        expired: () -> Boolean, progress: (EngineEvent) -> Unit,
        registerCancel: ((() -> Unit)?) -> Unit = {}): DirectResult {
        val part = File(directory, DirectResume.PART)
        val output = File(directory, "output.mp4")
        val recordFile = File(directory, DirectResume.NAME)
        val lock = Any()
        val stopped = AtomicBoolean()
        var keepPartial = true
        var stream: FileOutputStream? = null
        var representation: DirectResume.Record? = null
        var received = 0L
        var synced = 0L
        fun interrupted(): DirectResult.Failed? = when {
            expired() -> DirectResult.Failed(DownloadError.Network, DownloadFailureCause.STALL)
            cancelled() || stopped.get() -> DirectResult.Failed(DownloadError.Cancelled)
            else -> null
        }
        // Caller holds lock: data reaches storage before the atomic sidecar records its durable offset.
        fun checkpoint() {
            val file = stream ?: return
            val record = representation ?: return
            if (!keepPartial || received <= synced) return
            val next = record.copy(syncedBytes = received)
            storage { file.fd.sync(); saveRecord(directory, next) }
            synced = received
            representation = next
        }
        fun discard(result: DirectResult): DirectResult = synchronized(lock) {
            interrupted()?.let { return@synchronized it }
            keepPartial = false
            storage { stream?.close() }; stream = null
            for (file in listOf(part, recordFile)) {
                if (Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && !file.delete())
                    return@synchronized DirectResult.Failed(DownloadError.ExecutionRestricted)
            }
            result
        }
        fun publish() {
            storage {
                Files.move(part.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
                if (!recordFile.delete()) throw IOException("Storage failure")
                FileOutputStream(File(directory, "result-path.txt")).use {
                    it.write(output.path.toByteArray()); it.fd.sync()
                }
            }
            keepPartial = false
        }
        var wire: DirectTransport? = null
        try {
            require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) && directory.listFiles()?.all {
                !Files.isSymbolicLink(it.toPath()) && it.isFile && it.name in setOf(DirectResume.PART, DirectResume.NAME)
            } == true) { "Invalid transfer staging" }
            interrupted()?.let { return it }
            val active = transport(); wire = active
            registerCancel {
                synchronized(lock) {
                    if (!stopped.get()) {
                        // Finish this checkpoint before cancel returns; nothing writes after stopped becomes true.
                        try { checkpoint() } catch (_: Exception) { /* Resume will truncate to the older checkpoint. */ }
                        stopped.set(true)
                    }
                }
                try { active.cancel() } catch (_: Exception) { /* Fixed cancellation result only. */ }
            }
            interrupted()?.let { return it }
            val saved = DirectResume.read(directory)?.takeIf { it.native }
            val lengthBefore = part.length()
            val durable = saved?.syncedBytes ?: 0 // Pre-checkpoint v1 records cannot prove a durable prefix.
            val savedTotal = saved?.total
            val complete = savedTotal != null && savedTotal > 0 && durable == savedTotal && lengthBefore >= durable
            var offset = if ((complete || saved?.validator != null) && durable <= lengthBefore &&
                (savedTotal == null || durable <= savedTotal)) durable else 0
            synchronized(lock) {
                interrupted()?.let { return it }
                if (part.exists() && lengthBefore != offset) storage {
                    RandomAccessFile(part, "rw").use { it.setLength(offset); it.fd.sync() }
                }
                if (complete) publish()
            }
            if (lengthBefore > offset) progress(EngineEvent.Restarting(1))
            if (complete) {
                progress(EngineEvent.Progress(offset, Size.Exact(offset)))
                return DirectResult.Complete
            }
            val headers = request.headers.filterKeys { it.lowercase() !in setOf(
                "range", "if-range", "accept-encoding", "host", "connection", "content-length", "transfer-encoding") }.toMutableMap()
            headers["Accept-Encoding"] = "identity"
            if (offset > 0) { headers["Range"] = "bytes=$offset-"; headers["If-Range"] = saved!!.validator!! }
            active.execute(request.url, headers).use { response ->
                interrupted()?.let { return it }
                if (response.code == 403) return discard(DirectResult.Expired)
                if (response.code !in setOf(200, 206)) return discard(DirectResult.Fallback)
                if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) return discard(DirectResult.Fallback)
                val lengthText = response.header("Content-Length")
                val length = lengthText?.toLongOrNull()?.takeIf { it >= 0 }
                if (lengthText != null && length == null) return discard(DirectResult.Fallback)
                val etag = response.header("ETag")?.takeIf { DirectResume.safeValidator(it) && !it.startsWith("W/") && it.startsWith('"') && it.endsWith('"') }
                val modified = response.header("Last-Modified")?.takeIf(DirectResume::safeValidator)
                val kind = if (etag != null) "ETag" else if (modified != null) "Last-Modified" else "none"
                val validator = etag ?: modified
                var total = length
                if (response.code == 206) {
                    val range = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)").matchEntire(response.header("Content-Range").orEmpty())
                        ?: return discard(DirectResult.Fallback)
                    val start = range.groupValues[1].toLongOrNull()
                    val end = range.groupValues[2].toLongOrNull()
                    total = range.groupValues[3].toLongOrNull()
                    if (start != offset || end == null || total == null || total <= 0 || end != total - 1 || end < offset ||
                        (length != null && length != total - offset) ||
                        (offset > 0 && (saved?.validator != response.header(saved?.kind.orEmpty()) || saved?.total?.let { it != total } == true)))
                        return discard(DirectResult.Fallback)
                } else if (offset > 0) {
                    offset = 0
                    progress(EngineEvent.Restarting(1))
                }
                // EOF alone cannot prove an unknown-length MP4 complete. yt-dlp owns that path.
                val expected = total ?: return discard(DirectResult.Fallback)
                val file = synchronized(lock) {
                    interrupted()?.let { return it }
                    val opened = storage { FileOutputStream(part, offset > 0) }
                    stream = opened; received = offset; synced = offset
                    val record = DirectResume.Record(kind, validator, expected, offset)
                    representation = record
                    try { storage { opened.fd.sync(); saveRecord(directory, record) }; opened }
                    catch (error: Exception) { stream = null; opened.close(); throw error }
                }
                file.use {
                    try {
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            interrupted()?.let { return it }
                            val count = response.body.read(buffer)
                            interrupted()?.let { return it }
                            if (count < 0) break
                            if (count == 0) continue
                            if (count > expected - received) return discard(DirectResult.Fallback)
                            val event = synchronized(lock) {
                                interrupted()?.let { return it }
                                storage { file.write(buffer, 0, count) }
                                received += count
                                if (received - synced >= CHECKPOINT_BYTES) checkpoint()
                                EngineEvent.Progress(received, Size.Exact(expected))
                            }
                            // This may re-enter engine/coordinator cancellation. Never hold the writer lock here.
                            progress(event)
                        }
                        if (received != expected) return DirectResult.Failed(DownloadError.Network)
                        if (received <= 0) return discard(DirectResult.Fallback)
                    } finally {
                        synchronized(lock) {
                            try { if (!stopped.get()) checkpoint() } finally { stream = null }
                        }
                    }
                }
                synchronized(lock) {
                    interrupted()?.let { return it }
                    publish()
                }
                return DirectResult.Complete
            }
        } catch (_: DirectPolicyRejected) {
            return discard(DirectResult.Fallback)
        } catch (_: IllegalArgumentException) {
            return DirectResult.Failed(DownloadError.ExecutionRestricted)
        } catch (error: Exception) {
            interrupted()?.let { return it }
            // No raw exception or cause crosses this seam.
            return when {
                error is StorageFailure -> DirectResult.Failed(if (error.noSpace) DownloadError.InsufficientSpace else DownloadError.Failed)
                noSpace(error) -> DirectResult.Failed(DownloadError.InsufficientSpace)
                error is java.nio.file.FileSystemException -> DirectResult.Failed(DownloadError.Failed)
                else -> DirectResult.Failed(DownloadError.Network)
            }
        } finally {
            registerCancel(null)
            try { wire?.close() } catch (_: Exception) { /* URL-free seam. */ }
        }
    }
    private class StorageFailure(val noSpace: Boolean) : IOException("Storage failure")
    private fun <T> storage(block: () -> T): T = try { block() }
        catch (error: IOException) { throw StorageFailure(noSpace(error)) }
    private fun noSpace(error: Throwable): Boolean = generateSequence(error) { it.cause }.take(16).any {
        it.message?.let { text -> text.contains("ENOSPC", true) || text.contains("No space left", true) } == true
    }
    private companion object { const val CHECKPOINT_BYTES = 8L * 1024 * 1024 }
}
