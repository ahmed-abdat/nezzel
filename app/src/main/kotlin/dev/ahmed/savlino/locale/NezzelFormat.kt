package dev.ahmed.savlino.locale

import java.text.NumberFormat
import java.util.Locale

object NezzelFormat {
    /** Language tag with Latin digits in both languages. */
    fun numericTag(language: String): String = "$language-u-nu-latn"
    fun number(value: Double, language: String, decimals: Int): String =
        NumberFormat.getNumberInstance(Locale.forLanguageTag(numericTag(language))).apply {
            isGroupingUsed = false; minimumFractionDigits = 0; maximumFractionDigits = decimals
            // Units and numeric punctuation are Latin in both languages.
        }.format(value).replace('\u066b', '.')
    fun size(bytes: Long, language: String): String {
        require(bytes >= 0)
        val (value, unit) = when {
            bytes >= 1_000_000_000 -> bytes / 1_000_000_000.0 to "GB"
            bytes >= 1_000_000 -> bytes / 1_000_000.0 to "MB"
            bytes >= 1_000 -> bytes / 1_000.0 to "KB"
            else -> bytes.toDouble() to "B"
        }
        return "${number(value, language, 1)}\u00a0$unit" // NBSP: "5.6 MB" never breaks across lines
    }
    fun duration(seconds: Long): String {
        require(seconds >= 0)
        val s = (seconds % 60).toString().padStart(2, '0')
        val minutes = seconds / 60
        return if (minutes < 60) "$minutes:$s" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$s"
    }
    fun percent(value: Int, language: String): String = "${number(value.coerceIn(0, 100).toDouble(), language, 0)}%"
    fun count(value: Int, language: String): String = number(value.toDouble(), language, 0)
    /** "Oct 12" / "12 أكتوبر", with Latin digits in every language. */
    fun shortDate(epochMillis: Long, language: String): String {
        val locale = java.util.Locale.forLanguageTag(numericTag(language))
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
        return java.time.format.DateTimeFormatter.ofPattern(pattern, locale)
            .format(java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()))
    }
    fun isolate(value: String): String = "\u2066$value\u2069"
    /** First-strong isolate: the text takes the direction of its first strong character (names of unknown script). */
    fun firstStrong(value: String): String = "\u2068$value\u2069"
}
