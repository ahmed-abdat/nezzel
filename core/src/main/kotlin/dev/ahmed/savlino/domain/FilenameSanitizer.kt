package dev.ahmed.savlino.domain

import java.text.BreakIterator
import java.text.Normalizer
import java.util.Locale

object FilenameSanitizer {
    /** A display name only. Job directories must always be independently generated, never title-derived. */
    fun sanitize(title: String, extension: String): String {
        require(extension.matches(Regex("[a-zA-Z0-9]{1,10}")))
        val suffix = "." + extension.lowercase(java.util.Locale.ROOT)
        val cleaned = buildString {
            Normalizer.normalize(title, Normalizer.Form.NFC).codePoints().forEach { cp ->
                val type = Character.getType(cp)
                when {
                    type == Character.SPACE_SEPARATOR.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
                        type == Character.PARAGRAPH_SEPARATOR.toInt() -> append(' ')
                    type == Character.CONTROL.toInt() || type == Character.SURROGATE.toInt() -> Unit
                    // Preserve script/emoji joiners; strip bidi controls and other unsafe format controls.
                    type == Character.FORMAT.toInt() && cp != 0x200C && cp != 0x200D -> Unit
                    cp !in "/\\:*?\"<>|".map { it.code } -> appendCodePoint(cp)
                }
            }
        }.trim(' ', '.').replace(Regex("\\.{2,}"), "_").replace(Regex("\\s+"), " ").trim(' ', '.')
        val stem = cleaned.ifEmpty { "video" }
        val bounded = StringBuilder()
        var bytes = 0
        var codePoints = 0
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(stem) }
        var start = boundaries.first()
        var end = boundaries.next()
        while (end != BreakIterator.DONE) {
            val grapheme = stem.substring(start, end)
            val length = grapheme.toByteArray(Charsets.UTF_8).size
            val count = grapheme.codePointCount(0, grapheme.length)
            if (bytes + length + suffix.length > 240 || codePoints + count > 80) break
            bounded.append(grapheme)
            bytes += length
            codePoints += count
            start = end
            end = boundaries.next()
        }
        var shortened = bounded.toString()
        // Prefer a complete word when the cap lands inside one; a single long word still gets a safe prefix.
        if (start < stem.length && stem[start] != ' ' && !shortened.endsWith(' '))
            shortened = shortened.substringBeforeLast(' ', shortened)
        return shortened.trimEnd(' ', '.').ifEmpty { "video" } + suffix
    }
}
