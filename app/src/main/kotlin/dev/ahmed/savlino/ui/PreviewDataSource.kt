package dev.ahmed.savlino.ui

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import dev.ahmed.savlino.engine.PreviewStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * Plain HTTPS reads for the preview player over OkHttp with [PreviewStream.publicDns]: every connect, redirects included,
 * goes only to the public addresses that were checked, and the connected socket is checked again. No proxy (a proxy would resolve the host itself), no https→http.
 */
@UnstableApi
internal class PreviewDataSource(private val call: Call.Factory, private val headers: Map<String, String>) : BaseDataSource(true) {
    private var response: Response? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var spec: DataSpec? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        val range = when {
            dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong() -> null
            dataSpec.length == C.LENGTH_UNSET.toLong() -> "bytes=${dataSpec.position}-"
            else -> "bytes=${dataSpec.position}-${dataSpec.position + dataSpec.length - 1}"
        }
        val request = Request.Builder().url(dataSpec.uri.toString()).apply {
            headers.forEach { (name, value) -> header(name, value) }
            range?.let { header("Range", it) }
        }.build()
        val reply = try { call.newCall(request).execute() } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        response = reply
        if (!reply.isSuccessful || !reply.request.url.isHttps) {
            reply.close(); response = null
            throw HttpDataSource.InvalidResponseCodeException(reply.code, reply.message, null, reply.headers.toMultimap(), dataSpec, ByteArray(0))
        }
        val stream = reply.body.byteStream()
        // A server that ignores Range sends the whole file: skip to the wanted byte.
        if (reply.code == 200 && dataSpec.position > 0) {
            var skip = dataSpec.position
            while (skip > 0) { val n = stream.skip(skip); if (n <= 0) { reply.close(); throw HttpDataSource.HttpDataSourceException(dataSpec,
                PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, HttpDataSource.HttpDataSourceException.TYPE_OPEN) }; skip -= n }
        }
        input = stream
        val body = reply.body.contentLength().let { if (it >= 0 && reply.code == 200) it - dataSpec.position else it }
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else if (body >= 0) body else C.LENGTH_UNSET.toLong()
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val wanted = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
        val read = try { input!!.read(buffer, offset, wanted) } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, spec!!, HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
        if (read == -1) return C.RESULT_END_OF_INPUT
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = response?.request?.url?.toString()?.let(Uri::parse)

    override fun close() {
        response?.close(); response = null; input = null
        if (opened) { opened = false; transferEnded() }
    }

    class Factory(private val headers: Map<String, String>) : DataSource.Factory {
        override fun createDataSource(): DataSource = PreviewDataSource(client, headers)
    }

    companion object {
        private val client by lazy {
            OkHttpClient.Builder().dns(PreviewStream.publicDns).proxy(Proxy.NO_PROXY).followSslRedirects(false)
                // IP-literal hosts never reach the Dns: check the socket actually connected, on every hop.
                .addNetworkInterceptor { chain ->
                    val address = chain.connection()?.route()?.socketAddress?.address
                    if (address == null || !PreviewStream.isPublic(address)) throw IOException("not public")
                    chain.proceed(chain.request())
                }
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        }
    }
}
