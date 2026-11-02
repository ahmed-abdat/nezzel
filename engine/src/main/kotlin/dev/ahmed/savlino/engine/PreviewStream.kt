package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

/**
 * A small stream to play before saving: one file with sound, or a video track plus a separate audio track.
 * Only after the user taps Play; never saved, never logged.
 */
class PreviewStream(val url: String, val audioUrl: String?, val headers: Map<String, String>, val hasVideo: Boolean,
    val audioHeaders: Map<String, String> = emptyMap(), val hls: Boolean = false) {
    override fun toString() = "PreviewStream(redacted)"

    companion object {
        /**
         * The player's resolver: public addresses only (no LAN, loopback or link-local). OkHttp connects to exactly these
         * addresses, on the first request and on every redirect, so a later DNS answer cannot point elsewhere.
         */
        val publicDns = okhttp3.Dns { host ->
            java.net.InetAddress.getAllByName(host).toList().also { found ->
                if (found.isEmpty() || !found.all(dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy::allows))
                    throw java.net.UnknownHostException("not public")
            }
        }
        /** True for a public address only; the player checks every connected socket with it (IP-literal hosts skip DNS). */
        fun isPublic(address: java.net.InetAddress) = dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy.allows(address)

        /** Request headers a preview may replay; site auth/session headers stay with yt-dlp. */
        private val SAFE_HEADERS = setOf("user-agent", "referer", "origin", "accept", "accept-language")

        private const val TARGET_HEIGHT = 360
        private const val MAX_HEIGHT = 480
        private val mapper = ObjectMapper()

        /** Picks from yt-dlp-shaped JSON. HTTPS files first, then an HTTPS HLS playlist; never DASH manifests or DRM. */
        fun pick(json: String): PreviewStream? = try { pick(mapper.readTree(json)) } catch (_: Exception) { null }

        private fun pick(root: JsonNode): PreviewStream? {
            if (root.path("_type").asText("video") != "video") return null
            val formats = root.path("formats").filter(::playable)
            val muxed = formats.filter { (video(it) && audio(it)) || bare(it) }
            closest(muxed.filter { it.path("height").asInt(0) in 1..MAX_HEIGHT })?.let { return stream(it, null) }
            val videos = formats.filter { video(it) && !audio(it) && !bare(it) && it.path("ext").asText() == "mp4" }
            val audioOnly = formats.none { video(it) || bare(it) }
            val audios = formats.filter { audio(it) && !video(it) && (audioOnly || it.path("ext").asText() in setOf("m4a", "mp4")) }
            val audio = audios.minByOrNull { it.path("abr").asDouble(Double.MAX_VALUE).let { abr -> if (abr < 48) 1e6 + abr else abr } }
            val video = closest(videos)
            if (video != null && audio != null) return stream(video, audio)
            // Files with no height (Facebook's sd/hd pair): the smallest one, "sd" before "hd".
            muxed.filter { !it.hasNonNull("height") }.minWithOrNull(compareBy<JsonNode> { !it.path("format_id").asText().startsWith("sd") }
                .thenBy { it.path("filesize").asLong(it.path("tbr").asLong(Long.MAX_VALUE)) })?.let { return stream(it, null) }
            // Streaming playlists (Bluesky, Dailymotion, SoundCloud): the variant nearest 360p, or plain sound.
            val playlists = root.path("formats").filter(::hlsPlayable)
            val withSound = playlists.filter { (video(it) && audio(it)) || bare(it) }
            (closest(withSound) ?: withSound.minByOrNull { it.path("height").asInt(Int.MAX_VALUE) })?.let { return stream(it, null, hls = true) }
            // Only big files with sound (Pinterest): the smallest of them.
            muxed.filter { it.path("height").asInt(0) > MAX_HEIGHT }.minByOrNull { it.path("height").asInt() }?.let { return stream(it, null) }
            if (video == null && audioOnly && audio != null) return stream(audio, null, hasVideo = false)
            if (formats.none(::video) && playlists.none(::video)) playlists.filter { audio(it) }
                .minByOrNull { it.path("abr").asDouble(Double.MAX_VALUE) }?.let { return stream(it, null, hasVideo = false, hls = true) }
            // The site gave no sound at all (Instagram signed out): the saved file is silent too, so the preview may be.
            if (root.path("formats").none(::audio)) (video ?: videos.minByOrNull { it.path("height").asInt(Int.MAX_VALUE) })?.let { return stream(it, null) }
            // A single direct file (TikTok-style roots carry the chosen file itself).
            if (formats.isEmpty() && playable(root) && root.path("vcodec").asText() != "none") return stream(root, null)
            return null
        }

        /** Nearest to 360p within 480p, H.264 first; heightless files only when nothing has a height. */
        private fun closest(list: List<JsonNode>): JsonNode? {
            val sized = list.filter { it.path("height").asInt(0) in 1..MAX_HEIGHT }
            val pool = sized.ifEmpty { list.filter { !it.hasNonNull("height") } }
            return pool.minWithOrNull(compareBy<JsonNode> { !it.path("vcodec").asText().startsWith("avc") }
                .thenBy { if (it.hasNonNull("height")) kotlin.math.abs(it.path("height").asInt() - TARGET_HEIGHT) else 0 })
        }

        /** The codec yt-dlp named, or null when it left the field out or null ("none" means the track is absent). */
        private fun codec(node: JsonNode, field: String): String? = node.get(field)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotEmpty() }
        private fun video(node: JsonNode) = codec(node, "vcodec")?.let { it != "none" } ?: (node.path("height").asInt(0) > 0)
        /** A plain video file with no codec named at all (X, Twitch clips, Pinterest, Facebook); such files carry sound. */
        private fun bare(node: JsonNode) = codec(node, "vcodec") == null && codec(node, "acodec") == null &&
            node.path("ext").asText() in setOf("mp4", "webm", "mov")
        private fun audio(node: JsonNode) = codec(node, "acodec")?.let { it != "none" } ?: false

        private fun hlsPlayable(node: JsonNode): Boolean = !node.path("has_drm").asBoolean(false) &&
            node.path("protocol").asText() in setOf("m3u8_native", "m3u8") &&
            try { URI(node.path("url").asText("")).scheme == "https" } catch (_: Exception) { false }

        private fun playable(node: JsonNode): Boolean {
            val url = node.path("url").asText("")
            if (node.path("has_drm").asBoolean(false)) return false
            if (node.path("protocol").asText("https") !in setOf("https", "http")) return false
            return try { URI(url).scheme == "https" } catch (_: Exception) { false }
        }

        private fun stream(main: JsonNode, audio: JsonNode?, hasVideo: Boolean = true, hls: Boolean = false) =
            PreviewStream(main.path("url").asText(), audio?.path("url")?.asText(), headers(main), hasVideo, audio?.let(::headers).orEmpty(), hls)

        private fun headers(node: JsonNode): Map<String, String> = buildMap {
            node.path("http_headers").fields().forEach { (name, value) ->
                // Cookies and auth stay with yt-dlp; a preview never replays a session.
                if (value.isTextual && name.lowercase() in SAFE_HEADERS) put(name, value.asText())
            }
        }
    }
}
