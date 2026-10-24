package dev.ahmed.savlino.usage

import android.content.SharedPreferences
import dev.ahmed.savlino.report.InboxClient
import dev.ahmed.savlino.report.Report
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
 * Local counters since the last daily send, plus failure reports still to send. Everything here is dropped, never
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

    override fun count(key: String) {
        require(UsageKeys.allowed(key))
        if (!enabled()) return
        synchronized(lock) {
            expire()
            val edit = prefs.edit()
            if (!prefs.contains(SINCE)) edit.putLong(SINCE, now())
            edit.putInt(COUNT + key, (prefs.getInt(COUNT + key, 0) + 1).coerceAtMost(MAX_N)).apply()
        }
    }

    override fun site(link: String?) { UsageKeys.site(link)?.let(::count) }

    override fun failed(stage: String, code: String, link: String?, engineVersion: String?) {
        if (!enabled() || link == null) return
        val report = Report.failure(link, code, appVersion, engineVersion, sdk, stage)
        if (report.link == null || report.code == null) return
        val json = report.json()
        scope.launch {
            val sent = takeDailySlot() && post(InboxClient.REPORT, json)
            if (!sent && enabled()) queue(json)
        }
    }

    /** Switch off: nothing kept, nothing sent. */
    fun clear() = synchronized(lock) {
        prefs.edit().clear().commit()
        crash.delete()
    }

    /**
     * The daily send: one usage ping (counters subtracted only after the inbox's 204, so counts added meanwhile stay),
     * the pending crash, then queued failures up to the day's cap. A failed POST keeps its part for the next day.
     */
    suspend fun sendDaily() {
        if (!enabled()) { clear(); return }
        synchronized(lock) { expire() }
        val counts = synchronized(lock) { counters() }
        val payload = payload(counts)
        if (post(InboxClient.USAGE, payload.second)) synchronized(lock) {
            val edit = prefs.edit()
            for ((key, n) in payload.first) {
                val left = prefs.getInt(COUNT + key, 0) - n
                if (left > 0) edit.putInt(COUNT + key, left) else edit.remove(COUNT + key)
            }
            edit.putLong(SINCE, now()).commit()
        }
        crash.pending()?.let { text ->
            val report = Report.crash(text, appVersion, crash.engineVersion(), sdk)
            if (report.trace != null && enabled() && post(InboxClient.REPORT, report.json())) crash.delete()
        }
        for (json in queued()) {
            if (!enabled() || !takeDailySlot()) break
            if (post(InboxClient.REPORT, json)) unqueue(json) else break
        }
    }

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

    /** Counters unsent for 7 days and queued reports older than that are dropped. */
    private fun expire() {
        val since = prefs.getLong(SINCE, -1)
        val edit = prefs.edit()
        if (since >= 0 && (now() - since > MAX_AGE_MS || since > now() + MAX_AGE_MS)) {
            prefs.all.keys.filter { it.startsWith(COUNT) }.forEach(edit::remove)
            edit.remove(SINCE)
        }
        val fresh = queuedEntries().filter { now() - it.first in 0..MAX_AGE_MS }
        edit.putString(QUEUE, JSONArray(fresh.map { JSONObject().put("at", it.first).put("json", it.second) }).toString())
        edit.commit()
    }

    private fun queuedEntries(): List<Pair<Long, String>> = try {
        val array = JSONArray(prefs.getString(QUEUE, "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { o -> o.getLong("at") to o.getString("json") } }
    } catch (_: org.json.JSONException) { emptyList() }

    internal fun queued(): List<String> = synchronized(lock) { queuedEntries().map { it.second } }

    private fun queue(json: String) = synchronized(lock) {
        val entries = (queuedEntries() + (now() to json)).takeLast(MAX_REPORTS)
        prefs.edit().putString(QUEUE, JSONArray(entries.map { JSONObject().put("at", it.first).put("json", it.second) }).toString()).commit()
    }

    private fun unqueue(json: String) = synchronized(lock) {
        val entries = queuedEntries().toMutableList().apply { indexOfFirst { it.second == json }.takeIf { it >= 0 }?.let(::removeAt) }
        prefs.edit().putString(QUEUE, JSONArray(entries.map { JSONObject().put("at", it.first).put("json", it.second) }).toString()).commit()
    }

    /** At most [MAX_REPORTS] failure reports per UTC day, immediate and queued together. */
    private fun takeDailySlot(): Boolean = synchronized(lock) {
        val day = now() / DAY_MS
        val used = if (prefs.getLong(SLOT_DAY, -1) == day) prefs.getInt(SLOTS, 0) else 0
        if (used >= MAX_REPORTS) return false
        prefs.edit().putLong(SLOT_DAY, day).putInt(SLOTS, used + 1).commit()
    }

    companion object {
        const val MAX_KEYS = 200
        const val MAX_N = 10_000
        const val MAX_REPORTS = 20
        private const val DAY_MS = 86_400_000L
        const val MAX_AGE_MS = 7 * DAY_MS
        private const val COUNT = "c:"
        private const val SINCE = "since"
        private const val QUEUE = "failures"
        private const val SLOT_DAY = "slot_day"
        private const val SLOTS = "slots"
    }
}
