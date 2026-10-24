package dev.ahmed.savlino.domain

object BetterQuality {
    /**
     * Returned video formats whose picture tier is strictly above the saved one, highest first.
     * Audio-only saved files, and formats without known dimensions, are never "better".
     */
    fun above(formats: List<Format>, saved: Format): List<Format> {
        if (!saved.hasVideo) return emptyList()
        val savedTier = VideoQuality.of(saved.width, saved.height) ?: return emptyList()
        return formats.mapNotNull { format ->
            val tier = if (format.hasVideo) VideoQuality.of(format.width, format.height) else null
            if (tier != null && tier > savedTier) format to tier else null
        }.sortedByDescending { it.second }.map { it.first }
    }
}
