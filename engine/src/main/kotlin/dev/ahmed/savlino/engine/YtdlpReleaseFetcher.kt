package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.DownloadError
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Same release as youtubedl-android 0.18.1 `YoutubeDLUpdater` with `UpdateChannel.STABLE`: release API, `yt-dlp`
 * asset, `dlpVersion`/`dlpVersionName` preferences in `youtubedl-android`. Adds a SHA2-256SUMS check (lead 2026-10-23).
 */
internal object YtdlpRelease {
    const val API_URL = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
    const val ASSET = "yt-dlp"
    const val SUMS = "SHA2-256SUMS"
    const val PREFS = "youtubedl-android"
    const val VERSION_KEY = "dlpVersion"
    const val VERSION_NAME_KEY = "dlpVersionName"
    /** The 2026.10.21 asset is 3,074,818 bytes. */
    const val MAX_ASSET_BYTES = 15L * 1024 * 1024
    const val MAX_TEXT_BYTES = 1L * 1024 * 1024
    const val MAX_HOPS = 5
    /** The API host, release pages and the hosts GitHub redirects release assets to. */
    val HOSTS = setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")
}

/** A verified release in the cache directory, or [file] null when the installed tag is already current. */
internal class StagedYtdlp(val file: File?, val tag: String, val name: String) : PreparedUpdate {
    override fun toString() = "StagedYtdlp(redacted)"
}

/** Fixed messages only: no URL, tag or server text reaches an exception or log. */
internal class ReleaseFetchException(reason: String) : IOException(reason)

/**
 * Network-only stage of the yt-dlp update. Cancelling the caller cancels the in-flight call; the temporary file is
 * always deleted unless returned verified.
 */
internal class YtdlpReleaseFetcher(private val client: OkHttpClient, private val cacheDir: File,
    private val installedTag: () -> String?, private val apiUrl: String = YtdlpRelease.API_URL) {
    private val mapper = ObjectMapper()

    suspend fun fetch(): PreparedUpdate {
        val release = try { mapper.readTree(text(apiUrl)) } catch (_: com.fasterxml.jackson.core.JsonProcessingException) {
            throw ReleaseFetchException("release metadata")
        }
        val tag = release?.get("tag_name")?.asText().orEmpty()
        val name = release?.get("name")?.asText().orEmpty()
        if (tag.isEmpty()) throw ReleaseFetchException("release metadata")
        if (tag == installedTag()) return StagedYtdlp(null, tag, name)
        val binary = assetUrl(release, YtdlpRelease.ASSET) ?: throw ReleaseFetchException("release asset")
        val sums = assetUrl(release, YtdlpRelease.SUMS) ?: return rejected()
        val expected = expectedSha256(text(sums)) ?: return rejected()
        cacheDir.mkdirs()
        val temp = File.createTempFile(YtdlpRelease.ASSET, null, cacheDir)
        var kept = false
        try {
            val actual = get(binary, YtdlpRelease.MAX_ASSET_BYTES) { body -> temp.outputStream().use { body(it) } }
            if (!actual.equals(expected, ignoreCase = true)) return rejected()
            kept = true
            return StagedYtdlp(temp, tag, name)
        } finally { if (!kept) temp.delete() }
    }

    private fun rejected() = PreparedUpdate.Failed(EngineUpdateResult.Failure(DownloadError.Failed))

    private fun assetUrl(release: JsonNode, asset: String): String? = release.get("assets")
        ?.firstOrNull { it.get("name")?.asText() == asset }
        ?.get("browser_download_url")?.asText()?.takeIf(String::isNotEmpty)

    private suspend fun text(url: String): String {
        val out = java.io.ByteArrayOutputStream()
        get(url, YtdlpRelease.MAX_TEXT_BYTES) { body -> body(out) }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Follows at most [YtdlpRelease.MAX_HOPS] redirects, each https on an allowed host; returns the body's SHA-256. */
    private suspend fun get(start: String, cap: Long, sink: ((java.io.OutputStream) -> Unit) -> Unit): String {
        var url = allowed(start.toHttpUrlOrNull())
        repeat(YtdlpRelease.MAX_HOPS + 1) {
            val next = exchange(url) { response ->
                if (response.isRedirect) return@exchange allowed(response.header("Location")?.let(url::resolve)) to null
                if (response.code != 200) throw ReleaseFetchException("status")
                val body = response.body
                if (body.contentLength() > cap) throw ReleaseFetchException("size")
                val digest = MessageDigest.getInstance("SHA-256")
                sink { out ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > cap) throw ReleaseFetchException("size")
                            digest.update(buffer, 0, read)
                            out.write(buffer, 0, read)
                        }
                    }
                }
                null to digest.digest().joinToString("") { "%02x".format(it) }
            }
            next.second?.let { return it }
            url = next.first!!
        }
        throw ReleaseFetchException("redirects")
    }

    private fun allowed(url: HttpUrl?): HttpUrl =
        url?.takeIf { it.isHttps && it.host in YtdlpRelease.HOSTS } ?: throw ReleaseFetchException("host")

    /** Blocking call on IO; a caller cancel cancels the call, which ends a blocked read before this returns. */
    private suspend fun <T> exchange(url: HttpUrl, read: (Response) -> T): T = coroutineScope {
        val call = client.newCall(Request.Builder().url(url).build())
        val canceller = launch { try { awaitCancellation() } finally { call.cancel() } }
        try { runInterruptible(Dispatchers.IO) { call.execute().use(read) } } finally { canceller.cancel() }
    }

    companion object {
        /** Production transport: no automatic redirects (checked per hop above), same connect/read limits as 0.18.1. */
        fun client(callTimeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
            .build()

        /** `<sha256>  yt-dlp` line of SHA2-256SUMS; null when missing or malformed. */
        fun expectedSha256(sums: String): String? = sums.lineSequence()
            .map { it.trim().split(Regex("\\s+"), limit = 2) }
            .firstOrNull { it.size == 2 && it[1].removePrefix("*") == YtdlpRelease.ASSET }
            ?.get(0)?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
    }
}
