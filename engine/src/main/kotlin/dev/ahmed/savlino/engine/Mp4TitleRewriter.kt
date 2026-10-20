package dev.ahmed.savlino.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** Optional local MP4 polish, after yt-dlp finishes and before either publication destination sees bytes. */
internal class Mp4TitleRewriter(
    private val readTitle: (File) -> String?,
    private val ffmpeg: (List<String>) -> Boolean,
    private val replace: (File, File) -> Unit = { source, destination ->
        // Same directory/filesystem. Never delete the original first or fall back to a non-atomic copy.
        Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
) {
    fun rewrite(file: File, title: String) {
        if (!file.extension.equals("mp4", ignoreCase = true) || title.isBlank()) return
        val temporary = File(file.parentFile, "output.nezzel-title.mp4")
        try {
            // Only a wrong embedded tag is repaired: with none, the scanner keeps the published TITLE, so a
            // full copy of a large MP4 would buy nothing. Unreadable tags are left alone too.
            val existing = readTitleOrNull(file)
            if (existing.isNullOrBlank() || existing == title) return
            // Do not follow/overwrite an unexpected leftover. The job owns staging cleanup.
            if (Files.exists(temporary.toPath(), LinkOption.NOFOLLOW_LINKS)) return
            try {
                if (!ffmpeg(arguments(file, temporary, title))) return
                if (!Files.isRegularFile(temporary.toPath(), LinkOption.NOFOLLOW_LINKS) || temporary.length() <= 0) return
                if (readTitleOrNull(temporary) != title) return
                replace(temporary, file)
            } finally {
                temporary.delete()
            }
        } catch (_: Exception) {
            // Unsupported muxers/streams, low space, failed probes or rename: keep the original, never log metadata.
        }
    }

    private fun readTitleOrNull(file: File): String? = try { readTitle(file) } catch (_: Exception) { null }

    private fun arguments(input: File, output: File, title: String) = listOf(
        "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", input.absolutePath,
        "-map", "0", "-c", "copy",
        // No inherited global/stream/chapter metadata, including source comment/purl tags. No yt-dlp metadata PP.
        "-map_metadata", "-1", "-map_metadata:s", "-1", "-map_chapters", "-1", "-movflags", "+faststart",
        "-metadata", "title=$title", "-metadata", "encoder=", "-metadata:s", "encoder=", output.absolutePath,
    )
}
