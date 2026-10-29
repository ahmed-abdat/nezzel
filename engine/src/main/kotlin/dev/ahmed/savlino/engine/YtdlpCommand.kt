package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.domain.*
import java.io.File

/** Arguments passed directly to ProcessBuilder by the wrapper, never a shell. */
object YtdlpCommand {
    // Bump whenever download argv changes: partials from a different recipe must restart.
    const val ARGV_VERSION = 4
    internal const val SQUARE_COVER = "ThumbnailsConvertor+ffmpeg_o:-c:v mjpeg -qmin 1 -qscale:v 1 -vf \"crop=min(iw\\,ih):min(iw\\,ih)\""
    const val PROGRESS_TEMPLATE = "download:nezzel-v2:%(info.format_id)s:%(progress.downloaded_bytes)s:%(progress.total_bytes)s:%(progress.total_bytes_estimate)s:%(progress.fragment_index)s:%(progress.fragment_count)s"
    // Pinned yt-dlp grammar treats these names/extensions as selectors, even without operators.
    private val reservedId = Regex("(best|worst|b|w)(video|audio|v|a)?(\\.[1-9][0-9]*)?")
    private val selectorExtensions = setOf("avi", "flv", "mkv", "mov", "mp4", "webm", "3gp",
        "aiff", "alac", "flac", "m4a", "mka", "mp3", "ogg", "opus", "wav", "mhtml")
    fun isExactId(id: String): Boolean = id.matches(Regex("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,255}")) &&
        !reservedId.matches(id) && id !in selectorExtensions && id !in setOf("all", "mergeall")
    internal fun isDirectVideoId(id: String, container: String) = id == container && id in setOf("mp4", "mov", "webm")
    /** Bounded network waits (lead 2026-10-19, OPEN_DECISIONS): a dead socket ends in 20 s, transient errors retry. */
    val NETWORK = listOf("--socket-timeout", "20", "--retries", "5", "--fragment-retries", "10")
    internal fun isYouTube(source: LinkRevision): Boolean = source.link.host == Host.YOUTUBE
    /** [target]: what yt-dlp reads in place of the post link (a Reddit post's own video), never user input. */
    fun inspect(source: LinkRevision, target: String? = null) = listOf("--ignore-config", "--no-playlist", "--playlist-items", "1") +
        NETWORK + listOf("--dump-json", "--skip-download", "--", target ?: source.link.rawUrl)
    fun playlist(source: LinkRevision, start: Int, end: Int): List<String> {
        require(isYouTube(source) && start > 0 && end >= start && end - start < 100)
        return listOf("--ignore-config", "--yes-playlist", "--flat-playlist", "--lazy-playlist",
            "--dump-single-json", "--skip-download", "--playlist-items", "$start:$end") + NETWORK +
            listOf("--", source.link.rawUrl)
    }
    /**
     * The finished file yt-dlp reuses instead of downloading again ("has already been downloaded"): the source of an
     * MP3 conversion, or the final merged/single file. A stop in convert, merge or save keeps it for a compatible retry.
     * Kept only when its writer creates it complete by a rename: the download (.part), yt-dlp's merger and fixups
     * (.temp), the title rewrite and the native engine (atomic move). ExtractAudio writes its target directly, so an
     * extracted M4A or an MP3 target is never kept; nor is a native M4A/MP3, whose cover EmbedThumbnail saves in place
     * with mutagen. An "mp3" source has no extension (see [download]'s template).
     */
    fun finishedSource(format: Format): String? = when {
        extractsAudio(format) -> null
        format.conversion == null && !format.hasVideo && format.container in setOf("m4a", "mp3") -> null
        format.conversion == null -> "output.${format.container}"
        format.sourceContainer == "mp3" -> "output"
        format.sourceContainer != null -> "output.${format.sourceContainer}"
        else -> null
    }
    /** The finished source a failed conversion kept, or null: only a nonempty regular file in a valid staging directory. */
    fun keptConversionSource(format: Format, directory: File): File? {
        if (format.conversion == null) return null
        val name = finishedSource(format) ?: return null
        val root = try { validateStaging(directory, name) } catch (_: IllegalArgumentException) { return null }
        return File(root, name).takeIf {
            !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile && it.length() > 0
        }
    }
    /**
     * An MP3 row converts rather than merges: yt-dlp's ffmpeg failure there is a failed conversion, retried locally.
     * M4A extraction stays MergeFailed: nothing keeps its source, so ConvertFailed's local retry would not hold.
     */
    fun postProcessingError(format: Format, error: DownloadError): DownloadError =
        if (error == DownloadError.MergeFailed && format.conversion != null) DownloadError.ConvertFailed else error
    /** Shared by both transfer engines before any staging write. */
    internal fun validateStaging(directory: File, kept: String? = null): File {
        val root = directory.canonicalFile
        require(!java.nio.file.Files.isSymbolicLink(directory.toPath()) && root.isDirectory &&
            root.listFiles()?.all { !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.isFile &&
                (PartialFileNames.matches(it.name) || it.name == kept) } == true) { "Invalid staging directory" }
        return root
    }
    /** [target]: read in place of the post link when there is no info JSON, as in [inspect]. */
    fun download(command: DownloadCommand, directory: File, infoJson: File? = null,
        embedAudioTags: Boolean = true, target: String? = null): List<String> {
        val root = validateStaging(directory, finishedSource(command.format))
        val row = command.format
        // Generic direct-file IDs can equal a MIME subtype/container. A bare "mp4" is an extension selector,
        // not an exact ID; filter by the actual returned ID so it cannot silently choose a different format.
        val literalDirect = row.unreportedStreams && row.sourceFormatId == row.id && isDirectVideoId(row.id, row.container)
        require((isExactId(row.sourceFormatId) || literalDirect) && (row.audioFormatId?.let(::isExactId) != false))
        val selector = if (literalDirect) "all[format_id=${row.sourceFormatId}]"
            else row.sourceFormatId + (row.audioFormatId?.let { "+$it" } ?: "")
        // MP3 input must have a different pathname from the extracted .mp3 to avoid
        // ExtractAudio's same-codec early return. Other sources keep fixup-safe extensions.
        // Legacy journal rows lack provenance and deliberately retain their prior template.
        val template = if (row.conversion != null && row.sourceContainer == "mp3") "output" else "output.%(ext)s"
        return buildList {
            addAll(listOf("--ignore-config", "--no-playlist", "--playlist-items", "1"))
            addAll(NETWORK)
            addAll(listOf("--abort-on-unavailable-fragments", "--retry-sleep", "http:exp=1:8",
                "--retry-sleep", "fragment:exp=1:16"))
            if (!isYouTube(command.source)) addAll(listOf("-N", "4"))
            addAll(listOf("--no-simulate", "--newline",
                "--progress-template", PROGRESS_TEMPLATE, "-f", selector,
                "-o", File(root, template).path, "--print-to-file", "after_move:filepath", File(root, "result-path.txt").path))
            // yt-dlp's ffmpeg postprocessors already add `-movflags +faststart` to every output they write.
            // The mapper chooses MP4 for supported codec pairs, otherwise MKV.
            if (row.requiresMerge && row.container in setOf("mp4", "mkv")) addAll(listOf("--merge-output-format", row.container))
            if (extractsAudio(row))
                addAll(listOf("--extract-audio", "--audio-format", "m4a"))
            // MP3 rows: yt-dlp only downloads; Mp3Encoder encodes and writes the tags and cover these files carry.
            if (row.conversion != null && embedAudioTags) addAll(mp3SourceArgs(root))
            else if (embedAudioTags && !row.hasVideo && row.container in setOf("m4a", "mp3"))
                addAll(audioTagArgs(infoJson))
            if (infoJson != null) addAll(listOf("--load-info-json", infoJson.absolutePath))
            else addAll(listOf("--", target ?: command.source.link.rawUrl))
        }
    }

    /** Whether the info JSON names a real artist; without one yt-dlp's uploader/channel fallback is blanked. */
    internal fun realArtist(infoJson: File?): Boolean = try {
        infoJson?.let { ObjectMapper().readTree(it)?.get("artist") }
            ?.takeIf { it.isTextual }?.asText()?.isNotBlank() == true
    } catch (_: Exception) { false }
    /** The same check over one JSON line (the printed MP3 tag fields). */
    internal fun realArtist(json: String?): Boolean = try {
        json?.let { ObjectMapper().readTree(it)?.get("artist") }
            ?.takeIf { it.isTextual }?.asText()?.isNotBlank() == true
    } catch (_: Exception) { false }

    /** yt-dlp fields FFmpegMetadataPP would read for the kept tags; Mp3Tags applies its rules to them. */
    private const val MP3_TAGS = "%(.{title,track,track_number,artist,artists,creator,creators,uploader,uploader_id," +
        "composer,composers,album,album_artist,album_artists,disc_number,series,season_number,episode,episode_id,episode_number})j"

    /** The square jpg cover as output.temp.jpg, the tag fields as output.temp.tags, and today's title cleanup. */
    private fun mp3SourceArgs(root: File): List<String> = listOf(
        "-o", "thumbnail:" + File(root, "output.temp.%(ext)s").path, "--write-thumbnail", "--convert-thumbnails", "jpg",
        "--postprocessor-args", SQUARE_COVER,
        "--print-to-file", "after_move:$MP3_TAGS", File(root, Mp3Encoder.TAGS).path) + TITLE_CLEANUP

    // Python regex syntax: preserve BMP Arabic, strip supplementary/zero-width characters in tags only.
    private val TITLE_CLEANUP = listOf("--replace-in-metadata", "title", "[\\U00010000-\\U0010ffff\\u200b-\\u200d\\ufeff]|&(?:amp;)?#(?:[0-9]+|[xX][0-9a-fA-F]+);", "",
        "--replace-in-metadata", "title", "\\s+$", "")

    /** Only real artist metadata is trusted; yt-dlp otherwise falls back to the uploader/channel. */
    private fun audioTagArgs(infoJson: File?): List<String> = buildList {
        addAll(listOf("--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg"))
        // Square cover (players crop 16:9 art). yt-dlp shlex-splits the value; the escaped commas reach ffmpeg's filter parser.
        addAll(listOf("--postprocessor-args", SQUARE_COVER))
        val blank = listOf("comment", "purl", "description", "synopsis", "genre", "date") +
            if (!realArtist(infoJson)) listOf("artist") else emptyList()
        blank.forEach { addAll(listOf("--parse-metadata", ":(?P<meta_$it>)")) }
        addAll(TITLE_CLEANUP)
    }
}
