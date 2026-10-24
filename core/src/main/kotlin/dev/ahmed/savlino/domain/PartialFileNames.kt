package dev.ahmed.savlino.domain

/** Transfer partials and the URL-free native ownership record; callers also reject directories and symlinks. */
object PartialFileNames {
    private val pattern = Regex(".+\\.part(?:-Frag[^/]*)?|.+\\.ytdl|output\\.f[A-Za-z0-9_.-]+\\.[A-Za-z0-9_.-]+|.+\\.temp|output\\.temp\\.[A-Za-z0-9]+")
    fun matches(name: String): Boolean = name == DirectResume.NAME || '/' !in name && '\\' !in name && pattern.matches(name)
}
