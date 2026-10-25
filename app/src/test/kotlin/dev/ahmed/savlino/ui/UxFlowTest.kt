package dev.ahmed.savlino.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.entry.ShareInput
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.jobs.JobStage
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.storage.PendingPublication
import dev.ahmed.savlino.storage.PublishedFile
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.ui.theme.NezzelTheme
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** UX critique units U1 (share front door), U3 (tier copy), U4 (failure sheet) and the U6 "saved before" notice. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UxFlowTest {
    private val url = "https://youtu.be/one"
    private val app get() = RuntimeEnvironment.getApplication()
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun share(value: String) = ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value))
    private fun engine(vararg formats: Format) = object : EngineAdapter by FakeEngine() {
        val inspected = mutableListOf<String>()
        override val isAvailable = true
        override suspend fun installedVersion() = "fixture-v1"
        override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
            inspected += source.link.rawUrl
            return InspectResult.Success(Inspection(source, "fixture-v1", formats.toList(),
                listOf(PresetChoice(Preset.BALANCED, formats.first().id))))
        }
    }
    private val v720 = Format("v720", "mp4", true, true, height = 720, width = 1280, size = Size.Exact(5_000_000))
    private val v360 = Format("v360", "mp4", true, true, height = 360, width = 640, size = Size.Exact(2_000_000))

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            idle(); Thread.sleep(20)
        }
    }

    // U1 -----------------------------------------------------------------------------------------

    @Test fun shareAfterACompletedDownloadWithAnUntouchedFieldNeedsNoPrompt() {
        val store = JobStore(app); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = engine(v720)
        val coordinator = DownloadCoordinator(app, engine, store, workManager = { error("no scheduler in fixture") })
        try {
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, currentEngineVersion = { "fixture-v1" },
                downloads = coordinator, coordinatorScope = scope)
            vm.onNewShare(share(url)); idle()
            assertTrue(vm.downloadEnabled)
            vm.requestDownload()
            waitFor("field cleared after enqueue") { vm.text.isEmpty() }
            assertEquals(OptionsState.Hidden, vm.optionsState); assertFalse(vm.shareActive)
            assertEquals(1, store.all().size)
            vm.onNewShare(share("https://youtu.be/two")); idle()
            assertFalse(vm.hasPendingShare)
            assertEquals("https://youtu.be/two", vm.text)
            assertEquals(listOf(url, "https://youtu.be/two"), engine.inspected)
        } finally { scope.cancel(); store.close() }
    }

    @Test fun anEditedDraftStillPromptsAndNamesTheSharedHost() {
        val vm = LinkViewModel(SavedStateHandle(), engine = engine(v720), currentEngineVersion = { "fixture-v1" })
        vm.edit("https://youtu.be/typed-by-hand")
        vm.onNewShare(share("https://www.youtube.com/watch?v=x")); idle()
        assertTrue(vm.hasPendingShare)
        assertEquals("https://youtu.be/typed-by-hand", vm.text)
        assertEquals("www.youtube.com", vm.pendingShareHost)
        // A typed link that was checked is still the person's own draft.
        val checked = LinkViewModel(SavedStateHandle(), engine = engine(v720), currentEngineVersion = { "fixture-v1" })
        checked.edit(url); checked.checkLink(); idle()
        checked.onNewShare(share("https://youtu.be/two")); idle()
        assertTrue(checked.hasPendingShare); assertEquals(url, checked.text)
    }

    @Test fun typedDraftGuardSurvivesProcessRestore() {
        val state = SavedStateHandle()
        LinkViewModel(state, engine = engine(v720)).edit("https://youtu.be/typed")
        val restored = LinkViewModel(SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) }), engine = engine(v720))
        restored.onNewShare(share("https://youtu.be/two")); idle()
        assertTrue(restored.hasPendingShare); assertEquals("https://youtu.be/typed", restored.text)
    }

    // U4 -----------------------------------------------------------------------------------------

    private fun online() {
        val manager = app.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        shadowOf(manager).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
        assertFalse(dev.ahmed.savlino.downloads.isOffline(app))
    }

    private fun failing(error: DownloadError) = LinkViewModel(SavedStateHandle(),
        engine = FakeEngine(InspectResult.Failure(error)), currentEngineVersion = { "fixture-v1" }).also {
        it.edit(url); it.checkLink(); idle()
    }

    private fun render(language: String, content: @androidx.compose.runtime.Composable () -> Unit): Pair<List<SemanticsNode>, android.content.Context> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val activity = controller.get()
        val configuration = Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("$language-u-nu-latn")) }
        val context = activity.createConfigurationContext(configuration)
        activity.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration) {
                NezzelTheme(ThemePref.Light, false) { content() }
            }
        }
        val nodes = semanticsNodes(activity, rounds = 4, idleMillis = 100)
        controller.pause().stop().destroy()
        return nodes to context
    }

    @Test fun failureSheetShowsTruthfulCopyPerTypeWithNoSkeletonAndTheRightActions() {
        data class Case(val error: DownloadError, val title: Int, val body: Int, val retry: Boolean)
        val cases = listOf(
            Case(DownloadError.EngineUnavailable, R.string.unavailable_title, R.string.unavailable_body, true),
            Case(DownloadError.Failed, R.string.read_failed_title, R.string.read_failed_body, true),
            Case(DownloadError.Unavailable, R.string.video_gone_title, R.string.video_gone_body, false),
            Case(DownloadError.NotAPost, R.string.not_a_post_title, R.string.not_a_post_body, false),
            Case(DownloadError.NoVideo, R.string.no_video_title, R.string.no_video_body, false),
            Case(DownloadError.LoginRequired, R.string.private_title, R.string.private_body, false),
            Case(DownloadError.SourceRefused, R.string.source_refused_title, R.string.source_refused_body, false),
            Case(DownloadError.Offline, R.string.offline_title, R.string.offline_body, true),
            Case(DownloadError.Network, R.string.network_title, R.string.network_body, true),
        )
        online() // Robolectric starts offline, which turns Network into the offline copy (OptionsNetworkCopyTest)
        for (language in listOf("en", "ar")) for (case in cases) {
            val vm = failing(case.error)
            assertEquals(OptionsState.Error(case.error), vm.optionsState)
            val (nodes, context) = render(language) { OptionsSheetContent(vm, language, vm::dismissOptions) }
            val labels = texts(nodes)
            val tags = nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
            val name = "$language ${case.error}: $labels"
            assertTrue(name, labels.contains(context.getString(case.title)))
            assertTrue(name, labels.contains(context.getString(case.body)))
            if (case.error == DownloadError.LoginRequired)
                assertFalse(name, labels.any { it.contains("private", ignoreCase = true) || it.contains("خاص") })
            assertFalse(name, "preview_thumbnail_placeholder" in tags || "preview_title_skeleton" in tags)
            assertTrue(name, "failure_content" in tags)
            assertTrue(name, labels.any { it.contains("youtu.be") })
            assertEquals(name, case.retry, labels.contains(context.getString(R.string.try_again)))
            assertEquals(name, case.error != DownloadError.EngineUnavailable,
                labels.contains(context.getString(R.string.paste_another_link)))
            assertTrue(name, labels.contains(context.getString(R.string.nezzel_close)))
            vm.dismissOptions()
        }
        assertFalse(app.getString(R.string.read_failed_body).contains("changed"))
        assertNotEquals(app.getString(R.string.offline_title), app.getString(R.string.network_title))
        assertNotEquals(app.getString(R.string.offline_body), app.getString(R.string.network_body))
    }

    @Test fun pasteAnotherLinkLoadsADifferentLinkAndNeverRetriesTheSameOne() {
        val vm = failing(DownloadError.Unavailable)
        vm.pasteAnother(url); idle()
        assertEquals("", vm.text); assertEquals(OptionsState.Hidden, vm.optionsState)
        val again = failing(DownloadError.Unavailable)
        again.pasteAnother(null); idle()
        assertEquals("", again.text); assertEquals(OptionsState.Hidden, again.optionsState)
        val next = failing(DownloadError.Unavailable)
        next.pasteAnother("https://youtu.be/other"); idle()
        assertEquals("https://youtu.be/other", next.text); assertTrue(next.copiedLink)
        assertEquals(OptionsState.Error(DownloadError.Unavailable), next.optionsState)
    }

    // U3 -----------------------------------------------------------------------------------------

    @Test fun tierCopyIsDistinctInBothLanguages() {
        for (language in listOf("en", "ar")) {
            val configuration = Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
            val context = app.createConfigurationContext(configuration)
            val titles = FormatChoices.Tier.entries.map { context.getString(it.title) }
            val hints = FormatChoices.Tier.entries.map { context.getString(it.hint) }
            assertEquals(titles.toString(), titles.size, titles.toSet().size)
            assertEquals(hints.toString(), hints.size, hints.toSet().size)
            // The Best tag never contradicts a low tier's hint ("Best · Least clear").
            assertNotEquals(context.getString(R.string.preset_best), context.getString(R.string.quality_4k_hint))
        }
    }

    // U6 -----------------------------------------------------------------------------------------

    private fun complete(store: JobStore, id: String, format: Format, source: LinkRevision, uri: Uri) {
        val command = (DownloadCommand.confirm(id, Inspection(source, "fixture-v1", listOf(format)), source, format.id, "clip")
            as ConfirmResult.Confirmed).command
        assertTrue(store.enqueue(command))
        assertTrue(store.startTransfer(id)); assertTrue(store.markStaged(id))
        assertTrue(store.recordPending(id, PendingPublication(uri, "clip.mp4")))
        assertTrue(store.complete(id, PublishedFile(uri, "clip.mp4")))
    }

    private val keyer = LinkKeyer.withSecret(ByteArray(32) { (it * 7).toByte() })
    private fun sha256(value: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun storedKeys(store: JobStore): List<String?> = store.readableDatabase.rawQuery("SELECT source_key FROM jobs", null)
        .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    @Test fun journalMatchesSavedFilesByAKeyedTagWithoutKeepingTheLink() {
        app.deleteDatabase(JobStore.DATABASE_NAME)
        JobStore(app, keyer).use { store ->
            val source = LinkRevision(LinkInput.parse(url)!!, 1)
            complete(store, "done-720", v720, source, Uri.parse("content://media/external/downloads/1"))
            assertNull(store.get("done-720")!!.sourceUrl)
            val found = store.savedFormats(source.link.normalizedUrl)
            assertEquals(setOf("v720"), found.keys)
            assertEquals(Uri.parse("content://media/external/downloads/1"), found["v720"]!!.uri)
            assertTrue(store.savedFormats(LinkInput.parse("https://youtu.be/two")!!.normalizedUrl).isEmpty())
            val tag = storedKeys(store).single()!!
            // Not guessable by hashing candidate URLs, and unrelated to the clipboard tag of the same link.
            assertNotEquals(sha256(source.link.normalizedUrl), tag)
            assertNotEquals(keyer.key(LinkKeyer.CLIPBOARD, source.link.normalizedUrl), tag)
            assertEquals(keyer.key(LinkKeyer.JOB_SOURCE, source.link.normalizedUrl), tag)
        }
        // Another install key never matches this journal's tags.
        JobStore(app, LinkKeyer.withSecret(ByteArray(32) { 1 })).use { other ->
            assertTrue(other.savedFormats(LinkInput.parse(url)!!.normalizedUrl).isEmpty())
        }
    }

    @Test fun withoutAKeystoreKeyNothingIsTaggedAndTheNoticeStaysOff() {
        app.deleteDatabase(JobStore.DATABASE_NAME)
        JobStore(app, sourceKeys = null).use { store ->
            val source = LinkRevision(LinkInput.parse(url)!!, 1)
            complete(store, "done", v720, source, Uri.parse("content://media/external/downloads/2"))
            assertEquals(listOf<String?>(null), storedKeys(store))
            assertTrue(store.savedFormats(source.link.normalizedUrl).isEmpty())
        }
    }

    @Test fun clipboardTagsAreKeyedAndDomainSeparated() {
        val link = LinkInput.parse(url)!!
        val gate = SuggestionGate(keyer = keyer); gate.dismiss(link)
        val tag = gate.dismissedHashes().single()
        assertEquals(keyer.key(LinkKeyer.CLIPBOARD, link.normalizedUrl), tag)
        assertNotEquals(sha256(link.normalizedUrl), tag)
        // A tag stored under the old unkeyed scheme no longer matches: one extra suggestion, by decision.
        assertNotNull(SuggestionGate(setOf(sha256(link.normalizedUrl)), keyer).offer(url))
        assertNull(SuggestionGate(setOf(tag), keyer).offer(url))
    }

    private fun legacyJournal(version: Int) {
        app.deleteDatabase(JobStore.DATABASE_NAME)
        val path = app.getDatabasePath(JobStore.DATABASE_NAME).apply { parentFile!!.mkdirs() }
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""CREATE TABLE jobs (job_id TEXT PRIMARY KEY NOT NULL, stage TEXT NOT NULL,
                source_url TEXT, revision INTEGER NOT NULL, engine_version TEXT NOT NULL,
                format_json TEXT NOT NULL, filename TEXT NOT NULL, preset TEXT,
                pending_uri TEXT, display_name TEXT, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, error TEXT, retryable INTEGER NOT NULL DEFAULT 0)""")
            val format = """{"id":"v720","sourceId":"v720","container":"mp4","video":true,"audio":true,"merge":false,"sizeKind":"unknown"}"""
            val now = System.currentTimeMillis()
            db.execSQL("INSERT INTO jobs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>("old", "COMPLETED", null, 1,
                "e", format, "old.mp4", null, "content://media/external/downloads/3", "old.mp4", now, now, null, 0))
            db.execSQL("INSERT INTO jobs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)", arrayOf<Any?>("active", "ENQUEUED", url, 1,
                "e", format, "a.mp4", null, null, null, now, now, null, 0))
            db.version = version
        }
    }

    /** Jobs queued before the upgrade still count as "saved before" once they finish (backfill in the migration). */
    @Test fun legacyActiveJobsAreBackfilledAndCountOnceCompleted() {
        for (version in listOf(1, 2)) {
            legacyJournal(version)
            JobStore(app, keyer).use { store ->
                assertEquals(JobStore.DATABASE_VERSION, store.writableDatabase.version)
                assertEquals(JobStage.COMPLETED, store.get("old")!!.stage)
                assertEquals("v$version", 2, store.all().size)
                val uri = Uri.parse("content://media/external/downloads/9")
                assertTrue(store.startTransfer("active")); assertTrue(store.markStaged("active"))
                assertTrue(store.recordPending("active", PendingPublication(uri, "a.mp4")))
                assertTrue(store.complete("active", PublishedFile(uri, "a.mp4")))
                assertEquals("v$version", uri, store.savedFormats(LinkInput.parse(url)!!.normalizedUrl)["v720"]?.uri)
            }
        }
    }

    /** A row whose backfill never ran (no key at upgrade time) is tagged at completion, before the link is cleared. */
    @Test fun completionTagsAnUntaggedRowBeforeClearingItsLink() {
        legacyJournal(2)
        JobStore(app, sourceKeys = null).use { it.writableDatabase }
        JobStore(app, keyer).use { store ->
            assertNull(store.readableDatabase.rawQuery("SELECT source_key FROM jobs WHERE job_id = 'active'", null)
                .use { it.moveToFirst(); it.getString(0) })
            val uri = Uri.parse("content://media/external/downloads/10")
            assertTrue(store.startTransfer("active")); assertTrue(store.markStaged("active"))
            assertTrue(store.recordPending("active", PendingPublication(uri, "a.mp4")))
            assertTrue(store.complete("active", PublishedFile(uri, "a.mp4")))
            assertNull(store.get("active")!!.sourceUrl)
            assertEquals(uri, store.savedFormats(LinkInput.parse(url)!!.normalizedUrl)["v720"]?.uri)
        }
    }

    @Test fun alreadySavedNoticeShowsForTheSameFormatOnlyAndNeverBlocksDownload() {
        app.deleteDatabase(JobStore.DATABASE_NAME)
        val store = JobStore(app, keyer)
        val source = LinkRevision(LinkInput.parse(url)!!, 1)
        complete(store, "done-720", v720, source, Uri.parse("content://media/external/downloads/7"))
        var exists = true
        val engine = engine(v720, v360)
        val coordinator = DownloadCoordinator(app, engine, store, workManager = { error("no scheduler in fixture") },
            savedFileExists = { exists })
        try {
            val vm = LinkViewModel(SavedStateHandle(), engine = engine, currentEngineVersion = { "fixture-v1" }, downloads = coordinator)
            vm.paste(url)
            waitFor("saved lookup") { vm.savedBefore != null }
            assertTrue(vm.downloadEnabled)
            // The notice and destination scroll with the options; the pinned footer holds only the action.
            val (nodes, context) = render("en") { ConfirmationDetails(vm, "en") }
            assertTrue(nodes.any { it.config.getOrNull(SemanticsProperties.TestTag) == "saved_before" })
            assertTrue(texts(nodes).contains(context.getString(R.string.open_file)))
            val savesTo = context.getString(R.string.saves_to, NezzelFormat.isolate(context.getString(R.string.save_path)))
            assertTrue(texts(nodes).contains(savesTo))
            val (footer, _) = render("en") { ConfirmationScreen(vm, "en") }
            assertFalse(footer.any { it.config.getOrNull(SemanticsProperties.TestTag) == "saved_before" })
            assertFalse(texts(footer).contains(savesTo))
            assertTrue(footer.any { it.config.getOrNull(SemanticsActions.OnClick) != null })
            vm.selectFormat("v360")
            assertNull(vm.savedBefore)
            val (other, _) = render("en") { ConfirmationDetails(vm, "en") }
            assertFalse(other.any { it.config.getOrNull(SemanticsProperties.TestTag) == "saved_before" })
            exists = false
            vm.dismissOptions(); vm.edit(""); vm.paste(url)
            waitFor("options") { vm.optionsState is OptionsState.Ready }
            Thread.sleep(200); idle()
            assertNull("a file deleted outside Nezzel no longer counts", vm.savedBefore)
        } finally { store.close() }
    }
}
