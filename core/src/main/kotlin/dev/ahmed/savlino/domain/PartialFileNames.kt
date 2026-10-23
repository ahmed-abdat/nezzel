package dev.ahmed.savlino.domain

/** yt-dlp continuation inputs only (output.temp.<ext>: an unfinished ffmpeg merge); callers also reject directories and symlinks. */
object PartialFileNames {
    private val pattern = Regex(".+\\.part(?:-Frag[^/]*)?|.+\\.ytdl|output\\.f[A-Za-z0-9_.-]+\\.[A-Za-z0-9_.-]+|.+\\.temp|output\\.temp\\.[A-Za-z0-9]+")
    fun matches(name: String): Boolean = '/' !in name && '\\' !in name && pattern.matches(name)
}
