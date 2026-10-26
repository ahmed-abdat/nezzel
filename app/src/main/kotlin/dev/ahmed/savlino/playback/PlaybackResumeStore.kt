package dev.ahmed.savlino.playback

import dev.ahmed.savlino.viewer.ViewerItem
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One instance per application, one ordered IO critical section for load, LRU updates and atomic writes.
 * A single-thread IO queue (not a lock taken on the caller's thread) keeps calls in order and never
 * needs the main thread to finish a write.
 */
class PlaybackResumeStore(private val file: File, private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry(val position: Long, val duration: Long, val used: Long)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private var loaded = false
    private val entries = linkedMapOf<String, Entry>()

    suspend fun position(item: ViewerItem, size: Long?, duration: Long): Long {
        val key = key(item, size) ?: return 0
        return withContext(io) {
            load()
            val entry = entries[key] ?: return@withContext 0L
            val end = duration.takeIf { it > 0 } ?: entry.duration
            if (entry.position <= 0 || finished(entry.position, end)) { entries.remove(key); write(); return@withContext 0L }
            entries.remove(key)
            entries[key] = entry.copy(used = now())
            write()
            entry.position
        }
    }
    suspend fun save(item: ViewerItem, size: Long?, position: Long, duration: Long) {
        val key = key(item, size) ?: return
        withContext(io) {
            load()
            if (position <= 0 || finished(position, duration)) entries.remove(key)
            else { entries.remove(key); entries[key] = Entry(position, duration.coerceAtLeast(0), now()) }
            trim(); write()
        }
    }
    suspend fun clear(item: ViewerItem, size: Long?) {
        val key = key(item, size) ?: return
        withContext(io) { load(); entries.remove(key); write() }
    }
    private fun key(item: ViewerItem, size: Long?): String? {
        if (item.resumePolicy != ResumePolicy.SAVED || size == null || size < 0) return null
        return MessageDigest.getInstance("SHA-256").digest((item.uri.toString() + "\u0000" + size).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun load() {
        if (loaded) return
        loaded = true
        try {
            if (!file.isFile || file.length() > 128 * 1024) return
            val json = JSONObject(file.readText(Charsets.UTF_8))
            for (key in json.keys()) {
                if (!key.matches(Regex("[a-f0-9]{64}"))) continue
                val value = json.getJSONObject(key)
                val position = value.getLong("p"); val duration = value.getLong("d"); val used = value.getLong("u")
                if (position > 0 && duration >= 0 && used >= 0) entries[key] = Entry(position, duration, used)
            }
            trim()
        } catch (_: Exception) { entries.clear() }
    }
    private fun trim() {
        while (entries.size > 200) entries.minByOrNull { it.value.used }?.let { entries.remove(it.key) }
    }
    private fun write() {
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            val json = JSONObject()
            entries.forEach { (key, value) -> json.put(key, JSONObject().put("p", value.position).put("d", value.duration).put("u", value.used)) }
            FileOutputStream(temp).use { stream -> stream.write(json.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
            if (!temp.renameTo(file)) temp.delete()
        } catch (_: Exception) { runCatching { temp.delete() } }
    }
    companion object {
        fun finished(position: Long, duration: Long): Boolean = duration > 0 && position >= duration - minOf(10_000, duration / 20)
    }
}
