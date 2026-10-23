package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import java.io.File

/** Arguments passed directly to ProcessBuilder by the wrapper, never a shell. */
object YtdlpCommand {
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
    fun inspect(source: LinkRevision) = listOf("--ignore-config", "--no-playlist", "--playlist-items", "1") + NETWORK +
        listOf("--dump-json", "--skip-download", "--", source.link.rawUrl)
    fun download(command: DownloadCommand, directory: File, infoJson: File? = null): List<String> {
        val root = directory.canonicalFile
        require(root.isDirectory && root.list()?.isEmpty() == true) { "Staging directory must be empty" }
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
            if (!row.hasVideo && row.container == "m4a" && row.sourceFormatId != row.id)
                addAll(listOf("--extract-audio", "--audio-format", "m4a"))
            row.conversion?.let { conversion ->
                // Output-scoped options follow yt-dlp's generated copy/quality options.
                // Use one scoped value so encoder, bitrate and resampling apply together.
                val resample = if (conversion == AudioConversion.MP3_320) " -ar 44100" else ""
                addAll(listOf("--extract-audio", "--audio-format", "mp3", "--audio-quality", "${conversion.bitrateKbps}K",
                    "--postprocessor-args", "ExtractAudio+ffmpeg_o1:-c:a libmp3lame -b:a ${conversion.bitrateKbps}k$resample"))
            }
            if (infoJson != null) addAll(listOf("--load-info-json", infoJson.absolutePath))
            else addAll(listOf("--", command.source.link.rawUrl))
        }
    }
}
