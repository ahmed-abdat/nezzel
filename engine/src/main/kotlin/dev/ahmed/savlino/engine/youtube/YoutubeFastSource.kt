package dev.ahmed.savlino.engine.youtube

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Blocking on the caller thread. Every unusable result means use yt-dlp; never retain failures. */
internal interface YoutubeFastSource {
    fun infoJson(url: String, operationId: String, timeoutMs: Long): String?
    fun cancel(operationId: String)
}

internal class NpeFastSource(private val downloader: NpeDownloader = NpeDownloader.create()) : YoutubeFastSource {
    private val installed by lazy { downloader.install() }
    private val operations = ConcurrentHashMap<String, AtomicBoolean>()

    override fun infoJson(url: String, operationId: String, timeoutMs: Long): String? {
        if (timeoutMs <= 0) return null
        val cancelled = AtomicBoolean()
        if (operations.putIfAbsent(operationId, cancelled) != null) return null
        val started = System.nanoTime()
        return try {
            installed
            if (!ServiceList.YouTube.streamLHFactory.acceptUrl(url)) return null
            downloader.operation(operationId, timeoutMs) {
                if (cancelled.get()) null
                else NpeInfoJson.build(StreamInfo.getInfo(ServiceList.YouTube, url))
            }?.takeIf {
                !cancelled.get() && System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            }
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
