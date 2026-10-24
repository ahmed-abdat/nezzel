package dev.ahmed.savlino

import dev.ahmed.savlino.locale.NezzelFormat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** "Saved on" dates: day and month apart, Latin digits, in every language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35])
class ShortDateTest {
    private val oct24 = java.time.LocalDate.of(2026, 10, 24).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun codes(value: String) = value.map { "U+%04X".format(it.code) }.toString()

    @Test fun dayAndMonthAreSeparatedInEveryLanguage() {
        for ((language, month) in listOf("en" to "Oct", "ar" to "أكتوبر", "fr" to "oct")) {
            val date = NezzelFormat.shortDate(oct24, language)
            assertTrue("$language $date ${codes(date)}", "24" in date && month in date)
            val day = date.indexOf("24"); val name = date.indexOf(month)
            val between = if (day < name) date.substring(day + 2, name) else date.substring(name + month.length, day)
            assertTrue("$language ${codes(date)}", between.any { it == ' ' || it == ' ' || it == ' ' })
        }
    }

    @Test fun arabicSentenceKeepsTheSpaceAroundTheDate() {
        val context = dev.ahmed.savlino.locale.AppLocale.localized(org.robolectric.RuntimeEnvironment.getApplication(), "ar")
        val sentence = context.getString(R.string.saved_before, NezzelFormat.shortDate(oct24, "ar"))
        assertFalse(codes(sentence), Regex("أكتوبر\\d|\\dأكتوبر").containsMatchIn(sentence))
    }
}
