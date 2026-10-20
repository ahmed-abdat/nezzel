package dev.ahmed.savlino.domain

/** Picture quality as people know it ("1080p"), from real frame dimensions only. */
object VideoQuality {
    private val STEPS = listOf(4320, 2160, 1440, 1080, 720, 540, 480, 360, 240, 144)

    /**
     * The short side, so a portrait 1080×1920 is 1080p; a frame wider than 16:9 (1920×816)
     * counts its width as 16:9 would, so cinema crops keep the label sites give them.
     * Snapped down to a common step (1152 → 1080p).
     */
    fun of(width: Int?, height: Int?): Int? {
        val h = height?.takeIf { it > 0 } ?: return null
        val w = width?.takeIf { it > 0 } ?: return step(h)
        return step(maxOf(minOf(w, h), maxOf(w, h) * 9 / 16))
    }

    fun step(side: Int): Int = STEPS.firstOrNull { side >= it } ?: side
}
