package dev.ahmed.savlino.downloads

import dev.ahmed.savlino.statuses.StatusSaver
import dev.ahmed.savlino.storage.SaveDestinations
import dev.ahmed.savlino.storage.OldDownloads
import dev.ahmed.savlino.storage.FoundFile
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
import java.text.Collator
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
    val postPosition: SavedPostPosition? = null,
    val postAddedSeconds: Long? = null,
) {
    /** File labels follow DISPLAY_NAME; provider TITLE remains available to search. */
    val displayName: String get() = filename.removeSuffix(savedExtension(filename))
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
    val date: LocalDate get() = Instant.ofEpochSecond(postAddedSeconds ?: addedSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
}
/** Ordinal among saved siblings, not a claim about unsaved attachments on the source post. */
data class SavedPostPosition(val index: Int, val total: Int)

/** Display-only groups with at most 120 seconds between siblings; actions still address each original URI. */
fun groupDownloads(items: List<SavedMedia>): List<SavedMedia> {
    data class Key(val stem: String, val collision: String, val volume: String?, val width: Int)
    data class Candidate(val item: SavedMedia, val position: Int)
    val suffix = Regex("^(.+)_([0-9]{2,6})( \\([0-9]+\\))?$")
    val candidates = linkedMapOf<Key, MutableList<Candidate>>()
    val blocks = mutableListOf<List<SavedMedia>>()
    for (item in items) {
        val match = suffix.matchEntire(item.filename.substringBeforeLast('.', item.filename))
        val position = match?.groupValues?.get(2)?.toIntOrNull()?.takeIf { it > 0 }
        if (item.relativePath?.trimEnd('/') != "Download/Nezzel" || match == null || position == null ||
            item.kind !in setOf(SavedMediaKind.IMAGE, SavedMediaKind.VIDEO)) {
            blocks += listOf(item.copy(postPosition = null, postAddedSeconds = null)); continue
        }
        val key = Key(match.groupValues[1], match.groupValues[3], item.volumeName, match.groupValues[2].length)
        candidates.getOrPut(key) { mutableListOf() } += Candidate(item, position)
    }
    for (siblings in candidates.values) {
        val ordered = siblings.sortedBy { it.item.addedSeconds }
        var start = 0
        while (start < ordered.size) {
            var end = start + 1
            while (end < ordered.size && ordered[end].item.addedSeconds - ordered[end - 1].item.addedSeconds <= 120) end++
            val batch = ordered.subList(start, end)
            if (batch.size < 2 || batch.map { it.position }.distinct().size != batch.size)
                blocks.addAll(batch.map { listOf(it.item.copy(postPosition = null, postAddedSeconds = null)) })
            else blocks += batch.sortedBy { it.position }.mapIndexed { index, entry ->
                entry.item.copy(postPosition = SavedPostPosition(index + 1, batch.size), postAddedSeconds = batch.maxOf { it.item.addedSeconds })
            }
            start = end
        }
    }
    return blocks.sortedByDescending { block -> block.maxOf { it.addedSeconds } }.flatten()
}

/** Home lists a saved post once, by its first file; Downloads still shows every file of it. */
fun homeEntries(items: List<SavedMedia>): List<SavedMedia> =
    groupDownloads(items).filter { it.postPosition == null || it.postPosition.index == 1 }

enum class SavedMediaKind { IMAGE, VIDEO, AUDIO }
/** STATUSES stays last: its chip ends the row. */
enum class MediaFilter { ALL, VIDEO, AUDIO, IMAGE, STATUSES }

/** Saved from the Statuses tab: StatusSaver always publishes into its own MediaStore folder, which marks the file for good. */
val SavedMedia.fromStatuses: Boolean get() = relativePath?.trimEnd('/') == StatusSaver.RELATIVE_PATH.trimEnd('/')
enum class SavedSort { NEWEST, LARGEST, NAME }

/** A grouped post is an indivisible display block, including when only one sibling matches. */
private fun savedBlocks(items: List<SavedMedia>): List<List<SavedMedia>> {
    val blocks = mutableListOf<MutableList<SavedMedia>>()
    for (item in items) {
        if (item.postPosition == null || item.postPosition.index == 1 || blocks.isEmpty()) blocks += mutableListOf(item)
        else blocks.last() += item
    }
    return blocks
}
private fun MediaFilter.matches(item: SavedMedia) = when (this) {
    MediaFilter.ALL -> true
    MediaFilter.VIDEO -> item.kind == SavedMediaKind.VIDEO
    MediaFilter.AUDIO -> item.kind == SavedMediaKind.AUDIO
    MediaFilter.IMAGE -> item.kind == SavedMediaKind.IMAGE
    MediaFilter.STATUSES -> item.fromStatuses
}
fun filterMedia(items: List<SavedMedia>, filter: MediaFilter, query: String = ""): List<SavedMedia> =
    savedBlocks(items).filter { block -> block.any { filter.matches(it) } && block.any { SearchText.matches(it.searchKey, query) } }.flatten()

/** Types come from the complete library, so search and selection cannot make their own chips disappear. */
fun availableMediaFilters(items: List<SavedMedia>): List<MediaFilter> {
    val kinds = MediaFilter.entries.filter { it != MediaFilter.ALL && it != MediaFilter.STATUSES && items.any(it::matches) }
    val statuses = items.any { it.fromStatuses }
    return if (kinds.size > 1 || statuses) listOf(MediaFilter.ALL) + kinds + listOfNotNull(MediaFilter.STATUSES.takeIf { statuses }) else emptyList()
}

fun savedLibraryItems(items: List<SavedMedia>, filter: MediaFilter = MediaFilter.ALL, query: String = "",
    sort: SavedSort = SavedSort.NEWEST, locale: Locale = Locale.getDefault()): List<SavedMedia> {
    val blocks = savedBlocks(filterMedia(groupDownloads(items), filter, query))
    val newest = compareByDescending<List<SavedMedia>> { block -> block.maxOf { it.addedSeconds } }
    fun bytes(block: List<SavedMedia>): Long? {
        if (block.any { it.size == null }) return null
        // Saturate instead of wrapping if a provider reports implausibly large sizes.
        return block.fold(0L) { total, item -> total + minOf(item.size!!.coerceAtLeast(0), Long.MAX_VALUE - total) }
    }
    val comparator = when (sort) {
        SavedSort.NEWEST -> newest
        SavedSort.LARGEST -> compareByDescending<List<SavedMedia>> { bytes(it) }.then(newest)
        SavedSort.NAME -> {
            val collator = Collator.getInstance(locale).apply { decomposition = Collator.CANONICAL_DECOMPOSITION }
            Comparator<List<SavedMedia>> { a, b -> collator.compare(a.first().displayName, b.first().displayName) }.then(newest)
        }
    }
    return blocks.sortedWith(comparator).flatten()
}

/** Only the stem is editable. Preserve the exact extension, including its original case. */
fun savedExtension(filename: String): String = filename.lastIndexOf('.').takeIf { it > 0 }?.let(filename::substring).orEmpty()
fun validSavedName(name: String): Boolean = name.trim().let { it.isNotEmpty() && !it.startsWith('.') && it.length <= 200 } &&
    name.none { it == '/' || it == '\\' || Character.isISOControl(it) }

fun mediaDate(date: LocalDate, language: String): String = date.format(DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag(NezzelFormat.numericTag(language))))

/** Only published media in Nezzel's output directory. No broad storage permission or filesystem paths. */
class SavedMediaRepository(private val resolver: ContentResolver, private val ownerPackageName: String,
    private val destinations: SaveDestinations? = null, private val oldDownloads: OldDownloads? = null) {
    private val renameDenied = java.util.concurrent.ConcurrentHashMap.newKeySet<Uri>()
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<Uri>()

    fun query(): List<SavedMedia> {
        val shown = queryMediaStore() + destinations?.savedFiles().orEmpty()
        val found = oldDownloads?.let { OldDownloads.dedupe(it.list(), shown).map(FoundFile::media) }.orEmpty()
        return (shown + found).sortedByDescending { it.addedSeconds }
    }
    private fun queryMediaStore(cancellation: CancellationSignal? = null): List<SavedMedia> {
        val columns = listOf("_id", "_display_name", "title", "mime_type", "_size", "date_added", "height", "width", "duration", MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.VOLUME_NAME) +
            if (Build.VERSION.SDK_INT >= 30) listOf("bitrate") else emptyList()
        val cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, columns.toTypedArray(),
            "owner_package_name = ? AND (relative_path = ? OR relative_path = ? OR relative_path = ? OR relative_path = ?) AND is_pending = ? AND (mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ? OR mime_type = ?)",
            arrayOf(ownerPackageName, "Download/Nezzel/", "Download/Nezzel", StatusSaver.RELATIVE_PATH, StatusSaver.RELATIVE_PATH.trimEnd('/'), "0", "video/mp4", "video/x-matroska", "video/webm", "video/quicktime", "audio/mp4", "audio/x-m4a", "audio/mpeg", "image/jpeg", "image/png", "image/webp", "image/gif", "image/avif"), "date_added DESC, _id DESC", cancellation)
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
        oldDownloads?.prefs?.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose {
            resolver.unregisterContentObserver(observer)
            destinations?.prefs?.unregisterOnSharedPreferenceChangeListener(listener)
            oldDownloads?.prefs?.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.conflate().transformLatest {
        try {
            val media = cancellableQuery(query = ::queryMediaStore)
            val files = destinations?.recordedFiles().orEmpty().associateBy { it.uri }.toMutableMap()
            val found = linkedMapOf<Uri, SavedMedia>()
            fun rows() = (media + files.values + found.values).sortedByDescending { it.addedSeconds }
            emit(Result.success(rows()))
            // Old downloads: one listing, then rows at once with cached dimensions; probes fill in below.
            val listed = oldDownloads?.let { old -> cancellableQuery { signal -> old.list(signal) } }
                ?.let { OldDownloads.dedupe(it, media + files.values) }.orEmpty()
            fun FoundFile.withBounds(bounds: SaveDestinations.FileBounds) =
                this.media.copy(width = bounds.width, height = bounds.height, durationMillis = bounds.durationMillis)
            listed.forEach { file ->
                found[file.media.uri] = destinations?.cachedBounds(file.media.uri, file.media.size, file.modified, file.media.mimeType)
                    ?.let { file.withBounds(it) } ?: file.media
            }
            if (listed.isNotEmpty()) emit(Result.success(rows()))
            for (uri in destinations?.publishedUris().orEmpty()) {
                if (!inFlight.add(uri)) continue
                val file = cancellableQuery(onFinished = { inFlight.remove(uri) }) { signal -> destinations?.savedFile(uri, signal) }
                val previous = files[uri]
                if (file == null) files.remove(uri) else files[uri] = file
                if (file != previous) emit(Result.success(rows()))
            }
            val unprobed = listed.filter { file ->
                file.media.kind != SavedMediaKind.AUDIO &&
                    destinations?.cachedBounds(file.media.uri, file.media.size, file.modified, file.media.mimeType) == null
            }
            if (destinations != null) unprobed.chunked(FOUND_PROBE_BATCH).forEach { batch ->
                batch.forEach { file ->
                    val bounds = cancellableQuery { destinations.fileBounds(file.media.uri, file.media.size, file.modified, file.media.mimeType) }
                    found[file.media.uri] = file.withBounds(bounds)
                }
                emit(Result.success(rows()))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (cancelled: OperationCanceledException) { throw cancelled }
        catch (_: Exception) { emit(Result.failure<List<SavedMedia>>(IllegalStateException())) }
    }.flowOn(ioDispatcher)

    private companion object { const val FOUND_PROBE_BATCH = 24 }

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

    /** Recovered rows and folder documents never gain a rename or a storage-permission prompt. */
    fun canRename(uri: Uri): Boolean = try { ownedFilename(uri) != null } catch (_: Exception) { false }

    private fun ownedFilename(uri: Uri): String? {
        if (uri in renameDenied) return null
        requireMediaUri(uri)
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.DISPLAY_NAME),
            null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val owner = cursor.getColumnIndex(MediaStore.MediaColumns.OWNER_PACKAGE_NAME)
            val name = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (owner < 0 || name < 0 || cursor.getString(owner) != ownerPackageName || cursor.isNull(name)) null
            else cursor.getString(name)
        }
    }

    /** No TITLE/job mutation: MediaStore owns collision handling and returns the actual final filename. */
    fun rename(uri: Uri, name: String): String? {
        require(validSavedName(name))
        return try {
            val before = ownedFilename(uri) ?: return null
            val requestedName = name.trim() + savedExtension(before)
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName) }
            if (resolver.update(uri, values, "owner_package_name = ?", arrayOf(ownerPackageName)) != 1) return null
            ownedFilename(uri) ?: requestedName
        } catch (_: SecurityException) { renameDenied += uri; null }
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
