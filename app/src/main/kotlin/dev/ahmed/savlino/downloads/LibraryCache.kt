package dev.ahmed.savlino.downloads

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Last listed library rows, shown only while the fresh query runs at cold start; the query always wins.
 * Lives in noBackupFilesDir, so uninstall removes it and backups never carry it.
 */
class LibraryCache(private val file: File) {
    /** Null when there is no cache or it cannot be read; never throws. */
    fun read(): List<SavedMedia>? = try {
        if (!file.isFile) null
        else JSONArray(file.readText()).let { array ->
            if (array.length() > MAX_ROWS) null else List(array.length()) { row(array.getJSONObject(it)) }
        }
    } catch (_: Exception) { null }

    fun write(rows: List<SavedMedia>) {
        try {
            val array = JSONArray()
            rows.asSequence().take(MAX_ROWS).forEach { array.put(json(it)) }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(array.toString())
            if (!temp.renameTo(file)) { temp.delete(); clear() }
        } catch (_: Exception) { clear() }
    }

    fun clear() { file.delete() }

    private fun json(item: SavedMedia) = JSONObject()
        .put("uri", item.uri.toString()).put("filename", item.filename).put("title", item.title).put("mime", item.mimeType)
        .put("added", item.addedSeconds)
        .apply {
            item.size?.let { put("size", it) }
            item.height?.let { put("height", it) }
            item.width?.let { put("width", it) }
            item.bitrateKbps?.let { put("bitrate", it) }
            item.durationMillis?.let { put("duration", it) }
            item.relativePath?.let { put("path", it) }
            item.volumeName?.let { put("volume", it) }
        }

    private fun row(json: JSONObject): SavedMedia {
        fun long(name: String) = if (json.has(name)) json.getLong(name) else null
        fun int(name: String) = if (json.has(name)) json.getInt(name) else null
        fun string(name: String) = if (json.has(name)) json.getString(name) else null
        return SavedMedia(Uri.parse(json.getString("uri")), json.getString("filename"), json.getString("title"),
            json.getString("mime"), long("size"), json.getLong("added"), int("height"), int("bitrate"), long("duration"),
            width = int("width"), relativePath = string("path"), volumeName = string("volume"))
    }

    companion object {
        const val MAX_ROWS = 500
        const val FILE_NAME = "library-cache.json"
    }
}
