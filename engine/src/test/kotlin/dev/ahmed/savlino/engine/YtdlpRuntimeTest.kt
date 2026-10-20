package dev.ahmed.savlino.engine

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class YtdlpRuntimeTest {
    private class Table : ProcTable {
        val rows = linkedMapOf<Int, ProcProcess>()
        val signals = mutableListOf<Pair<Int, Int>>()
        var scans = 0
        override fun snapshot(): List<ProcProcess> { scans++; return rows.values.toList() }
        override fun read(pid: Int) = rows[pid]
        override fun signal(pid: Int, signal: Int) {
            signals += pid to signal
            if (signal == 9) rows.remove(pid)
        }
        fun fork() {
            rows[10] = ProcProcess(10, 1, 10)
            rows[11] = ProcProcess(11, 10, 11)
        }
    }
    private class Native : YtdlpRuntime {
        override val initialization = EngineInitialization()
        var action: (String, (String) -> Unit) -> String = { _, _ -> "result" }
        val fallbacks = AtomicInteger()
        var fallback: (String) -> Boolean = { true }
        var titleCancellation: (String) -> Boolean = { false }
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit) = action(processId, line)
        override fun destroy(processId: String): Boolean { fallbacks.incrementAndGet(); return fallback(processId) }
        override fun cancelTitleRewrite(processId: String) = titleCancellation(processId)
        override fun update() = Unit
    }
    @Test fun cancellationAfterYtdlpExitsTargetsOnlyRegisteredTitleRewrite() {
        val native = Native()
        val runtime = AndroidYtdlpRuntime(native, { error("No yt-dlp child during local title rewrite") })
        native.titleCancellation = { it == "title-job" }
        assertTrue(runtime.destroy("title-job"))
        assertFalse(runtime.destroy("unregistered-job"))
        assertEquals(0, native.fallbacks.get())
    }
    @Test fun unmatchedRootUsesLogicalIdFallbackAfterRegistrationDeadline() {
        val proc = Table(); val native = Native(); var now = 0L
        val runtime = AndroidYtdlpRuntime(native, { ProcessTree(proc) { false } }, { now }, {})
        assertFalse(runtime.destroy("not-registered")); assertEquals(0, native.fallbacks.get())
        native.action = { id, _ ->
            proc.fork()
            now = 299_000_000
            assertFalse(runtime.destroy(id)); assertEquals(0, native.fallbacks.get())
            now = 300_000_000
            assertTrue(runtime.destroy(id))
            "cancelled"
        }
        native.fallback = { id ->
            assertEquals("logical-id", id)
            proc.rows.remove(10) // Wrapper's process map stops Python even if argv never matched.
            true
        }
        assertEquals("cancelled", runtime.execute(emptyList(), "logical-id") {})
        assertEquals(1, native.fallbacks.get()); assertNull(proc.rows[10]); assertTrue(proc.signals.isEmpty())
        assertFalse(runtime.destroy("logical-id")); assertEquals(1, native.fallbacks.get())
    }
    @Test fun matchedTreeEscalatesBeforeCallingLogicalIdFallback() {
        val proc = Table(); val native = Native()
        val runtime = AndroidYtdlpRuntime(native, { ProcessTree(proc) { it.pid == 10 } }, pause = {})
        native.action = { id, _ -> proc.fork(); assertTrue(runtime.destroy(id)); "cancelled" }
        native.fallback = { id ->
            assertEquals("logical-id", id)
            assertTrue(proc.rows.isEmpty(), "fallback must follow tree escalation")
            false
        }
        runtime.execute(emptyList(), "logical-id") {}
        assertEquals(1, native.fallbacks.get())
        assertEquals(listOf(11 to 15, 10 to 15, 11 to 9, 10 to 9), proc.signals)
    }
    @Test fun executeFinallyKillsOrphanedChildrenOnSuccessAndFailureWithoutCancel() {
        for (failure in listOf(false, true)) {
            val proc = Table(); val native = Native()
            val runtime = AndroidYtdlpRuntime(native, { ProcessTree(proc) { it.pid == 10 } }, pause = {})
            native.action = { id, _ ->
                proc.fork(); runtime.observe(id)
                proc.rows.remove(10); proc.rows[11] = proc.rows.getValue(11).copy(parentPid = 1)
                if (failure) throw IllegalStateException("native failure")
                "result"
            }
            if (failure) assertFailsWith<IllegalStateException> { runtime.execute(emptyList(), "logical-id") {} }
            else assertEquals("result", runtime.execute(emptyList(), "logical-id") {})
            assertTrue(proc.rows.isEmpty(), "execute must finish child cleanup before unregistering")
            assertEquals(listOf(11 to 15, 11 to 9), proc.signals)
            assertFalse(runtime.destroy("logical-id")); assertEquals(0, native.fallbacks.get())
        }
    }
    @Test fun finalSweepExcludesLiveRootAndPreservesResultWhenProcSignalFails() {
        val proc = Table(); val native = Native()
        val throwing = object : ProcTable by proc {
            override fun signal(pid: Int, signal: Int) {
                assertEquals(11, pid, "final sweep must never signal Python")
                proc.signals += pid to signal
                throw SecurityException("denied")
            }
        }
        val runtime = AndroidYtdlpRuntime(native, { ProcessTree(throwing) { it.pid == 10 } }, pause = {})
        native.action = { id, _ -> proc.fork(); runtime.observe(id); "result" }
        assertEquals("result", runtime.execute(emptyList(), "logical-id") {})
        assertEquals(listOf(11 to 15), proc.signals)
        assertNotNull(proc.rows[10])
        assertFalse(runtime.destroy("logical-id"))
    }
    @Test fun finalSweepWithOnlyLivePythonDoesNotSignalOrWait() {
        val proc = Table(); val native = Native()
        val runtime = AndroidYtdlpRuntime(native, { ProcessTree(proc) { it.pid == 10 } },
            pause = { fail("no live child requires a grace interval") })
        native.action = { id, _ ->
            proc.rows[10] = ProcProcess(10, 1, 10); runtime.observe(id); "result"
        }
        assertEquals("result", runtime.execute(emptyList(), "logical-id") {})
        assertTrue(proc.signals.isEmpty()); assertNotNull(proc.rows[10])
    }
    @Test fun outputBurstsDoNotScanProcPerLine() {
        val proc = Table(); val native = Native()
        val runtime = AndroidYtdlpRuntime(native, { ProcessTree(proc) { false } }, pause = {})
        var delivered = 0
        native.action = { _, line ->
            val before = proc.scans
            repeat(1000) { line("progress") }
            assertEquals(before, proc.scans, "only the polling canceller and final sweep should scan")
            "result"
        }
        assertEquals("result", runtime.execute(emptyList(), "logical-id") { delivered++ })
        assertEquals(1000, delivered)
    }
}
