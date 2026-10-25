package dev.ahmed.savlino.tips

internal data class TipHistory(val lastOpen: Long = 0, val sent: Int = 0, val lastTip: Long = 0, val nextFeature: Int = 0,
    val ignored: Int = 0) {
    fun opened(now: Long, fromTip: Boolean = false) = copy(lastOpen = now, sent = 0,
        ignored = if (fromTip) 0 else (ignored + if (sent > 0) 1 else 0).coerceAtMost(2))
}

internal object TipsPolicy {
    private const val DAY = 86_400_000L
    fun due(history: TipHistory, now: Long, enabled: Boolean, allowed: Boolean, busy: Boolean): Boolean =
        enabled && allowed && !busy && history.ignored < 2 && history.lastOpen > 0 && history.sent in 0..1 &&
            now >= history.lastOpen && now - history.lastOpen >= (if (history.sent == 0) 3 else 7) * DAY &&
            (history.lastTip == 0L || now >= history.lastTip && now - history.lastTip >= 3 * DAY)

    fun feature(index: Int, whatsapp: Boolean): Int {
        val next = Math.floorMod(index, 6)
        return if (next == 3 && !whatsapp) 4 else next
    }
}
