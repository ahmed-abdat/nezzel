package dev.ahmed.savlino.domain

import java.text.Normalizer

/** Untrusted display metadata; never paths, selectors, or a source of new inspection requests. */
object PreviewMetadata {
    // Durations beyond 31 days are implausible for this single-video preview.
    const val MAX_DURATION_SECONDS = 31L * 24 * 60 * 60

    fun text(value: String?, maxCodePoints: Int): String? {
        if (value == null) return null
        require(maxCodePoints > 0)
        val clean = buildString {
            Normalizer.normalize(value, Normalizer.Form.NFC).codePoints().forEach { cp ->
                val type = Character.getType(cp)
                when {
                    Character.isWhitespace(cp) || type == Character.SPACE_SEPARATOR.toInt() -> append(' ')
                    type == Character.CONTROL.toInt() || type == Character.SURROGATE.toInt() -> Unit
                    type == Character.FORMAT.toInt() && cp != 0x200C && cp != 0x200D -> Unit
                    else -> appendCodePoint(cp)
                }
            }
        }.replace(Regex(" +"), " ").trim()
        if (clean.isEmpty()) return null
        return clean.substring(0, clean.offsetByCodePoints(0, minOf(maxCodePoints, clean.codePointCount(0, clean.length)))).trim()
    }

    fun thumbnail(value: String?): String? = value?.let {
        LinkInput.parse(it)?.rawUrl?.takeIf { parsed -> parsed == it }
    }
}
