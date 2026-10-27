package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.*
import org.jsoup.parser.Parser

internal class UnsupportedMetadataException(val emptyResult: Boolean = false) : Exception()

/** Pure JVM mapping. Missing codecs stay unknown; narrowly recover direct files and explicit HLS stream roles. */
object YtdlpMetadataMapper {
    private val json = ObjectMapper()
    private class Parsed(val root: JsonNode, val genericDirect: JsonNode?, val rows: List<Pair<Format, JsonNode>>)

    /** The returned streams before pairing or derived rows, by format id: what a confirmed row was built from. */
    internal fun streams(metadata: String): Map<String, Format> = parse(metadata).rows.associate { it.first.id to it.first }

    private fun parse(metadata: String): Parsed {
        // readTree(String) silently ignores trailing documents. Consume the entire stdout stream.
        val documents = json.factory.createParser(metadata).use { parser ->
            buildList<JsonNode> {
                while (true) add(json.readTree<JsonNode>(parser) ?: break)
            }
        }
        if (documents.size > 1) throw UnsupportedMetadataException()
        val root = documents.single()
        InspectionTiming.mark(InspectionPhase.JSON_PARSED)
        if (root.path("_type").asText() in listOf("playlist", "multi_video")) {
            val entries = root.path("entries")
            throw UnsupportedMetadataException(emptyResult = entries.isArray && entries.size() == 0)
        }
        require(root.isObject)
        val nodes = root.get("formats")?.takeIf { it.isArray }?.toList() ?: listOf(root)
        val mapped = nodes.mapNotNull { node -> format(node)?.let { it to node } }.distinctBy { it.first.id }
        // Generic direct links have no extractor-specific codec facts, even when yt-dlp writes "none".
        // Only one actual direct video file qualifies; never guess a stream from an arbitrary post/manifest.
        val genericDirect = nodes.singleOrNull()?.takeIf {
            (root.text("extractor")?.equals("generic", true) == true || root.text("extractor_key") == "Generic") &&
                !root.path("is_live").asBoolean(false) && isDirectFile(it, setOf("mp4", "mov", "webm"), allowLiteralId = true) &&
                it.codec("vcodec") == null && it.codec("acodec") == null
        }
        // Codec-less direct MP4s are fallback-only, except Facebook's named progressive sd/hd files.
        // X/LinkedIn must not gain unknown-codec duplicates beside playable mapped rows.
        val directMp4s = unreportedDirectMp4s(nodes, root.text("extractor_key"))
            .map { unreportedDirect(it) to it }
        fun playsWithSound(rows: List<Pair<Format, JsonNode>>) = rows.any { it.first.hasVideo && it.first.hasAudio } ||
            (rows.any { it.first.hasVideo } && rows.any { !it.first.hasVideo && it.first.hasAudio })
        val facebookProgressive = directMp4s.filter { root.text("extractor_key") == "Facebook" && it.first.id in setOf("sd", "hd") }
        val returned = if (playsWithSound(mapped)) (facebookProgressive + mapped).distinctBy { it.first.id }
            else genericDirect?.let { listOf(unreportedDirect(it) to it) }
                ?: directMp4s.ifEmpty {
                    // A lone non-live HLS MP4 with absent codecs (Kick clips) is the returned complete video.
                    val sole = nodes.singleOrNull()?.takeIf { !root.path("is_live").asBoolean(false) &&
                        root.text("live_status") != "is_live" && isUnreportedHlsVideo(it) }
                    sole?.let { listOf(unreportedDirect(it) to it) } ?: nodes.mapNotNull { node ->
                        format(node, inferHlsRoles = true)?.let { it to node } }.distinctBy { it.first.id }
                }
        // X animated GIF (silent): keep only its own video rows, so no audio partner, M4A or MP3 is ever derived.
        val animatedGif = returned.any { it.first.animatedGif }
        return Parsed(root, genericDirect, if (animatedGif) returned.filter { it.first.animatedGif } else returned)
    }

    fun map(metadata: String, source: LinkRevision, engineVersion: String): Inspection {
        val parsing = parse(metadata)
        val root = parsing.root
        val genericDirect = parsing.genericDirect
        val parsed = parsing.rows
        val animatedGif = parsed.any { it.first.animatedGif }
        // HLS rows carry only peak manifest BANDWIDTH; prefer a direct row of the same height and codec family.
        fun hls(node: JsonNode) = node.text("protocol")?.startsWith("m3u8") == true
        fun twin(row: Format) = row.height to VideoCodecs.family(row.videoCodec)
        val direct = parsed.filter { it.first.hasVideo && !hls(it.second) }.map { twin(it.first) }.toSet()
        val streams = parsed.filterNot { (row, node) -> row.hasVideo && hls(node) && twin(row) in direct }
        // yt-dlp's unpreferred baseline is -1; unknown must not outrank original/default or lose to descriptive (-10).
        fun preference(node: JsonNode) = node.finiteNumber("language_preference") ?: -1.0
        // owner 2026-10-18: auto-dubbed tracks (negative language_preference) hidden; app has no language picker.
        val hasOriginal = streams.any { !it.first.hasVideo && preference(it.second) >= 0 }
        val candidates = streams.filter { (row, node) -> row.hasVideo || !hasOriginal || preference(node) >= 0 }
        val raw = candidates.map { it.first }
        val languagePreferences = candidates.associate { it.first.id to preference(it.second) }
        val languageRank = compareBy<Format> { languagePreferences.getValue(it.id) }
        val audioRank = languageRank.thenBy { it.bitrateKbps ?: 0 }
        val audio = raw.filter { !it.hasVideo && it.hasAudio }
        // Keep every explicit audio row, but never rank a DRC duplicate ahead of its plain source.
        val audioIds = audio.map { it.id }.toSet()
        val preferredAudio = audio.filterNot { it.id.endsWith("-drc") && it.id.removeSuffix("-drc") in audioIds }
        val aac = preferredAudio.filter { it.container == "m4a" && isAac(it.audioCodec) }
            .maxWithOrNull(audioRank)
        val aacPartner = preferredAudio.filter { isAac(it.audioCodec) }.maxWithOrNull(audioRank)
        val anyAudio = preferredAudio.maxWithOrNull(audioRank)
        val unreportedHlsAudio = preferredAudio.filter { row -> row.audioCodec == null &&
            candidates.any { it.first.id == row.id && hls(it.second) } }.maxWithOrNull(audioRank)
        val hlsVideoIds = candidates.filter { hls(it.second) && it.first.hasVideo }.map { it.first.id }.toSet()
        fun partner(video: Format) = if (video.container == "mp4") aacPartner
            ?: unreportedHlsAudio?.takeIf { video.id in hlsVideoIds } ?: anyAudio else anyAudio
        fun withAudioPartner(video: Format): Format {
            val audioPartner = partner(video)
            if (video.hasAudio || audioPartner == null) return video
            // Pinterest's explicit audio-only MP4 HLS playlist lacks an acodec. Let FFmpeg probe it;
            // retain the unknown codec and size rather than claiming AAC or deriving a lossless M4A.
            val pinterestAudio = root.text("extractor_key") == "Pinterest" && video.id in hlsVideoIds &&
                audioPartner == unreportedHlsAudio && audioPartner.container in setOf("mp4", "m4a") &&
                VideoCodecs.mp4Safe(video.videoCodec)
            val container = if (VideoCodecs.mp4Safe(video.videoCodec) &&
                (isAac(audioPartner.audioCodec) || pinterestAudio)) "mp4" else "mkv"
            return video.copy(container = container, audioFormatId = audioPartner.id,
                requiresMerge = true, size = combined(video.size, audioPartner.size))
        }
        // A VP9 (or other known non-mp4-safe) row stays only when its height has no usable mp4-safe row.
        fun usable(row: Format) = row.hasAudio || partner(row) != null
        val safeHeights = raw.filter { it.hasVideo && it.container == "mp4" && VideoCodecs.mp4Safe(it.videoCodec) && usable(it) }
            .map { it.height }.toSet()
        val rawVideos = raw.filter { it.hasVideo && !(VideoCodecs.knownMp4Unsafe(it.videoCodec) && it.height in safeHeights) }
        // Facebook omits dimensions/bitrates on its progressive files: break that tie by name, hd > sd.
        fun facebookQuality(row: Format) = if (root.text("extractor_key") == "Facebook") when (row.id) {
            "hd" -> 2; "sd" -> 1; else -> 0
        } else 0
        // Preserve the established recommendation winners before pairing or exposing extra rows.
        val recommendations = rawVideos.groupBy { it.height }.values.map { rows ->
            rows.maxWith(compareBy<Format> { if (usable(it)) 1 else 0 }
                .thenBy { if (isMp4(it)) 1 else 0 }.thenBy { VideoCodecs.rank(it.videoCodec) }
                .thenBy { if (it.hasAudio) 1 else 0 }.thenBy { sizeRank(it.size) }.thenBy { it.bitrateKbps ?: 0 }
                .thenBy(::facebookQuality))
        }.map(::withAudioPartner)
        val videos = rawVideos.map(::withAudioPartner)
        val rows = (videos + audio).toMutableList()
        // A muxed AAC source can be extracted losslessly to M4A; retain its exact source ID.
        // Muxed fallbacks previously selected the first row; keep that order when preferences tie.
        val muxedAac = raw.filter { it.hasVideo && it.hasAudio && isAac(it.audioCodec) }.maxWithOrNull(languageRank)
        val extractedM4a = if (!animatedGif && aac == null && muxedAac != null) Format(
            "nezzel:m4a:${muxedAac.id}", "m4a", false, true, audioCodec = muxedAac.audioCodec,
            sourceFormatId = muxedAac.id) else null
        extractedM4a?.let(rows::add)
        val conversionSource = aac ?: anyAudio ?: raw.filter { it.hasAudio && !it.unreportedStreams }.maxWithOrNull(languageRank)
        // These sources require fixups that infer the muxer from the staging extension.
        // Keep their actual rows, but do not offer a conversion with an unsafe filename recipe.
        val conversionMetadata = candidates.firstOrNull { it.first.id == conversionSource?.id }?.second
        fun blocksConversion(node: JsonNode) = node.path("is_live").asBoolean(false) ||
            node.text("live_status") == "is_live" || node.path("is_dash_periods").asBoolean(false) ||
            node.text("protocol")?.split('+')?.contains("websocket_frag") == true
        if (!animatedGif && conversionSource != null && !blocksConversion(root) &&
            conversionMetadata?.let(::blocksConversion) != true) for (conversion in AudioConversion.entries) {
            // Derived rows always retain an actual returned source ID; no invented selector.
            val id = "nezzel:mp3:${conversion.bitrateKbps}:${conversionSource.id}"
            if (raw.none { it.id == id }) rows += Format(id, "mp3", false, true,
                audioCodec = "mp3", bitrateKbps = conversion.bitrateKbps, size = Size.Unknown,
                conversion = conversion, sourceFormatId = conversionSource.id, sourceContainer = conversionSource.container)
        }
        val availableVideo = recommendations.filter { it.hasAudio || it.requiresMerge || it.animatedGif }
        // Presets are compatibility-first (lead 2026-10-19): only the best codec tier present among mp4-safe rows
        // (H.264, else HEVC, else AV1); with none, any playable row (VP9 mkv). The full list keeps every tier.
        val mp4Video = availableVideo.filter { isMp4(it) || (it.container == "mp4" && it.unreportedStreams) ||
            (it.container == "mkv" && it.requiresMerge && VideoCodecs.mp4Safe(it.videoCodec)) }
        val bestTier = mp4Video.maxOfOrNull { VideoCodecs.rank(it.videoCodec) }
        val tier = mp4Video.filter { VideoCodecs.rank(it.videoCodec) == bestTier }
        // Owner 2026-10-27: HEVC taller than every H.264 row joins the presets (TikTok: 1080p HEVC over 540p H.264).
        // Every usable H.264 row counts, also one the per-height pick dropped for an mp4 (an Opus mkv merge).
        // TikTok's watermarked "download" copy has no height and is skipped; any other unknown height stops the climb.
        val tiktokDownload = root.text("extractor_key") == "TikTok"
        val avcHeights = videos.filter { VideoCodecs.family(it.videoCodec) == "avc" && (it.hasAudio || it.requiresMerge) &&
            !(tiktokDownload && it.id == "download" && it.height == null) }
            .takeIf { bestTier == VideoCodecs.rank("avc") }?.map { it.height }
        val tallerHevc = if (avcHeights.isNullOrEmpty() || null in avcHeights) emptyList() else mp4Video.filter {
            VideoCodecs.family(it.videoCodec) == "hevc" && (it.height ?: 0) > avcHeights.maxOf { h -> h ?: 0 } }
        val playable = (tier + tallerHevc).ifEmpty { availableVideo }
        val rank = compareBy<Format> { it.height ?: 0 }.thenBy { VideoCodecs.rank(it.videoCodec) }.thenBy { sizeRank(it.size) }.thenBy { it.bitrateKbps ?: 0 }
        val roughSizes = RoughSizeEstimator.estimate(root, candidates.associate { it.first.id to it }, rows)
            .filterKeys { it != genericDirect?.text("format_id") }
        val presets = mutableListOf<PresetChoice>()
        playable.maxWithOrNull(rank)?.let { presets += PresetChoice(Preset.BEST, it.id) }
        playable.filter { (it.height ?: Int.MAX_VALUE) <= 720 }.maxWithOrNull(rank)
            ?.let { presets += PresetChoice(Preset.BALANCED, it.id) }
        playable.minWithOrNull(compareBy<Format> { bytes(it.size) ?: roughSizes[it.id] ?: Long.MAX_VALUE }.then(rank))
            ?.let { presets += PresetChoice(Preset.SMALL, it.id) }
        (aac ?: extractedM4a)?.let { presets += PresetChoice(Preset.AUDIO_M4A, it.id) }
        rows.firstOrNull { it.conversion == AudioConversion.MAIN }
            ?.let { presets += PresetChoice(Preset.AUDIO_MP3, it.id) }
        return Inspection(source, engineVersion, rows, presets,
            title = (displayText(root.text("title"), 300) ?: displayText(root.text("fulltitle"), 300))
                ?.let { if (root.text("extractor_key")?.startsWith("Facebook") == true) withoutFacebookCounts(it) else it },
            uploader = displayText(root.text("uploader"), 120) ?: displayText(root.text("channel"), 120),
            durationSeconds = root.get("duration")?.takeIf { it.isNumber }?.doubleValue()
                ?.takeIf { it.isFinite() && it >= 1 && it <= PreviewMetadata.MAX_DURATION_SECONDS }?.toLong(),
            thumbnailUrl = thumbnail(root),
            roughSizeBytes = roughSizes)
    }
    // Facebook's og:title leads with engagement counts: "9.7K views · 352 reactions | <title>" (also the
    // "119 reactions · 1.4K shares | <title>" shape). Only these exact two-count shapes; a single count
    // ("10 views | Paris") or anything else is kept as-is.
    private const val N = "[0-9][0-9.,]*[KMB]?"
    private val facebookCounts = Regex("^$N views? · $N reactions? \\| |^$N reactions? · $N shares? \\| ")
    internal fun withoutFacebookCounts(title: String): String =
        facebookCounts.find(title)?.let { title.substring(it.range.last + 1).trim() }?.ifEmpty { null } ?: title
    /**
     * Some extractors (Facebook) return HTML-escaped titles; decode once, then clean as before. TikTok can escape its
     * emoji twice (`&amp;#128558;`, phone 2026-10-27), so a numeric reference left after that is decoded too.
     */
    private fun displayText(value: String?, maxCodePoints: Int): String? = PreviewMetadata.text(value
        ?.let { Parser.unescapeEntities(it, false) }
        ?.let { once -> numericReference.replace(once) { Parser.unescapeEntities(it.value, false) } }, maxCodePoints)
    private val numericReference = Regex("&#(?:[0-9]{1,7}|[xX][0-9a-fA-F]{1,6});")
    private fun thumbnail(root: JsonNode): String? = PreviewMetadata.thumbnail(root.text("thumbnail"))
        ?: root.get("thumbnails")?.takeIf { it.isArray }?.mapNotNull { node ->
            PreviewMetadata.thumbnail(node.text("url"))?.let { it to (node.positiveInt("width") ?: 0) }
        }?.filter { it.second <= 1280 }
            // Prefer known, bounded widths; later metadata candidates win ties (including unknown width).
            ?.asReversed()?.maxByOrNull { it.second }?.first

    private fun format(node: JsonNode, inferHlsRoles: Boolean = false): Format? {
        if (node.path("has_drm").asBoolean(false)) return null
        val id = node.text("format_id")?.takeIf(YtdlpCommand::isExactId) ?: return null
        val ext = node.text("ext")?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) } ?: return null
        val video = node.codec("vcodec")
        val audio = node.codec("acodec")
        val hls = inferHlsRoles && node.text("protocol")?.startsWith("m3u8") == true
        // A positive height identifies a video rendition; explicit vcodec=none identifies a separate audio
        // playlist. "none" audio means silent, whereas absent/unknown audio may be recovered only in that role.
        val hasVideo = video != null || (hls && ext in setOf("mp4", "webm") &&
            node.text("vcodec")?.lowercase() != "none" && node.positiveInt("height") != null)
        val hasAudio = audio != null || (hls && ext in setOf("mp4", "m4a", "webm") &&
            node.text("vcodec")?.lowercase() == "none" && node.text("acodec")?.lowercase() != "none" &&
            node.positiveInt("height") == null && node.positiveInt("width") == null)
        if (ext == "mhtml" || (!hasVideo && !hasAudio)) return null
        val size = node.positiveLong("filesize")?.let(Size::Exact)
            ?: node.positiveLong("filesize_approx")?.let(Size::Estimated) ?: Size.Unknown
        return Format(id, ext, hasVideo, hasAudio, video, audio,
            node.positiveInt("width"), node.positiveInt("height"),
            node.positiveInt(if (hasVideo) "tbr" else "abr") ?: node.positiveInt("tbr"), size,
            animatedGif = hasVideo && isXAnimatedGif(node))
    }
    /** Returned non-manifest files only; codec-less variants retain their established ambiguity guards. */
    private fun unreportedDirectMp4s(nodes: List<JsonNode>, extractor: String?): List<JsonNode> {
        // Streamable/9gag report a video codec but omit audio facts on their progressive MP4s.
        val partial = nodes.filter { extractor in setOf("Streamable", "NineGag") &&
            isDirectFile(it, setOf("mp4"), allowLiteralId = true) &&
            it.codec("vcodec") != null && it.get("acodec")?.isNull != false }
        val candidates = nodes.filter(::isUnreportedDirectMp4)
        val facebookFiles = if (extractor == "Facebook") candidates.filter { it.text("format_id") in setOf("sd", "hd") } else emptyList()
        val unknown = candidates - facebookFiles.toSet()
        return (partial + facebookFiles + ambiguousDirectMp4s(unknown)).distinctBy { it.text("format_id") }
    }
    private fun ambiguousDirectMp4s(candidates: List<JsonNode>): List<JsonNode> {
        val heights = candidates.map { it.positiveInt("height") }
        if (candidates.size > 1 && heights.none { it == null } && heights.toSet().size == heights.size) return candidates
        if (candidates.size <= 1) return candidates
        val rates = candidates.map { node -> node.finiteNumber("tbr")?.takeIf { it > 0 } ?: return emptyList() }
        val top = rates.max()
        return listOfNotNull(candidates[rates.indexOf(top)].takeIf { rates.count { it == top } == 1 })
    }
    private fun isUnreportedDirectMp4(node: JsonNode): Boolean {
        if (listOf("vcodec", "acodec").any { node.get(it)?.isNull == false }) return false
        return isDirectFile(node, setOf("mp4"))
    }
    private fun isUnreportedHlsVideo(node: JsonNode): Boolean =
        node.text("protocol")?.startsWith("m3u8") == true && node.text("ext") == "mp4" &&
            !node.path("has_drm").asBoolean(false) && !node.path("is_live").asBoolean(false) &&
            node.text("live_status") != "is_live" && node.text("format_id")?.let(YtdlpCommand::isExactId) == true &&
            listOf("vcodec", "acodec").all { node.get(it)?.isNull != false }
    private fun isDirectFile(node: JsonNode, extensions: Set<String>, allowLiteralId: Boolean = false): Boolean {
        if (node.path("has_drm").asBoolean(false) || node.path("is_live").asBoolean(false)) return false
        val id = node.text("format_id") ?: return false
        val ext = node.text("ext")?.takeIf { it in extensions } ?: return false
        if (!YtdlpCommand.isExactId(id) && !(allowLiteralId && YtdlpCommand.isDirectVideoId(id, ext))) return false
        val protocol = node.text("protocol")
        if (protocol != "http" && protocol != "https" || node.get("fragments") != null) return false
        val url = node.text("url")?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
        val path = url.path?.lowercase() ?: return false
        return url.scheme?.lowercase() in setOf("http", "https") && !path.endsWith(".m3u8") && !path.endsWith(".mpd")
    }
    private fun unreportedDirect(node: JsonNode): Format {
        val size = node.positiveLong("filesize")?.let(Size::Exact)
            ?: node.positiveLong("filesize_approx")?.let(Size::Estimated) ?: Size.Unknown
        return Format(node.text("format_id")!!, node.text("ext")!!, true, true, videoCodec = node.codec("vcodec"), width = node.positiveInt("width"),
            height = node.positiveInt("height"), bitrateKbps = node.positiveInt("tbr"), size = size,
            animatedGif = isXAnimatedGif(node))
    }
    /**
     * X serves animated GIFs as silent mp4 files under `video.twimg.com/tweet_video/`; real videos use
     * `ext_tw_video/` or `amplify_video/`. Without that exact signal nothing is labelled a GIF.
     */
    private fun isXAnimatedGif(node: JsonNode): Boolean {
        val url = node.text("url")?.let { runCatching { java.net.URI(it) }.getOrNull() } ?: return false
        return url.scheme?.lowercase() == "https" && url.host?.lowercase() == "video.twimg.com" &&
            url.path?.startsWith("/tweet_video/") == true
    }
    private fun JsonNode.text(key: String) = get(key)?.takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
    private fun JsonNode.codec(key: String) = text(key)?.takeUnless { it.lowercase() in setOf("none", "unknown") }
    private fun JsonNode.finiteNumber(key: String) = get(key)?.takeIf { it.isNumber }?.doubleValue()?.takeIf { it.isFinite() }
    private fun JsonNode.positiveLong(key: String) = get(key)?.takeIf { it.isIntegralNumber && it.canConvertToLong() }?.longValue()?.takeIf { it >= 0 }
    private fun JsonNode.positiveInt(key: String) = get(key)?.takeIf { it.isNumber }?.doubleValue()
        ?.takeIf { it.isFinite() && it >= 1 && it <= Int.MAX_VALUE }?.toInt()
    private fun isAac(codec: String?) = codec == "aac" || codec?.startsWith("mp4a") == true
    private fun isMp4(row: Format) = row.container == "mp4" && VideoCodecs.mp4Safe(row.videoCodec) &&
        (!row.hasAudio || isAac(row.audioCodec))
    private fun sizeRank(size: Size) = when (size) { is Size.Exact -> 2; is Size.Estimated -> 1; Size.Unknown -> 0 }
    private fun bytes(size: Size): Long? = when (size) { is Size.Exact -> size.bytes; is Size.Estimated -> size.bytes; Size.Unknown -> null }
    private fun combined(a: Size, b: Size): Size {
        val x = bytes(a) ?: return Size.Unknown; val y = bytes(b) ?: return Size.Unknown
        if (x > Long.MAX_VALUE - y) return Size.Unknown
        return if (a is Size.Exact && b is Size.Exact) Size.Exact(x + y) else Size.Estimated(x + y)
    }
}
