package dev.ahmed.savlino.engine

import java.io.File

/** startTicks (stat field 22) prevents a recycled PID from inheriting cancellation. */
internal data class ProcProcess(val pid: Int, val parentPid: Int, val startTicks: Long,
    val command: List<String> = emptyList(), val state: Char = 'S')

internal interface ProcTable {
    fun snapshot(): List<ProcProcess>
    fun read(pid: Int): ProcProcess?
    fun signal(pid: Int, signal: Int)
}

internal class FileProcTable(private val root: File = File("/proc"),
    private val sendSignal: (Int, Int) -> Unit = android.os.Process::sendSignal) : ProcTable {
    override fun snapshot(): List<ProcProcess> = root.listFiles().orEmpty().mapNotNull {
        it.name.toIntOrNull()?.takeIf { pid -> pid > 0 }?.let(::read)
    }
    override fun read(pid: Int): ProcProcess? = try {
        val directory = File(root, pid.toString())
        val stat = File(directory, "stat").readText()
        // comm is parenthesized, may contain spaces and ')' (including adversarial filenames).
        val end = stat.lastIndexOf(')')
        require(end > 0 && stat.substringBefore(' ').toInt() == pid)
        val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
        // Each argv entry ends in NUL; an empty argument ("" in --replace-in-metadata) is a real entry.
        val command = try { File(directory, "cmdline").readText().takeIf { it.isNotEmpty() }
            ?.removeSuffix("\u0000")?.split('\u0000').orEmpty() }
            catch (_: Exception) { emptyList() }
        ProcProcess(pid, fields[1].toInt(), fields[19].toLong(), command, fields[0].single())
    } catch (_: Exception) { null } // Process may disappear between directory/stat/cmdline reads.
    override fun signal(pid: Int, signal: Int) = sendSignal(pid, signal)
}

/** One execute call. Retains known descendants even if the root exits/reparents them.
 * Existing matching children are excluded; the caller serializes native executions.
 */
internal class ProcessTree(private val proc: ProcTable,
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep,
    private val matchesRoot: (ProcProcess) -> Boolean) {
    private data class Identity(val pid: Int, val start: Long)
    private data class Member(val identity: Identity, val depth: Int)
    private fun ProcProcess.identity() = Identity(pid, startTicks)
    private val baseline = proc.snapshot().map { it.identity() }.toSet()
    private var root: Identity? = null
    private val members = linkedMapOf<Identity, Member>()

    @Synchronized fun observe() {
        val rows = proc.snapshot()
        if (root == null) {
            val candidate = rows.filter { it.identity() !in baseline && matchesRoot(it) }.singleOrNull() ?: return
            root = candidate.identity()
            members[candidate.identity()] = Member(candidate.identity(), 0)
        }
        // Only a still-live identity can establish new children; never a recycled PID.
        val live = rows.filter { it.state != 'Z' }.associateBy { it.pid }
        var changed: Boolean
        do {
            changed = false
            for (row in live.values) {
                val parent = live[row.parentPid] ?: continue
                val owner = members[parent.identity()] ?: continue
                if (row.identity() !in members) {
                    members[row.identity()] = Member(row.identity(), owner.depth + 1)
                    changed = true
                }
            }
        } while (changed)
    }

    /** Blocking grace runs on IO. Must finish before staged files can be cleaned up. */
    @Synchronized fun terminate(includeRoot: Boolean = true, grace: () -> Unit): Boolean {
        observe()
        if (root == null) return false // Retry when the wrapper has actually forked Python.
        fun alive(id: Identity): Boolean {
            val current = proc.read(id.pid) ?: return false
            return current.identity() == id && current.state != 'Z'
        }
        fun targets() = members.values.filter { includeRoot || it.identity != root }
        if (targets().none { alive(it.identity) }) return true
        val termed = mutableSetOf<Identity>()
        fun signalAll(signal: Int, onlyNew: Boolean = false) {
            for (member in targets().sortedByDescending { it.depth }) {
                val id = member.identity
                if (onlyNew && id in termed) continue
                val current = proc.read(id.pid) ?: continue
                if (current.identity() != id || current.state == 'Z') continue
                proc.signal(id.pid, signal)
                if (signal == 15) termed += id
            }
        }
        signalAll(15)
        grace()
        observe() // Include descendants forked during TERM's grace interval.
        signalAll(15, onlyNew = true)
        signalAll(9)
        // SIGKILL delivery is asynchronous; bound the wait before caller-owned staging cleanup.
        val started = nanoTime()
        while (targets().any { alive(it.identity) }) {
            if (nanoTime() - started >= 300_000_000) break
            pause(20)
        }
        return true
    }
}

/**
 * Native processes a stopped, crashed or replaced attempt left writing into staging (an orphan ffmpeg after an
 * airplane toggle, or after the app was killed or updated). Only this app's uid, and only argv that names a path
 * inside [kill]'s directory: "downloads/job-1/" never matches job-10.
 */
internal class StaleProcesses(private val proc: ProcTable = FileProcTable(),
    private val uidOf: (Int) -> Int? = ::procUid, private val myUid: () -> Int = android.os.Process::myUid,
    private val myPid: () -> Int = android.os.Process::myPid,
    private val nanoTime: () -> Long = System::nanoTime, private val pause: (Long) -> Unit = Thread::sleep) {
    /** Blocking: TERM, a short grace, then KILL. Returns how many processes were signalled. */
    fun kill(directory: File): Int {
        val path = directory.canonicalFile.path.trimEnd('/')
        fun names(row: ProcProcess) = row.command.any { it == path || "$path/" in it }
        val uid = myUid(); val pid = myPid()
        val targets = proc.snapshot().filter { it.pid != pid && it.state != 'Z' && names(it) && uidOf(it.pid) == uid }
            .map { it.pid to it.startTicks }
        if (targets.isEmpty()) return 0
        fun alive(target: Pair<Int, Long>) = proc.read(target.first)?.let { it.startTicks == target.second && it.state != 'Z' } == true
        fun signalAll(signal: Int) = targets.filter(::alive).forEach { proc.signal(it.first, signal) }
        signalAll(15)
        val started = nanoTime()
        while (targets.any(::alive) && nanoTime() - started < 500_000_000) pause(20)
        signalAll(9)
        while (targets.any(::alive) && nanoTime() - started < 800_000_000) pause(20)
        return targets.size
    }
}

/** Real uid from /proc/<pid>/status; null when the process is gone or unreadable. */
private fun procUid(pid: Int): Int? = try {
    File("/proc/$pid/status").useLines { lines -> lines.firstOrNull { it.startsWith("Uid:") } }
        ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toInt()
} catch (_: Exception) { null }
