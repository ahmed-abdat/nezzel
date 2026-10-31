package dev.ahmed.savlino.engine

import android.util.Log

/** Bounded diagnostics with source identifiers and output paths masked before retention. */
internal class YtdlpDebugDiagnostics(
    private val enabled: Boolean = BuildConfig.DEBUG,
    args: List<String> = emptyList(),
    private val log: (String, String) -> Unit = { tag, message -> Log.d(tag, message); Unit },
) {
    // Info parsing and tag replacement may echo private titles, and a search echoes the person's words. Suppress the entire attempt.
    private val privateInput = "--load-info-json" in args || "--embed-metadata" in args || args.any { it.startsWith(YouTubeSearch.PREFIX) }
    private val tail = ArrayDeque<String>()
    @Synchronized fun accept(output: String) {
        if (!enabled || privateInput) return
        output.lineSequence().filter { it.isNotEmpty() }.forEach { line ->
            if (tail.size == 20) tail.removeFirst()
            tail.addLast(redact(line))
        }
    }
    @Synchronized fun finished(exitCode: Int?, stderr: String = "") {
        if (!enabled || privateInput) return
        accept(stderr)
        log("NezzelEngine", "yt-dlp exitCode=${exitCode ?: "unavailable (wrapper did not return a response)"}")
        tail.forEach { log("NezzelEngine", it) }
        tail.clear()
    }
    private fun redact(line: String): String {
        // These lines can contain unquoted titles/paths with spaces; mask the whole line.
        if (PRIVATE_OUTPUT.containsMatchIn(line)) return "<private output>"
        val withoutUris = line.replace(URI, "<url>")
            .replace(EXTRACTOR_ID) { "${it.groupValues[1]}<id>:" }
        return TOKEN.replace(withoutUris) {
            val token = it.value
            if (token.contains('/') || token.contains('\\') || HOST.containsMatchIn(token)) "<path>" else token
        }
    }
    private companion object {
        val URI = Regex("[a-z][a-z0-9+.-]*://\\S+", RegexOption.IGNORE_CASE)
        val PRIVATE_OUTPUT = Regex("\\bDestination\\s*:|\\[(?:Merger|ExtractAudio)]", RegexOption.IGNORE_CASE)
        val EXTRACTOR_ID = Regex("(\\[[\\w:-]+]\\s+)[^\\s:]+:")
        val TOKEN = Regex("\\S+")
        val HOST = Regex("\\b(?:[a-z0-9-]+\\.)+[a-z0-9-]+", RegexOption.IGNORE_CASE)
    }
}
