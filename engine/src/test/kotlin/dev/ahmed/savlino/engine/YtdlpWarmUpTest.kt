package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlin.test.*

class YtdlpWarmUpTest {
    private class Runtime(private val warmed: MutableMap<String, String>, var version: String = "2025.11.12") : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val extractions = AtomicInteger()
        val probes = AtomicInteger()
        var failProbe = false
        var failUpdateProbe = false
        var extract: () -> Unit = {}
        val calls = java.util.Collections.synchronizedList(mutableListOf<List<String>>())
        override fun initialize() { extractions.incrementAndGet(); extract() }
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            calls += args
            return if ("--version" in args) {
                probes.incrementAndGet()
                check(!failProbe)
                version
            } else {
                check("--dump-json" in args && "--skip-download" in args)
                """{"formats":[{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}]}"""
            }
        }
        override fun warmedVersion() = warmed[version]
        override fun rememberWarmedVersion(version: String) { warmed[this.version] = version }
        override fun update() { version = "2026.08.19"; failProbe = failUpdateProbe }
        override fun restoreBundledYtdlp() { version = "2025.11.12"; failProbe = false }
        override fun destroy(processId: String) = true
    }
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://youtu.be/shared")), 1)

    @Test fun secondLaunchSkipsVersionProcessAndChangedVersionRunsItAgain() = runBlocking {
        val durable = mutableMapOf<String, String>()
        val first = Runtime(durable)
        assertTrue(YoutubedlEngine(first).warmUp())
        assertEquals(listOf(listOf("--ignore-config", "--version")), first.calls)
        val second = Runtime(durable)
        assertTrue(YoutubedlEngine(second).warmUp())
        assertTrue(second.calls.isEmpty())
        assertEquals("2025.11.12", second.initialization.installedVersion)
        val changed = Runtime(durable, "2026.08.19")
        assertTrue(YoutubedlEngine(changed).warmUp())
        assertEquals(listOf(listOf("--ignore-config", "--version")), changed.calls)
    }
    @Test fun shareDuringWarmUpWaitsForOneExtractionAndOneVersionProcess() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val runtime = Runtime(mutableMapOf()).apply {
            extract = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val engine = YoutubedlEngine(runtime)
        val warming = async(Dispatchers.Default) { engine.warmUp() }
        var inspecting: Deferred<InspectResult>? = null
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(2, TimeUnit.SECONDS) })
            inspecting = async(Dispatchers.Default) { engine.inspect(source, "share") }
            assertNull(withTimeoutOrNull(100) { inspecting.await() })
            assertTrue(runtime.calls.isEmpty())
        } finally { release.countDown() }
        assertTrue(warming.await())
        assertIs<InspectResult.Success>(inspecting.await())
        assertEquals(1, runtime.extractions.get())
        assertEquals(1, runtime.probes.get())
        assertEquals(1, runtime.calls.count { "--dump-json" in it })
    }
    @Test fun inspectionFirstRecordsSameWarmMarker() = runBlocking {
        val durable = mutableMapOf<String, String>()
        assertIs<InspectResult.Success>(YoutubedlEngine(Runtime(durable)).inspect(source, "first"))
        val restarted = Runtime(durable)
        assertTrue(YoutubedlEngine(restarted).warmUp())
        assertEquals(0, restarted.probes.get())
    }
    @Test fun failedProbeIsNotRecordedAndNextLaunchRetries() = runBlocking {
        val durable = mutableMapOf<String, String>()
        assertFalse(YoutubedlEngine(Runtime(durable).apply { failProbe = true }).warmUp())
        assertTrue(durable.isEmpty())
        val retry = Runtime(durable)
        assertTrue(YoutubedlEngine(retry).warmUp())
        assertEquals(1, retry.probes.get())
    }
    @Test fun updateSelfTestWarmsNewVersionAndBundledRestoreAlwaysProbesAgain() = runBlocking {
        val durable = mutableMapOf<String, String>()
        val runtime = Runtime(durable)
        val engine = YoutubedlEngine(runtime)
        assertTrue(engine.warmUp())
        assertIs<EngineUpdateResult.Success>(engine.updateYtdlp())
        assertEquals(2, runtime.probes.get())
        val updatedLaunch = Runtime(durable, "2026.08.19")
        assertTrue(YoutubedlEngine(updatedLaunch).warmUp())
        assertEquals(0, updatedLaunch.probes.get())
        runtime.failUpdateProbe = true
        assertTrue(assertIs<EngineUpdateResult.Failure>(engine.updateYtdlp()).usingBuiltIn)
        assertEquals(4, runtime.probes.get()) // Failed update probe, then a fresh bundled probe despite its old marker.
        assertEquals("2025.11.12", engine.installedVersion())
    }
}
