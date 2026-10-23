package dev.ahmed.savlino.engine

import com.yausername.youtubedl_android.YoutubeDL
import dev.ahmed.savlino.domain.DownloadError
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
    private val binaryUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.21/yt-dlp"
    private val sumsUrl = "https://github.com/yt-dlp/yt-dlp/releases/download/2026.10.21/SHA2-256SUMS"
    private val cdn = "https://release-assets.githubusercontent.com/asset"
    private fun release(tag: String = "2026.10.21", binary: String = binaryUrl, sums: String? = sumsUrl): String {
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
        assertEquals("2026.10.21", staged.tag); assertEquals("yt-dlp 2026.10.21", staged.name)
        assertContentEquals(asset, assertNotNull(staged.file).readBytes())
        assertEquals(cache, staged.file!!.parentFile)
        assertFalse(staged.toString().contains("2026"))
    }

    @Test fun currentTagDownloadsNothing() = runBlocking {
        val cache = temp.newFolder()
        val staged = assertIs<StagedYtdlp>(fetcher(github(), cache, installed = "2026.10.21").fetch())
        assertNull(staged.file)
        assertEquals(listOf(YtdlpRelease.API_URL), requests)
        assertTrue(cache.listFiles()!!.isEmpty())
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
        // A gated operation runs while the network stage is in flight.
        assertTrue(withTimeout(1000) { engine.warmUp() })
        withTimeout(1000) { prepare.cancelAndJoin() }
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
}
