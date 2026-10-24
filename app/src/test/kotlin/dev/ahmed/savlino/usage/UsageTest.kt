package dev.ahmed.savlino.usage

import android.content.Context
import android.os.Build
import dev.ahmed.savlino.diagnostics.CrashMetadata
import dev.ahmed.savlino.diagnostics.CrashReportStore
import dev.ahmed.savlino.domain.Format
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.report.InboxClient
import dev.ahmed.savlino.report.Report
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UsageTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private var on = true
    private var answer = true
    private val posts = mutableListOf<Pair<String, String>>()
    private var clock = 1_000_000_000_000L
    private val crashDir = File(context.cacheDir, "usage-test-crash").apply { deleteRecursively() }
    private val crashStore = CrashReportStore(crashDir, CrashMetadata("0.5.0", 400, 35)) { clock }
    private val prefs = context.getSharedPreferences("usage-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
    private val usage = Usage(prefs, { on }, { path, json -> posts += path to json; answer }, CoroutineScope(Dispatchers.Unconfined),
        "0.5.0", 400, 35, { listOf("set.theme.dark", UsageKeys.brand("Samsung"), "sdk:35") }, object : Usage.Crashes {
            override fun pending() = crashStore.pending()
            override fun delete() = crashStore.deleteReports()
            override fun engineVersion() = "2026.10.01"
        }, now = { clock })
    private val link = "https://www.example.com/watch?v=abc123"

    private fun usagePosts() = posts.filter { it.first == InboxClient.USAGE }.map { JSONObject(it.second) }
    private fun counts(json: JSONObject) = json.getJSONObject("counts").let { c -> c.keys().asSequence().associateWith { c.getInt(it) } }

    @Test fun countersResetOnlyAfterTheInboxAccepted() = runBlocking {
        usage.count(UsageKeys.SHARE); usage.count(UsageKeys.SHARE); usage.site(link)
        answer = false
        usage.sendDaily()
        assertEquals(mapOf(UsageKeys.SHARE to 2, "site:example.com" to 1, "active" to 1, "set.theme.dark" to 1,
            "brand:samsung" to 1, "sdk:35" to 1), counts(usagePosts().single()))
        answer = true
        usage.sendDaily()
        assertEquals(2, counts(usagePosts()[1])[UsageKeys.SHARE]) // Kept after the failed POST.
        posts.clear()
        usage.sendDaily()
        assertEquals(setOf("active", "set.theme.dark", "brand:samsung", "sdk:35"), counts(usagePosts().single()).keys)
    }

    @Test fun payloadCarriesNoIdNoModelAndNoLink() = runBlocking {
        usage.site(link); usage.count(UsageKeys.OPEN)
        usage.sendDaily()
        val body = posts.single { it.first == InboxClient.USAGE }.second
        val json = JSONObject(body)
        assertEquals(setOf("app_version", "counts"), json.keys().asSequence().toSet())
        assertEquals(400, json.getInt("app_version"))
        assertFalse(body.contains("watch") || body.contains("abc123"))
        assertFalse(Build.MODEL.isNotBlank() && body.contains(Build.MODEL, ignoreCase = true) && Build.MODEL.lowercase() != "robolectric")
        assertTrue(counts(json).keys.all(UsageKeys::allowed))
    }

    @Test fun switchedOffCountsNothingSendsNothingAndClearsEverything() = runBlocking {
        usage.count(UsageKeys.SHARE)
        crashStore.record(Thread.currentThread(), IllegalStateException())
        on = false
        usage.count(UsageKeys.PASTE)
        usage.failed(Report.DOWNLOAD, "Failed", link, null)
        usage.sendDaily()
        assertTrue(posts.isEmpty())
        assertTrue(prefs.all.isEmpty())
        assertNull(crashStore.pending())
    }

    @Test fun crashIsSentOnceThenItsFileIsDeleted() = runBlocking {
        crashStore.record(Thread.currentThread(), IllegalStateException())
        answer = false
        usage.sendDaily()
        assertNotNull(crashStore.pending()) // Kept for the next day.
        answer = true
        usage.sendDaily()
        val crash = posts.last { it.first == InboxClient.REPORT }.second.let(::JSONObject)
        assertEquals("crash", crash.getString("kind"))
        assertTrue(crash.getString("trace").contains("java.lang.IllegalStateException"))
        assertNull(crashStore.pending())
        assertTrue(crashDir.listFiles().orEmpty().isEmpty())
    }

    @Test fun failureIsPostedAtOnceAndQueuedForTheDailySendWhenThatFails() = runBlocking {
        usage.failed(Report.DOWNLOAD, "Failed", link, "2026.10.01")
        val sent = JSONObject(posts.single().second)
        assertEquals(InboxClient.REPORT, posts.single().first)
        assertEquals(link, sent.getString("link"))
        assertEquals("download", sent.getString("stage"))
        assertEquals("example.com", sent.getString("site"))
        assertTrue(usage.queued().isEmpty())
        answer = false
        usage.failed(Report.OPTIONS, "Unsupported", link, null)
        assertEquals(1, usage.queued().size)
        answer = true; posts.clear()
        usage.sendDaily()
        assertEquals("Unsupported", posts.filter { it.first == InboxClient.REPORT }.single().second.let(::JSONObject).getString("code"))
        assertTrue(usage.queued().isEmpty())
    }

    @Test fun atMostTwentyFailureReportsADay() = runBlocking {
        repeat(25) { usage.failed(Report.DOWNLOAD, "Failed", link, null) }
        assertEquals(Usage.MAX_REPORTS, posts.size)
        assertEquals(5, usage.queued().size) // The rest wait for tomorrow's send.
        clock += 86_400_000L; posts.clear()
        usage.sendDaily()
        assertEquals(5, posts.count { it.first == InboxClient.REPORT })
    }

    @Test fun unsentCountsAndReportsAreDroppedAfterSevenDays() = runBlocking {
        answer = false
        usage.count(UsageKeys.SHARE)
        usage.failed(Report.DOWNLOAD, "Failed", link, null)
        clock += Usage.MAX_AGE_MS + 1; posts.clear(); answer = true
        usage.sendDaily()
        assertNull(counts(usagePosts().single())[UsageKeys.SHARE])
        assertTrue(posts.none { it.first == InboxClient.REPORT })
    }

    @Test fun allowlistAcceptsOnlyItsKeysAndPatterns() {
        assertTrue(UsageKeys.allowed("dl.finished.video.fhd"))
        assertTrue(UsageKeys.allowed("site:bbc.co.uk"))
        assertFalse(UsageKeys.allowed("site:https://example.com/x"))
        assertFalse(UsageKeys.allowed("site:" + "a".repeat(61) + ".com"))
        assertFalse(UsageKeys.allowed("sdk:035"))
        assertFalse(UsageKeys.allowed("brand:Samsung Galaxy"))
        assertFalse(UsageKeys.allowed("country:MR")) // Only the inbox adds the country.
        assertEquals("brand:samsung", UsageKeys.brand("Samsung"))
        assertEquals("brand:other", UsageKeys.brand(""))
        assertEquals("site:bbc.co.uk", UsageKeys.site("https://www.bbc.co.uk/news/1"))
        assertNull(UsageKeys.site("content://media/1"))
        assertThrows(IllegalArgumentException::class.java) { usage.count("title:secret") }
        val p1080 = Format("1", "mp4", hasVideo = true, hasAudio = true, width = 1920, height = 1080)
        assertEquals("dl.started.video.fhd", UsageKeys.download(UsageKeys.Download.STARTED, p1080))
        assertEquals("dl.failed.audio.mp3", UsageKeys.download(UsageKeys.Download.FAILED, Format("2", "mp3", false, true)))
        assertEquals("options.npe.t0-2", UsageKeys.options(UsageKeys.Path.NPE, 1_999))
        assertEquals("speed.s1m-5m", UsageKeys.speed(2_000_000.0))
        assertEquals("age.day0", UsageKeys.age(0)); assertEquals("age.d31up", UsageKeys.age(31))
    }

    @Test fun phoneSnapshotUsesOnlyAllowedKeys() {
        val app = context as NezzelApplication
        val keys = PhoneSnapshot(app, app.preferences).keys()
        assertTrue(keys.toString(), keys.all(UsageKeys::allowed))
        assertTrue(keys.any { it.startsWith(UsageKeys.BRAND) } && keys.any { it.startsWith("age.") })
    }

    @Test fun switchingOffInSettingsClearsCountersAndTheCrashFile() {
        val app = context as NezzelApplication
        app.usage.count(UsageKeys.SHARE)
        val crashes = dev.ahmed.savlino.diagnostics.CrashReports.store(app)
        crashes.record(Thread.currentThread(), IllegalStateException())
        app.chooseHelpImprove(false)
        assertFalse(app.preferences.helpImprove)
        assertTrue(app.getSharedPreferences("usage", Context.MODE_PRIVATE).all.isEmpty())
        assertNull(crashes.pending())
        app.usage.count(UsageKeys.SHARE)
        assertTrue(app.getSharedPreferences("usage", Context.MODE_PRIVATE).all.isEmpty())
        app.chooseHelpImprove(true)
    }
}
