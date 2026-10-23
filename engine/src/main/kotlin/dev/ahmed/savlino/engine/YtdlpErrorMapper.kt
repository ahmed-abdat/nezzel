package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.DownloadFailureCause
import java.net.URI
import java.util.Locale

/** Raw stderr never crosses the domain boundary; debug diagnostics redact URLs separately. */
object YtdlpErrorMapper {
    fun map(stderr: String): DownloadError {
        // Ignore diagnostic/warning lines and URL paths/queries that happen to contain keywords.
        val errors = stderr.lineSequence().filter { it.startsWith("ERROR:") }.toList()
        // A short link that resolved to a bare site root ("Unsupported URL: https://www.tiktok.com/?_r=1") no
        // longer points to a post. Only the shape is inspected; the URL itself is never kept or logged.
        val siteRoot = errors.any { Regex("^ERROR:\\s*unsupported url:\\s*https?://[^/\\s?#]+/?(?:[?#]\\S*)?\\s*$",
            RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val text = errors
            .joinToString("\n") { it.removePrefix("ERROR:").replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "") }
            .lowercase(Locale.ROOT)
        fun has(vararg patterns: String) = patterns.any(text::contains)
        val fragmentFailure = FRAGMENT_FAILURE.containsMatchIn(text)
        // Availability phrases are read from the final ERROR line only, and never when the failure is transient
        // (offline/network/429/5xx): "Video unavailable" + timeout must stay a retryable failure.
        val last = text.lineSequence().lastOrNull().orEmpty().trim()
        val transient = fragmentFailure || has("network is unreachable", "name resolution", "unable to resolve host", "no address associated with hostname",
            "no route to host", "name or service not known", "nodename nor servname provided", "timed out", "connection refused",
            "connection reset", "connection abort", "remote end closed connection", "failed to establish a new connection",
            "failed to resolve", "getaddrinfo failed", "broken pipe", "incompleteread", "eof occurred in violation", "ssleoferror",
            "too many requests", "temporarily unavailable", "service unavailable") ||
            Regex("\\bssl\\b[^\\n]*eof").containsMatchIn(text) || Regex("\\bhttp error (429|5\\d\\d)\\b").containsMatchIn(text)
        // Photo-only answers, only from the extractors that are known to give them for photo posts.
        val photoExtractor = Regex("^\\[(?:instagram|twitter|tiktok|bluesky|telegram)[^\\]]*\\]").containsMatchIn(last)
        val mediaFailure = Regex("\\b(?:unable|failed) to download (?:video data|media|(?:hls |dash )?fragment)\\b")
        fun lastHas(vararg patterns: String) = patterns.any(last::contains)
        return when {
            has("no space left", "enospc") -> DownloadError.InsufficientSpace
            Regex("\\bdrm\\b").containsMatchIn(text) || has("digital rights management") -> DownloadError.DrmProtected
            has("sign in to confirm", "sign in if", "sign in to watch", "please sign in", "not logged in",
                "login required", "log in to", "please log in", "authentication required",
                "private video", "video is private", "members-only", "join this channel",
                "only available for registered users", "cookies-from-browser", "use --cookies") ||
                // A 401 on a media/fragment URL is an expired selection, not a sign-in wall.
                text.lineSequence().any { line ->
                    Regex("\\bhttp error 401\\b|\\boauth\\b[^\\n]*\\b401\\b").containsMatchIn(line) && !mediaFailure.containsMatchIn(line)
                } -> DownloadError.LoginRequired
            !transient && text.lineSequence().any { line ->
                Regex("\\bhttp error 403\\b").containsMatchIn(line) && !mediaFailure.containsMatchIn(line)
            } -> DownloadError.SourceRefused
            // Known photo-post extractors: no-video responses are not stale format selections.
            !transient && photoExtractor && lastHas("no video in this post", "no video could be found in this post", "no video could be found in this tweet",
                "no video formats found") -> DownloadError.NoVideo
            !transient && lastHas("has been removed", "video unavailable", "video is unavailable", "video is no longer available",
                "video not available", "not available in your country", "geo restricted", "geo-restricted",
                "not available from your location") -> DownloadError.Unavailable
            siteRoot -> DownloadError.NotAPost
            has("unsupported url", "no suitable extractor") -> DownloadError.Unsupported
            // A missing post page (not a media fragment) means the post is gone.
            !transient && Regex("\\bhttp error (404|410)\\b").containsMatchIn(last) &&
                Regex("\\bunable to download (?:webpage|json metadata|api json)\\b").containsMatchIn(last) -> DownloadError.Unavailable
            // Page/metadata 404/410 does not imply a stale selected media URL. Keep context on
            // the same ERROR line so an unrelated media failure cannot reclassify a page error.
            text.lineSequence().any { line ->
                    Regex("\\bhttp error (401|403|404|410)\\b").containsMatchIn(line) &&
                        mediaFailure.containsMatchIn(line)
                } ||
                has("requested format is not available") -> DownloadError.FormatExpired
            has("postprocessing", "ffmpeg exited", "unable to merge") -> DownloadError.MergeFailed
            has("network is unreachable", "name resolution", "unable to resolve host", "no address associated with hostname", "no route to host", "name or service not known", "nodename nor servname provided") -> DownloadError.Offline
            has("timed out", "connection refused", "connection reset", "connection abort", "remote end closed connection",
                "failed to establish a new connection", "failed to resolve", "getaddrinfo failed", "broken pipe",
                "incompleteread", "eof occurred in violation", "ssleoferror") ||
                Regex("\\bssl\\b[^\\n]*eof").containsMatchIn(text) -> DownloadError.Network
            fragmentFailure && !Regex("\\bhttp error \\d{3}\\b").containsMatchIn(text) -> DownloadError.Network
            else -> DownloadError.Failed
        }
    }

    fun cause(stderr: String): DownloadFailureCause {
        val text = stderr.lineSequence().filter { it.startsWith("ERROR:") }
            .joinToString("\n") { it.replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "") }
            .lowercase(Locale.ROOT)
        // An HTTP response is never a lost connection, even on a fragment or alongside a timeout.
        if (Regex("\\bhttp error \\d{3}\\b").containsMatchIn(text) ||
            text.contains("too many requests") || text.contains("service unavailable")) return DownloadFailureCause.HTTP
        return map(stderr).cause
    }

    private val FRAGMENT_FAILURE = Regex("\\b(?:unable|failed) to download (?:hls |dash )?fragment\\b|" +
        "\\bfragment(?: \\d+)? (?:not found|failed|is unavailable)\\b")

    /** Inspection-only facts: retain no raw diagnostics, identifiers or URLs. */
    internal class EmptyInspection {
        @Volatile private var hasError = false
        @Volatile private var photoExtractorReturnedNothing = false
        fun accept(output: String) {
            output.lineSequence().forEach { line ->
                if (line.startsWith("ERROR:")) hasError = true
                if (returnedNothing.matches(line)) photoExtractorReturnedNothing = true
            }
        }
        fun isEmptyFailure(message: String) = message.lineSequence().all { it.isBlank() || returnedNothing.matches(it) }
        fun error(sourceUrl: String, fallback: DownloadError): DownloadError = when {
            hasError -> fallback
            isSiteRoot(sourceUrl) -> DownloadError.NotAPost
            photoExtractorReturnedNothing -> DownloadError.NoVideo
            else -> fallback
        }
        private fun isSiteRoot(url: String): Boolean {
            val uri = URI(url) // Input already validated by LinkInput; inspect shape only.
            if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return false
            // Unknown/content queries (e.g. ?v=...) may identify a post. Only known tracking keys are ignored.
            return uri.rawQuery.isNullOrBlank() || uri.rawQuery.split('&').all {
                val key = it.substringBefore('=').lowercase(Locale.ROOT)
                key == "_r" || key.startsWith("utm_")
            }
        }
        private companion object {
            val returnedNothing = Regex("^WARNING:\\s*Extractor (?:instagram|twitter|tiktok|bluesky|telegram)(?::[a-z0-9_-]+)* returned nothing\\s*$",
                RegexOption.IGNORE_CASE)
        }
    }
}
