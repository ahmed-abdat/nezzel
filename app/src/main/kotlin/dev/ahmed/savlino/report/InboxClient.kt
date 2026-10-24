package dev.ahmed.savlino.report

import dev.ahmed.savlino.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The private inbox on the site: one POST per report or daily usage ping, no retries,
 * cookies or redirects. Bodies and responses are never logged.
 */
class InboxClient(private val calls: Call.Factory? = null, private val version: String = BuildConfig.VERSION_NAME) {
    private val client by lazy {
        OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).cache(null)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS).build()
    }

    /** True only for the inbox's 204; any failure is false and nothing is queued. */
    suspend fun post(path: String, json: String): Boolean = withContext(Dispatchers.IO) {
        require(path in PATHS)
        // Debug builds and JVM tests never reach the live inbox; only release builds count.
        if (calls == null && BuildConfig.DEBUG) return@withContext false
        try {
            val request = Request.Builder().url(BASE + path).header("User-Agent", "Nezzel/$version")
                .post(json.toRequestBody(JSON)).build()
            (calls ?: client).newCall(request).execute().use { it.code == 204 }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { false }
        catch (_: RuntimeException) { false }
    }

    suspend fun send(report: Report): Boolean = post(REPORT, report.json())

    companion object {
        const val BASE = "https://nezzel.hululsoft.mr"
        const val REPORT = "/api/report"
        const val USAGE = "/api/usage"
        private val PATHS = setOf(REPORT, USAGE)
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
