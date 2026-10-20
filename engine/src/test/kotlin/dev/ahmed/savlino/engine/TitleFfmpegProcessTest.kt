package dev.ahmed.savlino.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class TitleFfmpegProcessTest {
    @get:Rule val temp = TemporaryFolder()
    private fun input() = temp.newFolder().resolve("output.mp4").apply { writeText("original") }
    private class Child(private val timesOut: Boolean = false, private val exit: Int = 0) : Process() {
        val waiting = CountDownLatch(1)
        val killed = CountDownLatch(1)
        var joined = false
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waiting.countDown()
            if (timesOut) return false
            assertTrue(killed.await(5, TimeUnit.SECONDS))
            return true
        }
        override fun waitFor(): Int { joined = true; return exit }
        override fun exitValue(): Int = exit
        override fun isAlive() = killed.count != 0L
        override fun destroy() { killed.countDown() }
        override fun destroyForcibly(): Process { destroy(); return this }
    }

    @Test fun timeoutKillsAndJoinsChildBeforeCleaningTemporaryOutputAndPreservesOriginal() {
        val child = Child(timesOut = true)
        val file = input()
        val process = TitleFfmpegProcess { args -> File(args.last()).writeText("partial"); child }
        Mp4TitleRewriter(readTitle = { if (it == file) "123" else "Confirmed title" },
            ffmpeg = { process.execute(it, "opaque-job") }).rewrite(file, "Confirmed title")
        assertEquals("original", file.readText())
        assertEquals(listOf(file), file.parentFile!!.listFiles()!!.toList())
        assertEquals(0L, child.killed.count)
        assertTrue(child.joined)
        assertFalse(process.cancel("opaque-job"))
    }

    @Test fun cancellationKillsAndJoinsChildAndPreservesOriginalEvenOnZeroExit() {
        val child = Child()
        val file = input()
        val process = TitleFfmpegProcess { args -> File(args.last()).writeText("partial"); child }
        var succeeded: Boolean? = null
        val worker = thread {
            Mp4TitleRewriter(readTitle = { if (it == file) "123" else "Confirmed title" }, ffmpeg = {
                process.execute(it, "opaque-job").also { result -> succeeded = result }
            }).rewrite(file, "Confirmed title")
        }
        try {
            assertTrue(child.waiting.await(5, TimeUnit.SECONDS))
            assertFalse(process.cancel("other-job"))
            assertTrue(process.cancel("opaque-job"))
            worker.join(5_000)
            assertFalse(worker.isAlive)
            assertEquals(false, succeeded)
            assertEquals("original", file.readText())
            assertEquals(listOf(file), file.parentFile!!.listFiles()!!.toList())
            assertTrue(child.joined)
            assertFalse(process.cancel("opaque-job"))
        } finally { child.destroyForcibly(); worker.join(5_000) }
    }
}
