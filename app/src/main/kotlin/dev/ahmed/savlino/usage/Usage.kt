package dev.ahmed.savlino.usage

import android.content.SharedPreferences
import dev.ahmed.savlino.report.InboxClient
import dev.ahmed.savlino.report.Report
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** What the daily ping adds once: settings and phone class, read at send time. Keys must be in [UsageKeys]. */
fun interface UsageSnapshot { fun keys(): List<String> }

/** Where hooks report; [None] for tests and builds without the app object. */
interface UsageEvents {
    fun count(key: String) {}
    /** Counts `site:<registrable name>` for a finished or failed download. */
    fun site(link: String?) {}
    /** A failed download or link read, sent now with its exact link; kept for the daily send if that POST fails. */
    fun failed(stage: String, code: String, link: String?, engineVersion: String?) {}
    object None : UsageEvents
}

/**
 * Local counters since the last daily send, plus failure reports still to send. Counters, payloads and links are dropped, never
 * sent, while "Help improve Nezzel" is off. Counts, payloads and links are never logged.
 */
class Usage(
    private val prefs: SharedPreferences,
    private val enabled: () -> Boolean,
    private val post: suspend (path: String, json: String) -> Boolean,
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val versionCode: Int,
    private val sdk: Int,
    private val snapshot: UsageSnapshot,
    /** The pending sanitized crash text, its delete, and the engine version a crash report names. */
    private val crash: Crashes,
    private val now: () -> Long = System::currentTimeMillis,
) : UsageEvents {
    interface Crashes {
        fun pending(): String?
        fun delete()
        fun engineVersion(): String?
    }

    private val lock = Any()
    private val sends = Mutex()
    /** Bumped by [clear]: a send or queue write begun before the switch went off never lands after it. */
    private var generation = 0L

    /** Unknown keys are dropped (a hook must never break a download); at most [MAX_SITES] sites, the rest as one. */
    override fun count(key: String) {
        if (!UsageKeys.allowed(key) || !enabled()) return
        synchronized(lock) {
            if (!enabled()) return
            val stored = if (key.startsWith(UsageKeys.SITE) && !prefs.contains(COUNT + key) &&
                prefs.all.keys.count { it.startsWith(COUNT + UsageKeys.SITE) } >= MAX_SITES) UsageKeys.SITE_OTHER else key
            val edit = prefs.edit()
            if (!prefs.contains(SINCE)) edit.putLong(SINCE, now())
            edit.putInt(COUNT + stored, (prefs.getInt(COUNT + stored, 0) + 1).coerceAtMost(MAX_N)).apply()
        }
    }

    override fun site(link: String?) { UsageKeys.site(link)?.let(::count) }

    override fun failed(stage: String, code: String, link: String?, engineVersion: String?) {
        if (!enabled() || link == null) return
        val report = Report.failure(link, code, appVersion, engineVersion, sdk, stage)
        if (report.link == null || report.code == null) return
        val json = report.json()
        val started = synchronized(lock) { generation }
        scope.launch {
            sends.withLock {
                if (!on(started)) return@withLock
                if (slotLeft() && post(InboxClient.REPORT, json)) {
                    synchronized(lock) { if (on(started)) takeSlot() }
                } else queue(json, started)
            }
        }
    }

    /** Switch off: drop payloads, keeping only the successful usage day and daily failure-slot counters. Call off the main thread. */
    fun clear() {
        synchronized(lock) {
            generation++
            val day = prefs.getLong(USAGE_DAY, -1)
            val slotDay = prefs.getLong(SLOT_DAY, -1)
            val slots = prefs.getInt(SLOTS, -1)
            prefs.edit().clear().apply {
                if (day >= 0) putLong(USAGE_DAY, day)
                if (slotDay >= 0) putLong(SLOT_DAY, slotDay)
                if (slots >= 0) putInt(SLOTS, slots)
            }.commit()
        }
        dropCrash()
    }

    /** A crash recorded while the switch was off is never sent later. */
    fun dropCrash() { try { crash.delete() } catch (_: Exception) {} }

    /**
     * The daily send: one usage ping (counters subtracted only after the inbox's 204, so counts added meanwhile stay),
     * the pending crash, then queued failures up to the day's cap. The first failed POST (offline, 429) ends the run and
     * keeps the rest; at most [MAX_POSTS] requests, under the inbox's per-minute limit.
     */
    suspend fun sendDaily() = sends.withLock {
        if (!enabled()) { clear(); return@withLock }
        val started = synchronized(lock) { expire(); generation }
        var posts = 0
        // Update work may run more often than daily. Persist the successful UTC day across process restarts.
        if (synchronized(lock) { prefs.getLong(USAGE_DAY, -1) != now() / DAY_MS }) {
            val payload = payload(synchronized(lock) { counters() })
            if (!on(started)) return@withLock
            posts++
            if (!post(InboxClient.USAGE, payload.second)) return@withLock
            synchronized(lock) {
                if (!on(started)) return@withLock
                val edit = prefs.edit()
                for ((key, n) in payload.first) {
                    val left = prefs.getInt(COUNT + key, 0) - n
                    if (left > 0) edit.putInt(COUNT + key, left) else edit.remove(COUNT + key)
                }
                edit.putLong(SINCE, now()).putLong(USAGE_DAY, now() / DAY_MS).commit()
            }
        }
        crash.pending()?.let { text ->
            val report = Report.crash(text, appVersion, crash.engineVersion(), sdk)
            if (!on(started)) return@withLock
            if (report.trace == null) {
                synchronized(lock) { if (on(started)) dropCrash() }
                return@let
            }
            posts++
            if (!post(InboxClient.REPORT, report.json())) return@withLock
            synchronized(lock) { if (on(started)) dropCrash() }
        }
        for (json in queued()) {
            if (posts >= MAX_POSTS || !on(started) || !slotLeft()) break
            posts++
            if (!post(InboxClient.REPORT, json)) break
            synchronized(lock) { if (on(started)) { unqueue(json); takeSlot() } }
        }
    }

    private fun on(started: Long) = synchronized(lock) { generation == started && enabled() }

    /** Counted keys plus once-per-ping keys, at most [MAX_KEYS]: fixed keys first, then the most counted sites. */
    internal fun payload(counts: Map<String, Int>): Pair<Map<String, Int>, String> {
        val once = (listOf(UsageKeys.ACTIVE) + snapshot.keys()).filter(UsageKeys::allowed).distinct()
        val (sites, rest) = counts.entries.partition { it.key.startsWith(UsageKeys.SITE) }
        val room = MAX_KEYS - once.size
        val sent = (rest.sortedBy { it.key } + sites.sortedByDescending { it.value }).take(room.coerceAtLeast(0))
            .associate { it.key to it.value }
        val all = JSONObject()
        once.forEach { all.put(it, 1) }
        sent.forEach { (key, n) -> if (key !in once) all.put(key, n.coerceIn(1, MAX_N)) }
        val json = JSONObject().put("app_version", versionCode).put("counts", all).toString()
        return sent to json
    }

    private fun counters(): Map<String, Int> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(COUNT) || value !is Int || value <= 0) null
        else key.removePrefix(COUNT).takeIf(UsageKeys::allowed)?.let { it to value }
    }.toMap()

    /** Counters unsent for 7 days and queued reports older than that are dropped. Only on send and queue. */
    private fun expire() {
        val since = prefs.getLong(SINCE, -1)
        val edit = prefs.edit()
        if (since >= 0 && (now() - since > MAX_AGE_MS || since > now() + MAX_AGE_MS)) {
            prefs.all.keys.filter { it.startsWith(COUNT) }.forEach(edit::remove)
            edit.remove(SINCE)
        }
        writeQueue(edit, queuedEntries().filter { now() - it.first in 0..MAX_AGE_MS })
        edit.commit()
    }

    private fun queuedEntries(): List<Pair<Long, String>> = try {
        val array = JSONArray(prefs.getString(QUEUE, "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { o -> o.getLong("at") to o.getString("json") } }
    } catch (_: org.json.JSONException) { emptyList() }

    private fun writeQueue(edit: SharedPreferences.Editor, entries: List<Pair<Long, String>>) =
        edit.putString(QUEUE, JSONArray(entries.map { JSONObject().put("at", it.first).put("json", it.second) }).toString())

    internal fun queued(): List<String> = synchronized(lock) { queuedEntries().map { it.second } }

    /** Kept for the daily send, unless the switch went off since the report was made. */
    private fun queue(json: String, started: Long) {
        synchronized(lock) {
            if (generation != started || !enabled()) return
            expire()
            val edit = prefs.edit()
            writeQueue(edit, (queuedEntries() + (now() to json)).takeLast(MAX_REPORTS))
            edit.commit()
        }
    }

    private fun unqueue(json: String) {
        val entries = queuedEntries().toMutableList().apply { indexOfFirst { it.second == json }.takeIf { it >= 0 }?.let(::removeAt) }
        val edit = prefs.edit(); writeQueue(edit, entries); edit.commit()
    }

    /** At most [MAX_REPORTS] failure reports per UTC day, immediate and queued together; a slot is used only on a 204. */
    private fun used(): Int = synchronized(lock) { if (prefs.getLong(SLOT_DAY, -1) == now() / DAY_MS) prefs.getInt(SLOTS, 0) else 0 }
    private fun slotLeft() = used() < MAX_REPORTS
    private fun takeSlot() {
        synchronized(lock) {
            if (!enabled()) return
            prefs.edit().putLong(SLOT_DAY, now() / DAY_MS).putInt(SLOTS, used() + 1).apply()
        }
    }

    companion object {
        const val MAX_KEYS = 200
        const val MAX_N = 10_000
        const val MAX_REPORTS = 20
        const val MAX_SITES = 50
        /** Requests per daily run; the inbox allows 10 a minute per address. */
        const val MAX_POSTS = 8
        private const val DAY_MS = 86_400_000L
        const val MAX_AGE_MS = 7 * DAY_MS
        private const val COUNT = "c:"
        private const val SINCE = "since"
        private const val QUEUE = "failures"
        private const val SLOT_DAY = "slot_day"
        private const val USAGE_DAY = "usage_day"
        private const val SLOTS = "slots"
    }
}
