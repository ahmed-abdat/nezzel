package dev.ahmed.savlino.locale

import java.text.NumberFormat
import java.util.Locale

object NezzelFormat {
    /** Language tag with Latin digits in every language. */
    fun numericTag(language: String): String = "$language-u-nu-latn"
    fun number(value: Double, language: String, decimals: Int): String =
        NumberFormat.getNumberInstance(Locale.forLanguageTag(numericTag(language))).apply {
            isGroupingUsed = false; minimumFractionDigits = 0; maximumFractionDigits = decimals
            // Keep Latin digits; retain each locale’s decimal separator.
        }.format(value).replace('\u066b', '.')
    fun size(bytes: Long, language: String): String {
        require(bytes >= 0)
        val (value, unit) = when {
            bytes >= 1_000_000_000 -> bytes / 1_000_000_000.0 to "G"
            bytes >= 1_000_000 -> bytes / 1_000_000.0 to "M"
            bytes >= 1_000 -> bytes / 1_000.0 to "K"
            else -> bytes.toDouble() to ""
        }
        val byte = if (language == "fr") "o" else "B" // French writes octets: Ko, Mo, Go
        return "${number(value, language, 1)}\u00a0$unit$byte" // NBSP: "5.6 MB" never breaks across lines
    }
    fun duration(seconds: Long): String {
        require(seconds >= 0)
        val s = (seconds % 60).toString().padStart(2, '0')
        val minutes = seconds / 60
        return if (minutes < 60) "$minutes:$s" else "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:$s"
    }
    /** Media metadata and playback use the nearest second, including minute/hour carries. */
    fun durationMillis(millis: Long): String {
        require(millis >= 0)
        return duration(millis / 1000 + if (millis % 1000 >= 500) 1 else 0)
    }
    fun percent(value: Int, language: String): String = "${number(value.coerceIn(0, 100).toDouble(), language, 0)}%"
    fun count(value: Int, language: String): String = number(value.toDouble(), language, 0)
    /** "Oct 12" / "12 أكتوبر", with Latin digits in every language. */
    fun shortDate(epochMillis: Long, language: String): String =
        android.icu.text.DateFormat.getInstanceForSkeleton("MMMd", android.icu.util.ULocale.forLanguageTag(numericTag(language)))
            .format(java.util.Date(epochMillis))
    fun shortDateTime(epochMillis: Long, language: String): String =
        java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM, java.time.format.FormatStyle.SHORT)
            .withLocale(Locale.forLanguageTag(numericTag(language)))
            .format(java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()))
    fun isolate(value: String): String = "\u2066$value\u2069"
    /** First-strong isolate: the text takes the direction of its first strong character (names of unknown script). */
    /** Short "when": the time for today, month and day this year, the full date only for older checks. Latin digits. */
    fun checkedAt(epochMillis: Long, language: String, now: Long = System.currentTimeMillis()): String {
        val zone = java.time.ZoneId.systemDefault()
        val day = java.time.Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val skeleton = when {
            day == today -> "jmm"
            day.year == today.year -> "MMMdjmm"
            else -> "yMMMdjmm"
        }
        return android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, android.icu.util.ULocale.forLanguageTag(numericTag(language)))
            .format(java.util.Date(epochMillis))
    }

    fun firstStrong(value: String): String = "\u2068$value\u2069"
}
