package dev.ahmed.savlino.domain

import java.text.Normalizer
import java.util.Locale

object SearchText {
    private val ignored = Regex("[\\p{Mn}\\p{Cf}\u0640]")
    private val whitespace = Regex("(?U)\\s+")

    fun fold(s: String): String {
        val normalized = Normalizer.normalize(Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return buildString {
            for (char in ignored.replace(normalized, "")) append(when (char) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ؤ' -> 'و'
                'ئ', 'ى', 'ی' -> 'ي'
                'ة' -> 'ه'
                'ک' -> 'ك'
                in '٠'..'٩' -> '0' + (char - '٠')
                in '۰'..'۹' -> '0' + (char - '۰')
                else -> char
            })
        }
    }

    fun matches(foldedHaystack: String, query: String): Boolean =
        whitespace.splitToSequence(fold(query)).all { it.isEmpty() || foldedHaystack.contains(it) }
}
