package dev.ahmed.savlino.engine

import com.yausername.youtubedl_android.YoutubeDL
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.domain.LinkRevision
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class YtdlpUpdateStageTest {
    @get:Rule val temp = TemporaryFolder()
    private val asset = "#!/usr/bin/env python3\nprint('yt-dlp')\n".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(asset).joinToString("") { "%02x".format(it) }
    private val binaryUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.02/yt-dlp"
    private val sumsUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.11.02/SHA2-256SUMS"
    private val cdn = "https://release-assets.githubusercontent.com/asset"
    private fun release(tag: String = "2026.11.02", binary: String = binaryUrl, sums: String? = sumsUrl): String {
        val assets = mutableListOf("""{"name":"yt-dlp.exe","browser_download_url":"https://github.com/x.exe"}""",
            """{"name":"yt-dlp","browser_download_url":"$binary"}""")
        if (sums != null) assets += """{"name":"SHA2-256SUMS","browser_download_url":"$sums"}"""
        return """{"tag_name":"$tag","name":"yt-dlp $tag","assets":[${assets.joinToString(",")}]}"""
    }
    private val requests = mutableListOf<String>()
    private fun respond(chain: Interceptor.Chain, code: Int, body: ResponseBody = ByteArray(0).toResponseBody(),
        location: String? = null): Response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(code).message("x").body(body).apply { location?.let { header("Location", it) } }.build()
    private fun client(routes: (Interceptor.Chain, String) -> Response) = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain -> val url = chain.request().url.toString(); synchronized(requests) { requests += url }; routes(chain, url) }
        .build()
    private fun github(sums: String = "deadbeef  yt-dlp.exe\n$sha  yt-dlp\n", releaseJson: String = release(),
        assetBody: (Interceptor.Chain) -> Response = { respond(it, 200, asset.toResponseBody()) }) = client { chain, url ->
        when (url) {
            YtdlpRelease.API_URL -> respond(chain, 200, releaseJson.toResponseBody())
            sumsUrl -> respond(chain, 302, location = "$cdn/sums")
            "$cdn/sums" -> respond(chain, 200, sums.toResponseBody())
            binaryUrl -> respond(chain, 302, location = "$cdn/bin")
            "$cdn/bin" -> assetBody(chain)
            else -> respond(chain, 404)
        }
    }
    private fun fetcher(client: OkHttpClient, cache: File, installed: String? = null) = YtdlpReleaseFetcher(client, cache, { installed })

    @Test fun sourceAssetAndPreferencesMatchThePinnedLibraryUpdater() {
        val updater = Class.forName("com.yausername.youtubedl_android.YoutubeDLUpdater")
        fun constant(name: String) = updater.getDeclaredField(name).apply { isAccessible = true }.get(null)
        assertEquals(constant("youtubeDLStableChannelUrl"), YtdlpRelease.API_URL)
        assertEquals(YoutubeDL.UpdateChannel.STABLE.apiUrl, YtdlpRelease.API_URL)
        assertEquals(constant("dlpBinaryName"), YtdlpRelease.ASSET)
        assertEquals(constant("dlpVersionKey"), YtdlpRelease.VERSION_KEY)
        assertEquals(constant("dlpVersionNameKey"), YtdlpRelease.VERSION_NAME_KEY)
        assertEquals(YoutubeDL.baseName, YtdlpRelease.PREFS)
        assertEquals("yt-dlp", YoutubeDL.ytdlpDirName); assertEquals("yt-dlp", YoutubeDL.ytdlpBin)
    }

    @Test fun verifiedReleaseIsStagedThroughAllowedRedirects() = runBlocking {
        val cache = temp.newFolder()
        val staged = assertIs<StagedYtdlp>(fetcher(github(), cache, installed = "2025.11.12").fetch())
        assertEquals("2026.11.02", staged.tag); assertEquals("yt-dlp 2026.11.02", staged.name)
        assertContentEquals(asset, assertNotNull(staged.file).readBytes())
        assertEquals(cache, staged.file!!.parentFile)
        assertFalse(staged.toString().contains("2026"))
    }

    @Test fun currentTagDownloadsNothing() = runBlocking {
        val cache = temp.newFolder()
        val staged = assertIs<StagedYtdlp>(fetcher(github(), cache, installed = "2026.11.02").fetch())
        assertNull(staged.file)
        assertEquals(listOf(YtdlpRelease.API_URL), requests)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun bundledTagWithNoRecordedUpdateDownloadsNothing() = runBlocking {
        val cache = temp.newFolder()
        val staged = assertIs<StagedYtdlp>(fetcher(github(releaseJson = release(YtdlpRelease.BUNDLED_TAG)), cache).fetch())
        assertNull(staged.file)
        assertEquals(listOf(YtdlpRelease.API_URL), requests)
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun aReleaseNotNewerThanWhatRunsDownloadsNothing() = runBlocking {
        // Recorded older than the bundle (first start after an app upgrade, before init drops it): the bundle is current.
        // A newer or unknown recorded tag is never replaced by an older, equal or unknown release.
        for ((installed, remote) in listOf("2026.10.21" to YtdlpRelease.BUNDLED_TAG, "2026.11.09" to "2026.11.02",
            "2026.11.02.1" to "2026.11.02", "updated" to "2026.11.02", null to "2026.10.21", null to "latest")) {
            synchronized(requests) { requests.clear() }
            val cache = temp.newFolder()
            val staged = assertIs<StagedYtdlp>(fetcher(github(releaseJson = release(remote)), cache, installed).fetch())
            assertNull(staged.file, "$installed vs $remote")
            assertEquals(listOf(YtdlpRelease.API_URL), requests, "$installed vs $remote")
        }
        synchronized(requests) { requests.clear() }
        val staged = assertIs<StagedYtdlp>(fetcher(github(), temp.newFolder(), "2026.10.21").fetch())
        assertContentEquals(asset, assertNotNull(staged.file).readBytes()) // Newer than the bundle: fetched.
    }

    @Test fun onlyAStableTagOlderThanTheBundledOneIsOlder() {
        assertTrue(YtdlpStagedInstall.olderTag("2025.11.12", "2026.10.26"))
        assertTrue(YtdlpStagedInstall.olderTag("2026.10.21.3", "2026.10.26"))
        assertTrue(YtdlpStagedInstall.olderTag("2026.10.26", "2026.10.26.1"))
        for (kept in listOf("2026.10.26", "2026.10.26.1", "2026.11.02", "2027.01.01", "updated", "", "2026.10",
            "2026.10.21-nightly", "v2026.10.21", "2026.10.21.1.2", "2026..21", "99999999999.1.1"))
            assertFalse(YtdlpStagedInstall.olderTag(kept, "2026.10.26"), kept)
    }

    @Test fun hashMismatchOrMissingEntryFailsAndKeepsNoFile() = runBlocking {
        val failed = PreparedUpdate.Failed(EngineUpdateResult.Failure(DownloadError.Failed))
        for (sums in listOf("${"0".repeat(64)}  yt-dlp\n", "$sha  yt-dlp.exe\n", "not a sums file")) {
            val cache = temp.newFolder()
            assertEquals(failed, fetcher(github(sums = sums), cache).fetch())
            assertTrue(cache.listFiles()!!.isEmpty())
        }
        val cache = temp.newFolder()
        assertEquals(failed, fetcher(github(releaseJson = release(sums = null)), cache).fetch())
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun redirectsOffGithubOrToPlainHttpAreRefused() = runBlocking {
        for (target in listOf("https://example.org/bin", "http://release-assets.githubusercontent.com/bin")) {
            val cache = temp.newFolder()
            val client = client { chain, url -> when (url) {
                YtdlpRelease.API_URL -> respond(chain, 200, release().toResponseBody())
                sumsUrl -> respond(chain, 200, "$sha  yt-dlp\n".toResponseBody())
                binaryUrl -> respond(chain, 302, location = target)
                else -> respond(chain, 200, asset.toResponseBody())
            } }
            val error = assertFailsWith<ReleaseFetchException> { fetcher(client, cache).fetch() }
            assertFalse(error.message.orEmpty().contains("example") || error.message.orEmpty().contains("http"))
            assertTrue(cache.listFiles()!!.isEmpty())
            assertFalse(requests.contains(target))
        }
        val listed = release(binary = "https://example.org/yt-dlp")
        assertFailsWith<ReleaseFetchException> { fetcher(github(releaseJson = listed), temp.newFolder()).fetch() }
        Unit
    }

    @Test fun oversizedAssetIsRefusedWithAndWithoutContentLength() = runBlocking {
        val declared = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = YtdlpRelease.MAX_ASSET_BYTES + 1
            override fun source(): BufferedSource = asset.inputStream().source().buffer()
        }
        val endless = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = object : java.io.InputStream() {
                override fun read() = 0
                override fun read(b: ByteArray, off: Int, len: Int) = len
            }.source().buffer()
        }
        for (body in listOf(declared, endless)) {
            val cache = temp.newFolder()
            assertFailsWith<ReleaseFetchException> { fetcher(github(assetBody = { respond(it, 200, body) }), cache).fetch() }
            assertTrue(cache.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellingTheDownloadDeletesThePartialFile() = runBlocking {
        val cache = temp.newFolder()
        val reading = CountDownLatch(1)
        val stalled = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source(): BufferedSource = object : java.io.InputStream() {
                var sent = false
                override fun read() = throw IOException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!sent) { sent = true; b[off] = 1; return 1 }
                    reading.countDown()
                    Thread.sleep(30_000) // Interrupted by the cancelled caller.
                    return -1
                }
            }.source().buffer()
        }
        val task = async(Dispatchers.Default) { fetcher(github(assetBody = { respond(it, 200, stalled) }), cache).fetch() }
        assertTrue(withContext(Dispatchers.IO) { reading.await(3, TimeUnit.SECONDS) })
        assertEquals(1, cache.listFiles()!!.size)
        withTimeout(2000) { task.cancelAndJoin() }
        assertTrue(cache.listFiles()!!.isEmpty())
    }

    @Test fun sumsParserReadsOnlyTheExactAssetLine() {
        assertEquals(sha, YtdlpReleaseFetcher.expectedSha256("$sha  yt-dlp_linux\n$sha  yt-dlp\n"))
        assertEquals(sha, YtdlpReleaseFetcher.expectedSha256("$sha *yt-dlp"))
        assertNull(YtdlpReleaseFetcher.expectedSha256("$sha  yt-dlp.exe"))
        assertNull(YtdlpReleaseFetcher.expectedSha256("abc  yt-dlp"))
    }

    private class StagingRuntime : YtdlpRuntime {
        override val initialization = EngineInitialization()
        var version = "2025.11.12"
        var updates = 0
        var installs = mutableListOf<PreparedUpdate>()
        val fetching = CompletableDeferred<Unit>()
        var fetchCancelled = false
        var staged: PreparedUpdate = PreparedUpdate.Unstaged
        val releaseFetch = CompletableDeferred<Unit>()
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit) = version
        override fun destroy(processId: String) = true
        override fun update() { updates++ }
        override suspend fun prepareUpdate(): PreparedUpdate {
            fetching.complete(Unit)
            try { releaseFetch.await() } catch (cancelled: CancellationException) { fetchCancelled = true; throw cancelled }
            return staged
        }
        override fun installUpdate(prepared: PreparedUpdate) { installs += prepared; version = "2026.10.21" }
    }

    @Test fun networkStageHoldsNoGateAndCancellingItLeavesTheRuntimeUntouched() = runBlocking {
        val runtime = StagingRuntime()
        val engine = YoutubedlEngine(runtime)
        assertEquals("2025.11.12", engine.installedVersion())
        val prepare = async(Dispatchers.Default) { engine.prepareYtdlpUpdate() }
        withTimeout(2000) { runtime.fetching.await() }
        // A gated operation runs while the network stage is in flight; the fetch never finishes on its own.
        assertTrue(withTimeout(10_000) { engine.warmUp() })
        withTimeout(10_000) { prepare.cancelAndJoin() }
        assertTrue(runtime.fetchCancelled)
        assertEquals(0, runtime.updates); assertTrue(runtime.installs.isEmpty())
        assertEquals("2025.11.12", engine.installedVersion())
    }

    @Test fun installUsesTheStagedReleaseAndDeletesItsFile() = runBlocking {
        val file = temp.newFile().apply { writeBytes(asset) }
        val staged = StagedYtdlp(file, "2026.10.21", "yt-dlp 2026.10.21")
        val runtime = StagingRuntime().apply { this.staged = staged; releaseFetch.complete(Unit) }
        val engine = YoutubedlEngine(runtime)
        assertEquals(EngineUpdateResult.Success("2025.11.12", "2026.10.21"), engine.updateYtdlp())
        assertEquals(listOf<PreparedUpdate>(staged), runtime.installs)
        assertFalse(file.exists())
        val dropped = temp.newFile()
        engine.discardYtdlpUpdate(StagedYtdlp(dropped, "t", "n"))
        assertFalse(dropped.exists())
    }

    @Test fun failedNetworkStageNeverReachesInstall() = runBlocking {
        val runtime = object : YtdlpRuntime {
            override val initialization = EngineInitialization()
            var installs = 0
            override fun initialize() = Unit
            override fun execute(args: List<String>, processId: String, line: (String) -> Unit) = "2025.11.12"
            override fun destroy(processId: String) = true
            override fun update() { installs++ }
            override suspend fun prepareUpdate(): PreparedUpdate = throw ReleaseFetchException("status")
        }
        val engine = YoutubedlEngine(runtime)
        assertEquals(EngineUpdateResult.Failure(DownloadError.Network), engine.updateYtdlp())
        assertEquals(0, runtime.installs)
    }
    @Test fun fetchSweepsStaleUpdateFilesOnly() = runBlocking {
        val cache = temp.newFolder()
        val stale = File(cache, "${YtdlpReleaseFetcher.TEMP_PREFIX}1.tmp").apply { writeBytes(asset) }
        val other = File(cache, "yt-dlp-other.tmp").apply { writeText("x") }
        assertIs<StagedYtdlp>(fetcher(github(), cache, installed = "2026.10.26").fetch())
        assertFalse(stale.exists()); assertTrue(other.exists())
    }

    private inner class Install(val hadInstall: Boolean = true, val recordOk: Boolean = true) {
        val base = temp.newFolder()
        val dir = File(base, "yt-dlp")
        val staged = temp.newFile().apply { writeBytes(asset) }
        var updates = 0; var restores = 0
        val records = mutableListOf<Pair<String, Long>>()
        val installer = YtdlpStagedInstall(dir, "yt-dlp", { updates++ },
            { release, size -> records += release.tag to size; recordOk }, { restores++; dir.mkdirs() })
        init {
            if (hadInstall) { File(dir, "yt-dlp").apply { parentFile.mkdirs(); writeText("old") }; File(dir, "yt-dlp.zip").writeText("zip") }
        }
        fun leftovers() = base.list()!!.filter { it != "yt-dlp" }
    }

    @Test fun stagedInstallReplacesTheOldInstallAndRecordsLast() {
        val install = Install()
        install.installer.install(StagedYtdlp(install.staged, "2026.10.21", "n"))
        assertContentEquals(asset, File(install.dir, "yt-dlp").readBytes())
        assertEquals(listOf("yt-dlp"), install.dir.list()!!.toList()) // Materialized leftovers go with the old dir.
        assertEquals(listOf("2026.10.21" to asset.size.toLong()), install.records)
        assertFalse(install.staged.exists()); assertTrue(install.leftovers().isEmpty())
        assertEquals(0, install.updates); assertEquals(0, install.restores)
    }

    @Test fun currentTagTouchesNoFileAndNeverRunsTheLibraryUpdater() {
        val install = Install()
        install.installer.install(StagedYtdlp(null, "2026.10.21", "n"))
        assertEquals("old", File(install.dir, "yt-dlp").readText())
        assertEquals(0, install.updates); assertTrue(install.records.isEmpty()); assertEquals(0, install.restores)
        install.installer.install(PreparedUpdate.Unstaged)
        assertEquals(1, install.updates)
    }

    @Test fun failedInstallPutsTheOldInstallBackElseTheBundledOne() {
        val kept = Install(recordOk = false)
        assertFailsWith<ReleaseFetchException> { kept.installer.install(StagedYtdlp(kept.staged, "t", "n")) }
        assertEquals("old", File(kept.dir, "yt-dlp").readText()); assertEquals("zip", File(kept.dir, "yt-dlp.zip").readText())
        assertEquals(0, kept.restores); assertFalse(kept.staged.exists()); assertTrue(kept.leftovers().isEmpty())

        val fresh = Install(hadInstall = false, recordOk = false)
        assertFailsWith<YtdlpBundledRestored> { fresh.installer.install(StagedYtdlp(fresh.staged, "t", "n")) }
        assertEquals(1, fresh.restores); assertFalse(fresh.staged.exists())
    }

    @Test fun startupSweepKeepsTheNewBinaryOrPutsTheOldInstallBack() {
        // Killed after the new binary was renamed in: keep it, drop the old install.
        val renamedIn = Install()
        File(renamedIn.base, "yt-dlp.incoming").writeBytes(asset.copyOf(3))
        File(renamedIn.base, "yt-dlp.retired").apply { mkdirs(); File(this, "yt-dlp").writeText("older") }
        renamedIn.installer.sweep()
        assertTrue(renamedIn.leftovers().isEmpty()); assertEquals("old", File(renamedIn.dir, "yt-dlp").readText())
        // Killed between moving the old install aside and renaming the new one in (dir missing or empty).
        for (emptyDir in listOf(false, true)) {
            val movedAside = Install(hadInstall = false)
            File(movedAside.base, "yt-dlp.retired").apply { mkdirs(); File(this, "yt-dlp").writeText("old"); File(this, "yt-dlp.zip").writeText("zip") }
            if (emptyDir) movedAside.dir.mkdirs()
            movedAside.installer.sweep()
            assertTrue(movedAside.leftovers().isEmpty())
            assertEquals("old", File(movedAside.dir, "yt-dlp").readText()); assertEquals("zip", File(movedAside.dir, "yt-dlp.zip").readText())
        }
    }

    @Test fun repairKnowsOurRecordedSizeButKeepsUnknownUpdates() {
        assertEquals(7L, YtdlpStagedInstall.expectedSize(null, 99, { 7 }))
        assertEquals(99L, YtdlpStagedInstall.expectedSize("2026.10.21", 99, { 7 }))
        assertNull(YtdlpStagedInstall.expectedSize("2026.10.21", null, { 7 }))
    }

    private class BlockingInstallRuntime(val failWith: Exception? = null) : YtdlpRuntime {
        override val initialization = EngineInitialization()
        val installing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        override fun initialize() = Unit
        override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
            if ("--dump-json" in args) {
                events += "inspect"
                return """{"formats":[{"format_id":"18","ext":"mp4","vcodec":"h264","acodec":"aac"}]}"""
            }
            return "2025.11.12"
        }
        override fun destroy(processId: String) = true
        override fun update() = Unit
        override fun installUpdate(prepared: PreparedUpdate) {
            installing.countDown()
            release.await(30, TimeUnit.SECONDS)
            events += "installed"
            failWith?.let { throw it }
        }
    }

    @Test fun inspectionAfterTheCallerStoppedWaitingStillWaitsForTheInstall() = runBlocking {
        val runtime = BlockingInstallRuntime()
        val engine = YoutubedlEngine(runtime, EngineLimits(updateMs = 300))
        assertEquals("2025.11.12", engine.installedVersion())
        // The caller (EngineMaintenance) gives up after updateMs and releases its own gate.
        assertEquals(EngineUpdateResult.Failure(DownloadError.Network), engine.installYtdlpUpdate(PreparedUpdate.Unstaged))
        assertTrue(runtime.installing.await(0, TimeUnit.SECONDS))
        val source = LinkRevision(assertNotNull(LinkInput.parse("https://example.org/video")), 1)
        val inspection = async(Dispatchers.Default) { engine.inspect(source, "during-install") }
        try {
            // The install still holds the native writer gate.
            assertNull(withTimeoutOrNull(500) { inspection.await() })
            assertTrue(runtime.events.isEmpty())
        } finally { runtime.release.countDown() }
        assertIs<InspectResult.Success>(withTimeout(10_000) { inspection.await() })
        assertEquals(listOf("installed", "inspect"), runtime.events.toList())
    }

    @Test fun installThatRestoredTheBundledCopyReportsBuiltIn() = runBlocking {
        val runtime = BlockingInstallRuntime(failWith = YtdlpBundledRestored()).apply { release.countDown() }
        val engine = YoutubedlEngine(runtime)
        assertEquals(EngineUpdateResult.Failure(DownloadError.Failed, usingBuiltIn = true),
            engine.installYtdlpUpdate(PreparedUpdate.Unstaged))
        assertEquals("2025.11.12", engine.installedVersion())
    }
}
