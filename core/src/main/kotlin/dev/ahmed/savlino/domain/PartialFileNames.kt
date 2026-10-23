package dev.ahmed.savlino.domain

/** yt-dlp continuation inputs only; callers also reject directories and symlinks. */
object PartialFileNames {
    private val pattern = Regex(".+\\.part(?:-Frag[^/]*)?|.+\\.ytdl|output\\.f[A-Za-z0-9_.-]+\\.[A-Za-z0-9_.-]+|.+\\.temp")
    fun matches(name: String): Boolean = '/' !in name && '\\' !in name && pattern.matches(name)
}
