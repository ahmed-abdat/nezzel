package dev.ahmed.savlino.ui

import android.os.SystemClock
import android.util.Log
import dev.ahmed.savlino.BuildConfig

/** Debug-only elapsed times. Labels are constants; never pass URLs or engine output. */
internal object EngineTiming {
    private val confirmations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun startDownload(jobId: String) {
        if (!BuildConfig.DEBUG) return
        val now = SystemClock.elapsedRealtime()
        confirmations.entries.removeIf { now - it.value >= 60 * 60 * 1000L }
        confirmations.putIfAbsent(jobId, now)
    }
    fun firstDownloadBytes(jobId: String, bytes: Long) {
        if (!BuildConfig.DEBUG || bytes <= 0) return
        val start = confirmations.remove(jobId) ?: return
        Log.d("NezzelTiming", "download_start ${SystemClock.elapsedRealtime() - start}ms")
    }
    fun forgetDownload(jobId: String) { if (BuildConfig.DEBUG) confirmations.remove(jobId) }

    suspend fun <T> measure(phase: String, block: suspend () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val start = SystemClock.elapsedRealtime()
        try { return block() }
        finally { Log.d("NezzelTiming", "$phase ${SystemClock.elapsedRealtime() - start}ms") }
    }
}
