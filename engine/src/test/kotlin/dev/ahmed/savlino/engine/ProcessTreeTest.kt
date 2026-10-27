package dev.ahmed.savlino.engine

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ProcessTreeTest {
    @get:Rule val temp = TemporaryFolder()
    private class Table : ProcTable {
        val rows = linkedMapOf<Int, ProcProcess>()
        val signals = mutableListOf<Pair<Int, Int>>()
        override fun snapshot() = rows.values.toList()
        override fun read(pid: Int) = rows[pid]
        override fun signal(pid: Int, signal: Int) { signals += pid to signal }
        fun add(pid: Int, parent: Int, start: Long = pid.toLong()) { rows[pid] = ProcProcess(pid, parent, start) }
    }
    @Test fun cancellationBeforePidRegistrationRetriesThenKillsDeepestFirst() {
        val proc = Table(); val tree = ProcessTree(proc) { it.pid == 10 }
        assertFalse(tree.terminate {})
        proc.add(10, 1); proc.add(11, 10); proc.add(12, 11); proc.add(99, 1)
        assertTrue(tree.terminate {})
        assertEquals(listOf(12 to 15, 11 to 15, 10 to 15, 12 to 9, 11 to 9, 10 to 9), proc.signals)
    }
    @Test fun orphanedDescendantsAreKilledEvenWhenParentExitsDuringGrace() {
        val proc = Table(); val tree = ProcessTree(proc) { it.pid == 10 }
        proc.add(10, 1); proc.add(11, 10); proc.add(12, 11)
        assertTrue(tree.terminate { proc.rows.remove(10); proc.add(11, 1) })
        assertEquals(listOf(12 to 15, 11 to 15, 10 to 15, 12 to 9, 11 to 9), proc.signals)
    }
    @Test fun observedChildrenSurviveLossOfRootAndPidReuseIsNeverSignalled() {
        val proc = Table(); val tree = ProcessTree(proc) { it.pid == 10 }
        proc.add(10, 1); proc.add(11, 10); tree.observe()
        proc.rows.remove(10); proc.add(11, 1)
        assertTrue(tree.terminate { proc.add(11, 1, 999) })
        assertEquals(listOf(11 to 15), proc.signals)
    }
    @Test fun cancellationAfterExitAndExistingUnrelatedMatchesAreHarmless() {
        val proc = Table(); proc.add(10, 1)
        val tree = ProcessTree(proc) { it.pid == 10 }
        assertFalse(tree.terminate {}); assertTrue(proc.signals.isEmpty())
        proc.rows.clear(); proc.add(10, 1, 100); tree.observe(); proc.rows.clear()
        assertTrue(tree.terminate {}); assertTrue(proc.signals.isEmpty())
    }
    @Test fun discoversChildrenForkedDuringGraceAndRejectsReusedParent() {
        val proc = Table(); val tree = ProcessTree(proc) { it.pid == 10 }
        proc.add(10, 1); proc.add(11, 10)
        tree.terminate { proc.add(12, 11) }
        assertEquals(listOf(11 to 15, 10 to 15, 12 to 15, 12 to 9, 11 to 9, 10 to 9), proc.signals)
        proc.signals.clear(); proc.add(10, 1, 1000); proc.add(13, 10)
        tree.terminate {}
        assertFalse(proc.signals.any { it.first == 10 || it.first == 13 })
    }
    @Test fun killWaitsUntilEveryMemberDisappearsOrBecomesZombie() {
        for (zombie in listOf(false, true)) {
            val table = Table()
            var killed = false; var readsAfterKill = 0
            val proc = object : ProcTable by table {
                override fun signal(pid: Int, signal: Int) {
                    table.signal(pid, signal)
                    if (signal == 9) killed = true
                }
                override fun read(pid: Int): ProcProcess? {
                    if (killed && pid == 11 && ++readsAfterKill >= 3) {
                        if (zombie) table.rows[pid] = table.rows.getValue(pid).copy(state = 'Z')
                        else table.rows.remove(pid)
                    }
                    return table.read(pid)
                }
            }
            val tree = ProcessTree(proc) { it.pid == 10 }
            table.add(10, 1); table.add(11, 10)
            tree.terminate { table.rows.remove(10) }
            assertTrue(readsAfterKill >= 3, "cleanup must wait for the child, not just send SIGKILL")
            assertTrue(table.rows[11]?.state == 'Z' || 11 !in table.rows)
        }
    }
    @Test fun killWaitStopsAfterThreeHundredMillisecondsForAnUnreapedProcess() {
        val proc = Table(); var now = 0L
        val tree = ProcessTree(proc, nanoTime = { now }, pause = { now += it * 1_000_000 }) { it.pid == 10 }
        proc.add(10, 1); proc.add(11, 10)
        assertTrue(tree.terminate {})
        assertEquals(300_000_000L, now)
        assertEquals(listOf(11 to 15, 10 to 15, 11 to 9, 10 to 9), proc.signals)
    }
    @Test fun fakeProcFilesParseNamesWithSpacesAndClosingParenthesesAndIgnoreRaces() {
        val root = temp.newFolder()
        val pid = File(root, "42").apply { mkdir() }
        // Field 3 = state, 4 = ppid, 22 = starttime.
        File(pid, "stat").writeText("42 (ffmpeg ) helper) S 7 " + List(17) { "0" }.joinToString(" ") + " 12345 0")
        File(pid, "cmdline").writeBytes("/native/libpython.so\u0000/private/yt-dlp\u0000--version\u0000".toByteArray())
        File(root, "43").mkdir(); File(root, "not-a-pid").mkdir()
        val proc = FileProcTable(root) { _, _ -> fail("read must never signal") }
        val row = assertNotNull(proc.read(42))
        assertEquals(7, row.parentPid); assertEquals(12345, row.startTicks)
        assertEquals(listOf("/native/libpython.so", "/private/yt-dlp", "--version"), row.command)
        assertEquals(listOf(row), proc.snapshot())
        File(pid, "stat").writeText("partial")
        assertNull(proc.read(42))
    }
    @Test fun rootDiscoveryRequiresAppParentPinnedExecutablePairAndExactArgvSuffix() {
        val args = listOf("--ignore-config", "--", "https://example.org/video")
        val row = ProcProcess(42, 7, 123, listOf("/native/libpython.so", "/private/yt-dlp", "--no-cache-dir") + args)
        fun matches(row: ProcProcess) = matchesYtdlpChild(row, 7, "/native/libpython.so", "/private/yt-dlp", args)
        assertTrue(matches(row))
        assertFalse(matches(row.copy(parentPid = 8)))
        assertFalse(matches(row.copy(command = listOf("/other/python", "/private/yt-dlp") + args)))
        assertFalse(matches(row.copy(command = row.command + "extra")))
        assertFalse(matches(row.copy(command = row.command.dropLast(1) + "https://other.org/video")))
    }
    /** Audio with tags passes two "" values (--replace-in-metadata): /proc keeps them, so the root match must too. */
    @Test fun audioConversionArgvWithEmptyArgumentsMatchesAndItsFfmpegIsSignalled() {
        val dir = temp.newFolder().canonicalFile
        val source = dev.ahmed.savlino.domain.LinkRevision(assertNotNull(dev.ahmed.savlino.domain.LinkInput.parse("https://example.org/123")), 1)
        val row = dev.ahmed.savlino.domain.Format("mp3-192", "mp3", false, true, audioCodec = "mp3",
            conversion = dev.ahmed.savlino.domain.AudioConversion.MP3_192, sourceFormatId = "140", sourceContainer = "m4a")
        val command = assertIs<dev.ahmed.savlino.domain.ConfirmResult.Confirmed>(dev.ahmed.savlino.domain.DownloadCommand.confirm("job-1",
            dev.ahmed.savlino.domain.Inspection(source, "2025.11.12", listOf(row)), source, row.id, "title")).command
        val args = YtdlpCommand.download(command, dir)
        assertTrue(args.count { it.isEmpty() } >= 2, "fixture must carry the empty metadata replacements")
        val root = temp.newFolder()
        fun write(pid: Int, parent: Int, argv: List<String>) {
            val directory = File(root, pid.toString()).apply { mkdir() }
            File(directory, "stat").writeText("$pid (x) S $parent " + List(17) { "0" }.joinToString(" ") + " $pid 0")
            File(directory, "cmdline").writeBytes(argv.joinToString("") { it + "\u0000" }.toByteArray())
        }
        val signals = mutableListOf<Pair<Int, Int>>()
        val proc = FileProcTable(root) { pid, signal -> signals += pid to signal; if (signal == 9) File(root, pid.toString()).deleteRecursively() }
        val tree = ProcessTree(proc) { matchesYtdlpChild(it, 7, "/native/libpython.so", "/private/yt-dlp", args) }
        write(42, 7, listOf("/native/libpython.so", "/private/yt-dlp") + args)
        write(43, 42, listOf("/native/libffmpeg.so", "-y", "-i", File(dir, "output.m4a").path, File(dir, "output.mp3").path))
        assertEquals(args, assertNotNull(proc.read(42)).command.drop(2))
        assertTrue(tree.terminate(includeRoot = false) {})
        assertEquals(listOf(43 to 15, 43 to 9), signals)
    }
    @Test fun staleProcessesKillsOnlyOurUidWritingIntoThatJobsDirectory() {
        val proc = Table()
        val jobs = temp.newFolder("downloads")
        val job1 = File(jobs, "job-1").canonicalFile; val job10 = File(jobs, "job-10").canonicalFile
        fun add(pid: Int, vararg argv: String) { proc.rows[pid] = ProcProcess(pid, 1, pid.toLong(), argv.toList()) }
        add(20, "libffmpeg.so", "-i", "file:$job1/output.m4a", "-y", "file:$job1/output.mp3") // orphan ffmpeg
        add(21, "libpython.so", "-o", "$job1/output.%(ext)s")
        add(22, "libffmpeg.so", "-i", "file:$job10/output.m4a")   // another job
        add(23, "libffmpeg.so", "-i", "file:$job1/output.m4a")     // another app's uid
        add(24, "app_process")                                      // this app itself
        add(25, "libffmpeg.so", "-i", "file:$job1/output.m4a")
        proc.rows[25] = proc.rows[25]!!.copy(state = 'Z')
        // Another job's argv merely containing the path (a URL, a title, a prefix) is not ours to kill.
        add(26, "libpython.so", "--", "https://example.com/watch?p=$job1/output.mp4")
        add(27, "libffmpeg.so", "-metadata", "title=$job1/x", "-i", "file:/other$job1/output.m4a")
        val uids = mapOf(20 to 10100, 21 to 10100, 22 to 10100, 23 to 10200, 24 to 10100, 25 to 10100, 26 to 10100, 27 to 10100)
        val stale = StaleProcesses(proc, uids::get, { 10100 }, { 24 }, nanoTime = { 0L }, pause = { proc.rows.remove(20); proc.rows.remove(21) })
        assertEquals(0, stale.kill(job1)) // both targets died: staging is free
        assertEquals(listOf(20 to 15, 21 to 15), proc.signals)
    }
    @Test fun staleProcessesEscalatesToKillAndScansEveryJobUnderTheRoot() {
        val proc = Table()
        val jobs = temp.newFolder("downloads").canonicalFile
        proc.rows[30] = ProcProcess(30, 1, 30, listOf("libffmpeg.so", "file:$jobs/a/output.m4a"))
        proc.rows[31] = ProcProcess(31, 1, 31, listOf("libffmpeg.so", "file:$jobs/b/output.m4a"))
        proc.rows[32] = ProcProcess(32, 1, 32, listOf("libffmpeg.so", "file:${jobs.path}-old/x"))
        var now = 0L
        val stale = StaleProcesses(proc, { 1 }, { 1 }, { 2 }, nanoTime = { now }, pause = { now += 100_000_000 })
        assertEquals(2, stale.kill(jobs)) // ignored TERM and KILL for 800 ms: both reported as survivors
        assertEquals(listOf(30 to 15, 31 to 15, 30 to 9, 31 to 9), proc.signals)
        proc.signals.clear()
        val killedLate = StaleProcesses(proc, { 1 }, { 1 }, { 2 }, nanoTime = { now }, pause = { now += 100_000_000
            if (proc.signals.any { it.second == 9 }) proc.rows.remove(30) })
        assertEquals(1, killedLate.kill(jobs)) // only 31 outlived KILL
        assertEquals(0, StaleProcesses(proc, { 1 }, { 1 }, { 2 }).kill(File(jobs, "c")))
    }
}
