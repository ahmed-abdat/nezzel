package dev.ahmed.savlino.update

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import org.json.JSONObject
import org.json.JSONTokener

/** Untrusted release data; never use its generated toString in diagnostics. */
class Release(val version: String, val code: Long, val name: String, val size: Long,
              val url: String, val digest: String, internal val english: List<String>, internal val arabic: List<String>,
              internal val french: List<String> = emptyList()) {
    fun notes(language: String): List<String> = when {
        language == "ar" && arabic.isNotEmpty() -> arabic
        language == "fr" && french.isNotEmpty() -> french
        else -> english
    }
    companion object {
        const val API = "https://api.github.com/repos/ahmed-abdat/nezzel/releases/latest"
        const val MANIFEST = "https://github.com/ahmed-abdat/nezzel/releases/latest/download/nezzel-update.json"
        const val BODY_CAP = 256 * 1024
        const val MANIFEST_CAP = 64 * 1024
        private val semver = Regex("v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?")
        private val marker = Regex("<!-- nezzel:versionCode=([1-9][0-9]*) -->")
        private fun validVersion(version: String): Boolean {
            if (!semver.matches("v$version")) return false
            val prerelease = version.substringBefore('+').substringAfter('-', "")
            return prerelease.split('.').none { it.length > 1 && it.startsWith('0') && it.all(Char::isDigit) }
        }
        private fun validName(name: String) = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.apk").matches(name) && ".." !in name
        private fun positiveInteger(value: Any?): Long? =
            (if (value is Int || value is Long) (value as Number).toLong() else null)?.takeIf { it > 0 }

        fun parseManifest(bytes: ByteArray): Release? {
            if (bytes.size > MANIFEST_CAP) return null
            return try {
                val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                if (!boundedDepth(text)) return null
                // JSONObject accepts non-JSON syntax; validate the manifest's strict JSON grammar first.
                JsonReader(StringReader(text)).use { reader ->
                    if (reader.peek() != JsonToken.BEGIN_OBJECT) return null
                    reader.skipValue()
                    if (reader.peek() != JsonToken.END_DOCUMENT) return null
                }
                val json = JSONObject(text)
                if (positiveInteger(json.get("schema")) != 1L) return null
                val version = (json.get("version") as? String)?.takeIf(::validVersion) ?: return null
                val code = positiveInteger(json.get("versionCode")) ?: return null
                val apk = json.getJSONObject("apk")
                val name = (apk.get("name") as? String)?.takeIf(::validName) ?: return null
                val size = positiveInteger(apk.get("size")) ?: return null
                if (size > (Long.MAX_VALUE - 64L * 1024 * 1024) / 2) return null
                val digest = apk.get("sha256") as? String ?: return null
                if (!Regex("[0-9a-fA-F]{64}").matches(digest)) return null
                val notes = json.getJSONObject("notes")
                fun language(key: String): List<String> {
                    if (!notes.has(key)) return emptyList()
                    val array = notes.getJSONArray(key)
                    val lines = (0 until array.length()).map { array.get(it) as? String ?: throw IllegalArgumentException() }
                    return cleanNotes(lines.asSequence())
                }
                Release(version, code, name, size,
                    "https://github.com/ahmed-abdat/nezzel/releases/download/v$version/$name", digest, language("en"), language("ar"), language("fr"))
            } catch (_: Exception) { null }
        }
        fun parse(bytes: ByteArray): Release? {
            if (bytes.size > BODY_CAP) return null
            return try {
                val text = bytes.toString(Charsets.UTF_8)
                if (!boundedDepth(text)) return null
                val tokener = JSONTokener(text)
                val json = tokener.nextValue() as? JSONObject ?: return null
                if (tokener.nextClean() != '\u0000') return null
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
                val tag = json.get("tag_name") as? String ?: return null
                if (!tag.startsWith('v') || !validVersion(tag.drop(1))) return null
                val body = json.get("body") as? String ?: return null
                val code = marker.findAll(body).singleOrNull()?.groupValues?.get(1)?.toLongOrNull() ?: return null
                if (Regex("nezzel:versionCode=").findAll(body).count() != 1) return null
                val assets = json.getJSONArray("assets")
                val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
                    (it.opt("name") as? String)?.endsWith(".apk") == true
                }
                val apk = apks.singleOrNull() ?: return null
                val name = apk.getString("name")
                if (!validName(name)) return null
                val size = apk.get("size").let { if (it is Int || it is Long) (it as Number).toLong() else return null }
                if (size <= 0 || size > (Long.MAX_VALUE - 64L * 1024 * 1024) / 2) return null
                val url = apk.get("browser_download_url") as? String ?: return null
                if (!UpdatePolicy.allowed(url)) return null
                val digest = apk.get("digest") as? String ?: return null
                if (!Regex("sha256:[0-9a-fA-F]{64}").matches(digest)) return null
                Release(tag.drop(1), code, name, size, url, digest.substringAfter(':'), notes(body, "en"), notes(body, "ar"), notes(body, "fr"))
            } catch (_: Exception) { null }
        }
        private fun boundedDepth(text: String): Boolean {
            var depth = 0; var quoted = false; var escaped = false
            for (char in text) {
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; if (depth > 32) return false }
                    '}', ']' -> { depth--; if (depth < 0) return false }
                }
            }
            return depth == 0 && !quoted
        }
        private fun notes(body: String, language: String): List<String> {
            val block = body.substringAfter("<!-- nezzel:notes:$language -->", "").substringBefore("<!-- /nezzel:notes -->", "")
            return cleanNotes(block.lineSequence().filter { it.startsWith("- ") }.map { it.drop(2) })
        }
        private fun cleanNotes(lines: Sequence<String>): List<String> = lines.map {
                it.replace("**", "").replace("`", "").replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                    .replace(Regex("<[^>]*>"), "").filterNot { char -> char.isISOControl() || char in '\u202a'..'\u202e' || char in '\u2066'..'\u2069' }
                    .trim().take(1000)
            }.filter { it.isNotBlank() }.take(8).toList()
    }
}
