package dev.ahmed.savlino.engine.youtube

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ahmed.savlino.domain.Inspection
import dev.ahmed.savlino.engine.YtdlpCommand
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.*

internal object NpeInfoJson {
    private val json = ObjectMapper()

    fun build(info: StreamInfo): String? {
        if (info.streamType != StreamType.VIDEO_STREAM || info.contentAvailability == ContentAvailability.UPCOMING ||
            info.errors.any(::streamError)) return null
        val videos = (info.videoOnlyStreams + info.videoStreams).mapNotNull { stream ->
            base(stream, stream.itag.toString())?.apply {
                val codecs = stream.codec?.split(',')?.map { it.trim() }.orEmpty()
                codec("vcodec", codecs.firstOrNull())
                if (stream.isVideoOnly) put("acodec", "none") else codec("acodec", codecs.getOrNull(1))
                if (stream.width > 0) put("width", stream.width)
                if (stream.height > 0) put("height", stream.height)
                if (stream.fps > 0) put("fps", stream.fps)
                val bitrate = stream.bitrate.takeIf { it > 0 }?.div(1000.0)
                bitrate?.let { put("tbr", it) }
                size(stream, bitrate, info.duration)
            }?.let { stream to it }
        }
        val audioStreams = info.audioStreams.filter { progressive(it) }
        val tracks = audioStreams.groupBy { it.itag }.mapValues { (_, streams) -> streams.map { it.audioTrackId }.toSet() }
        val audios = audioStreams.mapNotNull { stream ->
            val track = if (tracks.getValue(stream.itag).size > 1) {
                val id = stream.audioTrackId?.takeIf { it.isNotBlank() } ?: return null
                "-t" + id.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
            } else ""
            val drc = if (stream.itagItem?.isDrc == true) "-drc" else ""
            base(stream, "${stream.itag}$track$drc")?.apply {
                put("vcodec", "none"); codec("acodec", stream.codec)
                val bitrate = stream.bitrate.takeIf { it > 0 }?.div(1000.0)
                    ?: stream.averageBitrate.takeIf { it > 0 }?.toDouble()
                bitrate?.let { put("abr", it) }
                size(stream, bitrate, info.duration)
                val language = stream.audioLocale?.toLanguageTag()?.takeIf { it.isNotBlank() && it != "und" }
                    ?: stream.audioTrackId?.substringBefore('.')?.takeIf { it.isNotBlank() }
                language?.let { put("language", it) }
                val preference = when (stream.audioTrackType) {
                    AudioTrackType.DESCRIPTIVE -> -10
                    AudioTrackType.ORIGINAL -> 10
                    AudioTrackType.DUBBED, AudioTrackType.SECONDARY -> -1
                    // yt-dlp gives default audio 5; NPE has no default type. Unknown stays absent.
                    null -> null
                }
                preference?.let { put("language_preference", it) }
            }
        }
        if (videos.none { it.first.isVideoOnly } || audios.isEmpty()) return null
        val formats = videos.map { it.second } + audios
        val ids = formats.map { it.path("format_id").asText() }
        // Ambiguous or colliding track identifiers cannot safely survive exact-id download selection.
        if (ids.any { !YtdlpCommand.isExactId(it) } || ids.toSet().size != ids.size) return null
        val root = json.createObjectNode().apply {
            put("id", info.id); put("title", info.name); put("uploader", info.uploaderName)
            if (info.duration > 0) put("duration", info.duration)
            info.thumbnails.maxWithOrNull(compareBy<Image> {
                it.width.coerceAtLeast(0).toLong() * it.height.coerceAtLeast(0)
            }.thenBy { -it.estimatedResolutionLevel.ordinal })?.let { put("thumbnail", it.url) }
            put("extractor", "youtube"); put("extractor_key", "Youtube"); put("_type", "video")
            set<com.fasterxml.jackson.databind.JsonNode>("formats", json.valueToTree(formats))
        }
        return json.writeValueAsString(root)
    }

    fun accepts(json: String, inspection: Inspection): Boolean {
        if (inspection.formats.none { it.hasVideo }) return false
        val audio = inspection.formats.filter { !it.hasVideo && it.hasAudio }
        if (audio.isEmpty()) return false
        val formats = runCatching { this.json.readTree(json).path("formats") }.getOrNull() ?: return false
        val byId = formats.associateBy { it.path("format_id").asText() }
        return audio.any { row ->
            byId[row.id]?.let { node ->
                val preference = node.get("language_preference")
                preference == null || (preference.isNumber && preference.asDouble().isFinite() && preference.asDouble() >= 0)
            } == true
        }
    }

    // StreamInfo.extractStreams (v0.26.5) wraps stream getter failures in these fixed messages; optional fields
    // (description, related items, ...) also land in errors and must not force the slow path.
    private val streamErrors = setOf("Couldn't get audio streams", "Couldn't get video streams", "Couldn't get video only streams")
    private fun streamError(error: Throwable) = error !is ExtractionException || error.message in streamErrors

    private fun progressive(stream: Stream) = stream.isUrl && stream.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
        stream.content.toHttpUrlOrNull()?.isHttps == true

    private fun base(stream: Stream, id: String): ObjectNode? {
        if (!progressive(stream)) return null
        val ext = stream.format?.suffix?.takeIf { it.isNotBlank() } ?: return null
        return json.createObjectNode().apply {
            put("format_id", id); put("url", stream.content); put("protocol", "https"); put("ext", ext)
            put("has_drm", false)
            putObject("http_headers").put("User-Agent", userAgent(stream.content))
            putObject("downloader_options").put("http_chunk_size", 10_485_760)
        }
    }

    private fun ObjectNode.codec(key: String, value: String?) {
        value?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("unknown", "none") }?.let { put(key, it) }
    }

    private fun ObjectNode.size(stream: Stream, bitrate: Double?, duration: Long) {
        val itag = stream.itagItem
        val exact = itag?.contentLength?.takeIf { it > 0 }
        if (exact != null) { put("filesize", exact); return }
        val seconds = itag?.approxDurationMs?.takeIf { it > 0 }?.div(1000.0)
            ?: duration.takeIf { it > 0 }?.toDouble()
        // yt-dlp filesize_from_tbr: duration * kbps * (1000 / 8), truncated to bytes.
        if (bitrate != null && seconds != null) {
            val bytes = seconds * bitrate * 125
            if (bytes.isFinite() && bytes > 0 && bytes < Long.MAX_VALUE.toDouble()) put("filesize_approx", bytes.toLong())
        }
    }

    private fun userAgent(url: String) = when {
        YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
        YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
        YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
        else -> NPE_BROWSER_USER_AGENT
    }
}

// Extractor v0.26.5 exposes the client via URL helpers, but supplies no browser UA constant.
// Match NewPipe's DownloaderImpl for web/unknown clients rather than guessing a mobile client.
internal const val NPE_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
