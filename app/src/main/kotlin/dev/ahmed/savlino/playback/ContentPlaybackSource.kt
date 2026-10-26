package dev.ahmed.savlino.playback

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Sanitized failures: provider messages can contain private content URIs. */
class LocalSourceException(val failure: PlaybackFailure) : IOException("Local playback source unavailable")

/** No HTTP/file/asset fallback exists, including when the extractor reopens a source. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ContentPlaybackSource private constructor(context: Context) : DataSource {
    private val content = ContentDataSource(context.applicationContext)
    override fun addTransferListener(transferListener: TransferListener) = content.addTransferListener(transferListener)
    override fun getUri(): Uri? = content.uri
    override fun open(dataSpec: DataSpec): Long {
        requireContent(dataSpec.uri)
        return try { content.open(dataSpec) } catch (_: Exception) { throw LocalSourceException(PlaybackFailure.UNAVAILABLE) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try { content.read(buffer, offset, length) }
        catch (_: Exception) { throw LocalSourceException(PlaybackFailure.UNAVAILABLE) }
    override fun close() { try { content.close() } catch (_: IOException) { /* Closing cannot block viewer disposal. */ } }
    class Factory(private val context: Context) : DataSource.Factory {
        override fun createDataSource(): DataSource = ContentPlaybackSource(context)
    }
    companion object {
        fun requireContent(uri: Uri) {
            if (uri.scheme != "content" || uri.authority.isNullOrBlank()) throw LocalSourceException(PlaybackFailure.UNAVAILABLE)
        }
    }
}
