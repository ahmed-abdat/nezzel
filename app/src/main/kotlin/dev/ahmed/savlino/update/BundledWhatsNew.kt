package dev.ahmed.savlino.update

import android.content.res.AssetManager
import java.io.IOException

/** Offline notes for this APK, generated from fastlane's localized changelogs. */
internal object BundledWhatsNew {
    fun read(assets: AssetManager): WhatsNew? {
        fun text(language: String): String? = try {
            assets.open("whats-new/$language.txt").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: IOException) { null }
        return parse(text("en"), text("ar"), text("fr"))
    }

    internal fun parse(english: String?, arabic: String?, french: String? = null): WhatsNew? {
        fun localized(text: String?): Pair<String, List<String>>? {
            val lines = text?.lineSequence()?.toList() ?: return null
            val version = lines.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val notes = lines.drop(1).map { it.trimStart().removePrefix("• ").trim() }.filter { it.isNotEmpty() }
            return if (notes.isEmpty()) null else version to notes
        }
        val en = localized(english) ?: return null
        val ar = localized(arabic) ?: return null
        if (en.first != ar.first) return null
        val fr = localized(french)?.takeIf { it.first == en.first }
        return WhatsNew(en.first, en.second, ar.second, fr?.second.orEmpty())
    }
}
