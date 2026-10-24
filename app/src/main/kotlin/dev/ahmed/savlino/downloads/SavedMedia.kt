package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.storage.SaveDestinations
import dev.ahmed.savlino.domain.SearchText

import android.content.*
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.ahmed.savlino.locale.NezzelFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SavedMedia(
    val uri: Uri, val filename: String, val title: String, val mimeType: String,
    val size: Long?, val addedSeconds: Long, val height: Int?, val bitrateKbps: Int?, val durationMillis: Long?,
    val width: Int? = null,
    val relativePath: String? = null,
    /** MediaStore volume ("external_primary", an SD card id); null for folder documents. */
    val volumeName: String? = null,
) {
    val searchKey: String by lazy { SearchText.fold("$title $filename") }
    val kind: SavedMediaKind? get() = when {
        mimeType.startsWith("image/") -> SavedMediaKind.IMAGE
        mimeType.startsWith("video/") -> SavedMediaKind.VIDEO
        mimeType.startsWith("audio/") -> SavedMediaKind.AUDIO
        else -> null
    }
    val audio get() = kind == SavedMediaKind.AUDIO
    val format get() = when (mimeType) {
        "image/jpeg" -> "JPEG"
        "image/png" -> "PNG"
        "image/webp" -> "WebP"
        "image/gif" -> "GIF"
        "image/avif" -> "AVIF"
        "video/mp4" -> "MP4"
        "video/x-matroska" -> "MKV"
        "video/webm" -> "WebM"
        "video/quicktime" -> "MOV"
        "audio/mpeg" -> "MP3"
        "audio/mp4", "audio/x-m4a" -> "M4A"
        else -> mimeType
    }
    val date: LocalDate get() = Instant.ofEpochSecond(addedSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
}
enum class SavedMediaKind { IMAGE, VIDEO, AUDIO }
enum class MediaFilter { ALL, VIDEO, AUDIO, IMAGE }
fun filterMedia(items: List<SavedMedia>, filter: MediaFilter, query: String = "") = items.filter {
    (when (filter) {
        MediaFilter.ALL -> true
        MediaFilter.VIDEO -> it.kind == SavedMediaKind.VIDEO
        MediaFilter.AUDIO -> it.kind == SavedMediaKind.AUDIO
        MediaFilter.IMAGE -> it.kind == SavedMediaKind.IMAGE
    }) &&
        SearchText.matches(it.searchKey, query)
}
fun mediaDate(date: LocalDate, language: String): String = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag(NezzelFormat.numericTag(language))))

/** Only published media in Nezzel's output directory. No broad storage permission or filesystem paths. */
class SavedMediaRepository(private val resolver: ContentResolver, private val ownerPackageName: String,
    private val destinations: SaveDestinations? = null) {
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Uri>()

    fun query(): List<SavedMedia> = (queryMediaStore() + destinations?.savedFiles().orEmpty()).sortedByDescending { it.addedSeconds }
    private fun queryMediaStore(cancellation: CancellationSignal? = null): List<SavedMedia> {
        val columns = listOf("_id", "_display_name", "title", "mime_type", "_size", "date_added", "height", "width", "duration", MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.VOLUME_NAME) +
            if (Build.VERSION.SDK_INT >= 30) listOf("bitrate") else emptyList()
        val cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns.toTypedArray(),
            "owner_package_name = ? AND (relative_path = ? OR relative_path = ?) AND is_pending = ? AND (mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ?)",
            arrayOf(ownerPackageName, "Download/Nezzel/", "Download/Nezzel", "0", "video/mp4", "video/x-matroska", "video/webm", "video/quicktime", "audio/mp4", "audio/x-m4a", "audio/mpeg", "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif"), "date_added DESC, _id DESC", cancellation)
            ?: return emptyList()
        return cursor.use { c ->
            fun string(name: String): String? = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
            fun number(name: String): Long? = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getLong)
            fun dimension(name: String): Int? = number(name)?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
            buildList { while(c.moveToNext()) {
                val mime = string("mime_type") ?: continue
                val filename = string("_display_name") ?: continue
                // The confirmed filename survives provider scans that replace TITLE with an extractor id.
                val title = string("title")?.takeUnless { it.isBlank() || it.trim().all(Char::isDigit) }
                    ?: filename.substringBeforeLast('.')
                add(SavedMedia(ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, number("_id") ?: continue),
                    filename, title, mime,
                    number("_size")?.takeIf { it >= 0 }, number("date_added") ?: 0,
                    dimension("height"), number("bitrate")?.takeIf { it > 0 }?.div(1000)?.toInt(),
                    number("duration")?.takeIf { it > 0 }, width = dimension("width"), relativePath = string(MediaStore.MediaColumns.RELATIVE_PATH),
                    volumeName = string(MediaStore.MediaColumns.VOLUME_NAME)))
            } }
        }
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(ioDispatcher: CoroutineDispatcher = Dispatchers.IO) = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        resolver.registerContentObserver(MediaStore.Downloads.EXTERNAL_CONTENT_URI, true, observer)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        destinations?.prefs?.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose {
            resolver.unregisterContentObserver(observer)
            destinations?.prefs?.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.conflate().transformLatest {
        try {
            val media = cancellableQuery(query = ::queryMediaStore)
            val files = destinations?.recordedFiles().orEmpty().associateBy { it.uri }.toMutableMap()
            fun rows() = (media + files.values).sortedByDescending { it.addedSeconds }
            emit(Result.success(rows()))
            for (uri in destinations?.publishedUris().orEmpty()) {
                if (!inFlight.add(uri)) continue
                val file = cancellableQuery(onFinished = { inFlight.remove(uri) }) { signal -> destinations?.savedFile(uri, signal) }
                val previous = files[uri]
                if (file == null) files.remove(uri) else files[uri] = file
                if (file != previous) emit(Result.success(rows()))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cancelled: OperationCanceledException) { throw cancelled }
        catch (_: Exception) { emit(Result.failure<List<SavedMedia>>(IllegalStateException())) }
    }.flowOn(ioDispatcher)

    /** A provider may ignore cancellation: abandon its result without joining its blocking call. */
    private suspend fun <T> cancellableQuery(onFinished: () -> Unit = {}, query: (CancellationSignal) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            Dispatchers.IO.dispatch(EmptyCoroutineContext) {
                try {
                    if (continuation.isActive) continuation.resume(query(signal))
                } catch (failure: Exception) { continuation.resumeWithException(failure) }
                finally { onFinished() }
            }
        }

    /** Own files delete directly; Android may require consent for a recovered/foreign URI. */
    fun delete(uri: Uri): Boolean {
        if (uri.scheme == "content" && DocumentsContract.isTreeUri(uri) && uri.query == null && uri.fragment == null) {
            val deleted = DocumentsContract.deleteDocument(resolver, uri)
            if (deleted) {
                destinations?.forget(uri)
            }
            return deleted
        }
        requireMediaUri(uri)
        return resolver.delete(uri, null, null) == 1
    }
    @androidx.annotation.RequiresApi(30)
    fun deleteRequest(item: SavedMedia): android.content.IntentSender {
        return deleteRequest(listOf(item.uri))
    }
    @androidx.annotation.RequiresApi(30)
    fun deleteRequest(uris: List<Uri>): android.content.IntentSender {
        require(uris.isNotEmpty())
        uris.forEach(::requireMediaUri)
        check(Build.VERSION.SDK_INT >= 30)
        // Consent accepts typed media item URIs, not the Downloads aliases used by the library.
        val mediaUris = uris.distinct().map { uri ->
            val volume = MediaStore.getVolumeName(uri)
            val collection = when (resolver.getType(uri)?.substringBefore('/')) {
                "video" -> MediaStore.Video.Media.getContentUri(volume)
                "audio" -> MediaStore.Audio.Media.getContentUri(volume)
                "image" -> MediaStore.Images.Media.getContentUri(volume)
                else -> throw IllegalArgumentException("Unavailable media type")
            }
            ContentUris.withAppendedId(collection, ContentUris.parseId(uri))
        }
        return MediaStore.createDeleteRequest(resolver, mediaUris).intentSender
    }
    private fun requireMediaUri(uri: Uri) {
        require(uri.scheme == "content" && uri.authority == "media" && uri.pathSegments.size == 3 &&
            uri.pathSegments[1] == "downloads" && uri.lastPathSegment?.toLongOrNull()?.let { it > 0 } == true &&
            uri.query == null && uri.fragment == null)
    }
}

object MediaActions {
    fun open(item: SavedMedia) = Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("", item.uri) }
    fun share(item: SavedMedia): Intent = Intent.createChooser(Intent(Intent.ACTION_SEND).setType(item.mimeType)
        .putExtra(Intent.EXTRA_STREAM, item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri("", item.uri) }, null)
    fun share(items: List<SavedMedia>): Intent {
        require(items.isNotEmpty())
        if (items.size == 1) return share(items.single())
        val types = items.map { it.mimeType }.distinct()
        val topLevels = types.map { it.substringBefore('/') }.distinct()
        val mime = when {
            types.size == 1 -> types.single()
            topLevels.size == 1 -> "${topLevels.single()}/*"
            else -> "*/*"
        }
        val uris = ArrayList(items.map { it.uri })
        val clip = ClipData.newRawUri("", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime)
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = clip }, null)
    }
}
