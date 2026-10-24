package dev.ahmed.savlino.domain

import java.util.Locale
import kotlin.test.*

class SearchTextTest {
    @Test fun foldsArabicAndLatinSearchVariants() {
        val cases = listOf(
            Triple("محمد", "مُحَمَّد", true),
            Triple("محمـد", "محمد", true),
            Triple("احمد", "أحمد", true),
            Triple("احمد", "إحمد", true),
            Triple("احمد", "آحمد", true),
            Triple("احمد", "ٱحمد", true),
            Triple("مومن", "مؤمن", true),
            Triple("بيئه", "بيئة", true),
            Triple("مدرسه", "مدرسة", true),
            Triple("علي", "على", true),
            Triple("كي", "کی", true),
            Triple("0123456789", "٠١٢٣٤٥٦٧٨٩", true),
            Triple("0123456789", "۰۱۲۳۴۵۶۷۸۹", true),
            Triple("٣", "3", true),
            Triple("ete", "été", true),
            Triple("ÉTÉ", "ete", true),
            Triple("MP3", "mp3", true),
            Triple("أحمد", "ا\u0654حمد", true),
            Triple("ا\u0654حمد", "أحمد", true),
            Triple("لا", "ﻻ", true),
            Triple("الله", "اللّٰه\u06D6", true),
            Triple("محمد", "م\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069حمد", true),
            Triple("", "محمد", true),
            Triple(" \t\n", "محمد", true),
            Triple("َـ\u200F", "محمد", true),
            Triple("ahmed 2024", "2024 - Ahmed", true),
            Triple("  ahmed\t2024\n", "2024 - Ahmed", true),
            Triple("ahmed\u20032024", "2024 - Ahmed", true),
            Triple("ahmed 2025", "2024 - Ahmed", false),
            Triple("ة", "ت", false),
            Triple("محمد", "أحمد", false),
            Triple("ahmed", "أحمد", false),
            Triple("x", "", false),
        )
        cases.forEach { (query, haystack, expected) ->
            assertEquals(expected, SearchText.matches(SearchText.fold(haystack), query), "$query / $haystack")
        }
    }
    @Test fun foldProducesReusableSearchKey() {
        assertEquals("احمد مدرسه كي 123 ete mp3", SearchText.fold("أَحْمَد مـدرسة کی ١۲3 été MP3"))
    }
    @Test fun foldingIgnoresTurkishDefaultLocale() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("i", SearchText.fold("I"))
            assertTrue(SearchText.matches(SearchText.fold("FILE"), "file"))
            assertTrue(SearchText.matches(SearchText.fold("file"), "FILE"))
        } finally { Locale.setDefault(previous) }
    }
}
