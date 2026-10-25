package dev.ahmed.savlino.engine.youtube

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Never carries URLs, ids or exception details. */
internal sealed interface FastResult {
    class Json(val json: String) : FastResult
    /** Anything unusable, including cancellation: use yt-dlp. */
    data object Miss : FastResult
    /** No HTTP response at all in this run; yt-dlp would wait on the same dead network. */
    data object Unreachable : FastResult
}

/** Blocking on the caller thread. Never retain failures. */
internal interface YoutubeFastSource {
    fun infoJson(url: String, operationId: String, timeoutMs: Long): FastResult
    fun playlist(source: dev.ahmed.savlino.domain.LinkRevision, token: dev.ahmed.savlino.engine.PlaylistPageToken?,
        operationId: String, timeoutMs: Long): dev.ahmed.savlino.engine.PlaylistPage? = null
    fun cancel(operationId: String)
}

internal class NpeFastSource(private val downloader: NpeDownloader = NpeDownloader.create()) : YoutubeFastSource {
    private val installed by lazy { downloader.install() }
    private val operations = ConcurrentHashMap<String, AtomicBoolean>()

    override fun infoJson(url: String, operationId: String, timeoutMs: Long): FastResult {
        if (timeoutMs <= 0) return FastResult.Miss
        val cancelled = AtomicBoolean()
        if (operations.putIfAbsent(operationId, cancelled) != null) return FastResult.Miss
        val started = System.nanoTime()
        var unreachable = false
        fun miss() = if (unreachable && !cancelled.get()) FastResult.Unreachable else FastResult.Miss
        return try {
            installed
            if (!ServiceList.YouTube.streamLHFactory.acceptUrl(url)) return FastResult.Miss
            downloader.operation(operationId, timeoutMs, unreachable = { unreachable = true }) {
                if (cancelled.get()) null
                else NpeInfoJson.build(StreamInfo.getInfo(ServiceList.YouTube, url))
            }?.takeIf {
                !cancelled.get() && System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            }?.let(FastResult::Json) ?: miss()
        } catch (_: Exception) { miss() }
        catch (_: LinkageError) { FastResult.Miss }
        finally { operations.remove(operationId, cancelled) }
    }

    override fun playlist(source: dev.ahmed.savlino.domain.LinkRevision,
        token: dev.ahmed.savlino.engine.PlaylistPageToken?, operationId: String, timeoutMs: Long): dev.ahmed.savlino.engine.PlaylistPage? {
        if (timeoutMs <= 0) return null
        val cancelled = AtomicBoolean()
        if (operations.putIfAbsent(operationId, cancelled) != null) return null
        val started = System.nanoTime()
        return try {
            installed
            downloader.operation(operationId, timeoutMs) {
                if (cancelled.get()) null else NpePlaylistPage.read(source, token)
            }?.takeIf { !cancelled.get() && System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(timeoutMs) }
        } catch (_: Exception) { null }
        catch (_: LinkageError) { null }
        finally { operations.remove(operationId, cancelled) }
    }

    override fun cancel(operationId: String) {
        operations[operationId]?.set(true)
        try { downloader.cancel(operationId) }
        catch (_: Exception) { /* Fall back without retaining private extraction details. */ }
        catch (_: LinkageError) { /* Older Android runtimes may lack a desugared API. */ }
    }
}
