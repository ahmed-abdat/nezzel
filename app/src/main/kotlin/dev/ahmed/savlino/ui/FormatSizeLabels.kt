package dev.ahmed.savlino.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Format
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.locale.NezzelFormat

/** Merging, extracting or converting changes the output, so even an exact input size only estimates the file. */
internal fun isEstimate(format: Format, roughBytes: Long? = null): Boolean = when (format.size) {
    is Size.Exact -> format.requiresMerge || format.conversion != null ||
        (!format.hasVideo && format.container == "m4a" && format.sourceFormatId != format.id)
    is Size.Estimated -> true
    Size.Unknown -> roughBytes != null
}

@Composable
internal fun sizeLabel(format: Format, language: String, roughBytes: Long? = null): String {
    val bytes = when (val size = format.size) {
        is Size.Exact -> size.bytes
        is Size.Estimated -> size.bytes
        Size.Unknown -> roughBytes ?: return stringResource(R.string.unknown_size)
    }
    val amount = NezzelFormat.size(bytes, language)
    // "≈ 4.7 MB" is one LTR token; unisolated, Arabic layout moves the ≈ after the number.
    return NezzelFormat.isolate(when {
        format.size == Size.Unknown -> stringResource(R.string.rough_size, amount)
        isEstimate(format) -> stringResource(R.string.estimated_size, amount)
        else -> amount
    })
}

/** The size as TalkBack should say it: an estimate is spoken as a word, not as "≈". */
@Composable
internal fun spokenSize(format: Format, language: String, roughBytes: Long? = null): String {
    val size = sizeLabel(format, language, roughBytes)
    return if (isEstimate(format, roughBytes)) stringResource(R.string.size_estimate_a11y,
        NezzelFormat.isolate(size.filter { it != '\u2066' && it != '\u2069' }.removePrefix("≈").trim())) else size
}
