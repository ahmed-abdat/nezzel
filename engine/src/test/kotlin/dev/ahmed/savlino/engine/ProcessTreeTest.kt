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
}
