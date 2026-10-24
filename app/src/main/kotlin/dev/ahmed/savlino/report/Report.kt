package dev.ahmed.savlino.report

import java.net.URI
import org.json.JSONObject

/**
 * Exactly what one report sends. Never log a report or its JSON.
 * Built only through [failure] and [crash], so every field has passed its sanitizer.
 */
class Report private constructor(
    val kind: String,
    val appVersion: String,
    val engineVersion: String?,
    val androidSdk: Int,
    val site: String?,
    val code: String?,
    val link: String?,
    val trace: String?,
    /** Where a failure happened: [OPTIONS] (reading the link) or [DOWNLOAD]. */
    val stage: String? = null,
) {
    fun json(): String = JSONObject().apply {
        put("kind", kind); put("app_version", appVersion); put("android_sdk", androidSdk)
        engineVersion?.let { put("engine_version", it) }
        site?.let { put("site", it) }
        code?.let { put("code", it) }
        link?.let { put("link", it) }
        trace?.let { put("trace", it) }
        stage?.let { put("stage", it) }
    }.toString()

    companion object {
        const val LINK_CAP = 2048
        const val TRACE_CAP = 12 * 1024
        private val label = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,39}")
        private val codeName = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
        private val hostName = Regex("[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63})+")
        /** Second-level labels under which the registrable name takes one more label (bbc.co.uk, site.com.mr). */
        private val secondLevel = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

        const val OPTIONS = "options"
        const val DOWNLOAD = "download"

        /** The exact link plus the site as its registrable name. */
        fun failure(link: String, code: String, appVersion: String, engineVersion: String?, androidSdk: Int, stage: String? = null) =
            Report("failure", version(appVersion) ?: "?", engineVersion?.let(::version), androidSdk, site(link),
                code.takeIf(codeName::matches), link(link), null, stage?.takeIf { it == OPTIONS || it == DOWNLOAD })

        /** [link] is the job's link when the crash happened during one. */
        fun crash(stored: String, appVersion: String, engineVersion: String?, androidSdk: Int, link: String? = null) =
            Report("crash", version(appVersion) ?: "?", engineVersion?.let(::version), androidSdk, link?.let(::site), null,
                link?.let(::link), trace(stored).takeIf { it.isNotEmpty() })

        private fun link(value: String) = value.takeIf { it.length <= LINK_CAP && web(it) }

        internal fun version(value: String) = value.takeIf(label::matches)

        internal fun site(link: String): String? = try {
            val host = URI(link).host?.lowercase()?.trimEnd('.')?.takeIf { hostName.matches(it) && !it.all { c -> c.isDigit() || c == '.' } }
            host?.split('.')?.let { labels ->
                val keep = if (labels.size >= 3 && labels[labels.size - 2] in secondLevel && labels.last().length == 2) 3 else 2
                labels.takeLast(keep).joinToString(".")
            }
        } catch (_: Exception) { null }

        private fun web(link: String) = try {
            URI(link).let { it.scheme in setOf("http", "https") && it.host != null && it.rawUserInfo == null }
        } catch (_: Exception) { false }

        private val traceLine = listOf(
            Regex("Timestamp: [0-9]{4}-[0-9]{2}-[0-9]{2}"),
            Regex("App: [A-Za-z0-9._+-]{1,40} \\([0-9]{1,10}\\)"),
            Regex("Android SDK: [0-9]{1,3}"),
            Regex("Thread: [A-Za-z0-9.\\[\\]-]{1,64}"),
            Regex("(Caused by: )?[A-Za-z0-9_$<>-]+(\\.[A-Za-z0-9_$<>-]+)*"),
            Regex("  at [A-Za-z0-9_$<>-]+(\\.[A-Za-z0-9_$<>-]+)*:-?[0-9]{1,7}"),
        )

        /**
         * Keeps only lines shaped like the store's own output (class names, frames, versions), then strips anything
         * URL- or path-like that still fits those shapes. Messages and free text never pass.
         */
        internal fun trace(text: String): String {
            val out = StringBuilder()
            for (line in text.lineSequence()) {
                val clean = sanitize(line)
                if (clean != line.trim() || traceLine.none { it.matches(line) }) continue
                if (out.length + line.length + 1 > TRACE_CAP) break
                out.append(line).append('\n')
            }
            return out.toString().trimEnd()
        }

        private val urlLike = Regex("(?i)\\b[a-z][a-z0-9+.-]*://\\S*|\\bwww\\.\\S+|\\b(content|file|data|intent|mailto):(?![-0-9])\\S*")
        private val pathLike = Regex("(^|\\s|[=(\"'])(~?/|[A-Za-z]:\\\\)\\S*|\\S+/\\S+")

        /** Removes URL-like and path-like text. */
        fun sanitize(text: String): String = text.replace(urlLike, "").replace(pathLike, "$1").trim()
    }
}
