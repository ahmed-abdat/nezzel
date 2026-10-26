package dev.ahmed.savlino.ui

import androidx.compose.runtime.Composable
import dev.ahmed.savlino.domain.Format
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.locale.NezzelFormat

/** Merging, extracting or converting changes the output, so even an exact input size only estimates the file. */
internal fun isEstimate(format: Format): Boolean = when (format.size) {
    is Size.Exact -> format.requiresMerge || format.conversion != null ||
        (!format.hasVideo && format.container == "m4a" && format.sourceFormatId != format.id)
    is Size.Estimated -> true
    Size.Unknown -> false
}

/** Owner 2026-10-25: a size shows only when it is exact; an estimate shows no size at all (no "≈"). */
internal fun exactBytes(format: Format): Long? = (format.size as? Size.Exact)?.bytes?.takeIf { !isEstimate(format) }

@Composable
internal fun sizeLabel(format: Format, language: String): String? =
    exactBytes(format)?.let { NezzelFormat.isolate(NezzelFormat.size(it, language)) }
