package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

/**
 * A small stream to play before saving: one file with sound, or a video track plus a separate audio track.
 * Only after the user taps Play; never saved, never logged.
 */
class PreviewStream(val url: String, val audioUrl: String?, val headers: Map<String, String>, val hasVideo: Boolean) {
    override fun toString() = "PreviewStream(redacted)"

    companion object {
        private const val TARGET_HEIGHT = 360
        private const val MAX_HEIGHT = 480
        private val mapper = ObjectMapper()

        /** Picks from yt-dlp-shaped JSON. Plain HTTPS files only: no HLS/DASH manifests, no DRM. */
        fun pick(json: String): PreviewStream? = try { pick(mapper.readTree(json)) } catch (_: Exception) { null }

        private fun pick(root: JsonNode): PreviewStream? {
            if (root.path("_type").asText("video") != "video") return null
            val formats = root.path("formats").filter(::playable)
            val muxed = formats.filter { video(it) && audio(it) }
            closest(muxed)?.let { return stream(it, null) }
            val videos = formats.filter { video(it) && !audio(it) && it.path("ext").asText() == "mp4" }
            val audioOnly = formats.none(::video)
            val audios = formats.filter { audio(it) && !video(it) && (audioOnly || it.path("ext").asText() in setOf("m4a", "mp4")) }
            val audio = audios.minByOrNull { it.path("abr").asDouble(Double.MAX_VALUE).let { abr -> if (abr < 48) 1e6 + abr else abr } }
            val video = closest(videos)
            if (video != null && audio != null) return stream(video, audio)
            if (video == null && audioOnly && audio != null) return stream(audio, null, hasVideo = false)
            // A single direct file (TikTok-style roots carry the chosen file itself).
            if (formats.isEmpty() && playable(root) && root.path("vcodec").asText() != "none") return stream(root, null)
            return null
        }

        /** Nearest to 360p within 480p, H.264 first; heightless muxed files count as one candidate. */
        private fun closest(list: List<JsonNode>): JsonNode? {
            val sized = list.filter { it.path("height").asInt(0) in 1..MAX_HEIGHT }
            val pool = sized.ifEmpty { list.filter { !it.hasNonNull("height") } }
            return pool.minWithOrNull(compareBy<JsonNode> { !it.path("vcodec").asText().startsWith("avc") }
                .thenBy { if (it.hasNonNull("height")) kotlin.math.abs(it.path("height").asInt() - TARGET_HEIGHT) else 0 })
        }

        private fun video(node: JsonNode) = node.path("vcodec").asText("").let { it.isNotEmpty() && it != "none" } ||
            (!node.has("vcodec") && node.path("height").asInt(0) > 0)
        private fun audio(node: JsonNode) = node.path("acodec").asText("").let { it.isNotEmpty() && it != "none" }

        private fun playable(node: JsonNode): Boolean {
            val url = node.path("url").asText("")
            if (node.path("has_drm").asBoolean(false)) return false
            if (node.path("protocol").asText("https") !in setOf("https", "http")) return false
            return try { URI(url).scheme == "https" } catch (_: Exception) { false }
        }

        private fun stream(main: JsonNode, audio: JsonNode?, hasVideo: Boolean = true) =
            PreviewStream(main.path("url").asText(), audio?.path("url")?.asText(), headers(main), hasVideo)

        private fun headers(node: JsonNode): Map<String, String> = buildMap {
            node.path("http_headers").fields().forEach { (name, value) ->
                // Cookies stay with yt-dlp; a preview never replays a session.
                if (value.isTextual && !name.equals("Cookie", ignoreCase = true)) put(name, value.asText())
            }
        }
    }
}
