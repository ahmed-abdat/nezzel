package dev.ahmed.savlino.engine

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One local stream-copy process per transfer; cancellation also works in the gap after yt-dlp exits. */
internal class TitleFfmpegProcess(private val start: (List<String>) -> Process) {
    private class Execution(val child: Process, val cancelled: AtomicBoolean = AtomicBoolean())
    private val executions = ConcurrentHashMap<String, Execution>()

    fun execute(args: List<String>, processId: String): Boolean {
        val execution = Execution(start(args))
        executions[processId] = execution
        try {
            val exited = execution.child.waitFor(120, TimeUnit.SECONDS)
            return exited && !execution.cancelled.get() && execution.child.exitValue() == 0
        } finally {
            try { terminateAndJoin(execution.child) } finally { executions.remove(processId, execution) }
        }
    }

    fun cancel(processId: String): Boolean {
        val execution = executions[processId] ?: return false
        execution.cancelled.set(true)
        terminateAndJoin(execution.child)
        return true
    }

    private fun terminateAndJoin(child: Process) {
        if (child.isAlive) child.destroyForcibly()
        // No output reader threads/descendants: direct FFmpeg, both streams redirected to /dev/null.
        // Join even after interruption so callers never delete staging while the child can still write it.
        var interrupted = false
        while (true) {
            try { child.waitFor(); break } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}
