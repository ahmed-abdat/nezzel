package dev.ahmed.savlino.tips

import org.junit.Assert.*
import org.junit.Test

class TipsPolicyTest {
    private val day = 86_400_000L
    @Test fun dayThreeThenDaySevenAndNothingElseUntilOpen() {
        val state = TipHistory(lastOpen = day)
        assertFalse(TipsPolicy.due(state, 4 * day - 1, true, true, false))
        assertTrue(TipsPolicy.due(state, 4 * day, true, true, false))
        val first = state.copy(sent = 1, lastTip = 4 * day)
        assertFalse(TipsPolicy.due(first, 7 * day, true, true, false))
        assertTrue(TipsPolicy.due(first, 8 * day, true, true, false))
        assertFalse(TipsPolicy.due(first.copy(sent = 2), 100 * day, true, true, false))
    }
    @Test fun lateFirstTipStillLeavesThreeDaysBetweenTips() {
        val first = TipHistory(lastOpen = day, sent = 1, lastTip = 10 * day)
        assertFalse(TipsPolicy.due(first, 13 * day - 1, true, true, false))
        assertTrue(TipsPolicy.due(first, 13 * day, true, true, false))
    }
    @Test fun openingResetsStretchButKeepsGlobalSpacingAndRotation() {
        val opened = TipHistory(day, 2, 20 * day, 4).opened(21 * day)
        assertEquals(0, opened.sent)
        assertEquals(4, opened.nextFeature)
        assertEquals(20 * day, opened.lastTip)
        assertFalse(TipsPolicy.due(opened, 23 * day, true, true, false))
        assertTrue(TipsPolicy.due(opened, 24 * day, true, true, false))
    }
    @Test fun switchPermissionDownloadsAndUnknownOrFutureOpenSuppressTips() {
        val state = TipHistory(lastOpen = day)
        assertFalse(TipsPolicy.due(state, 9 * day, false, true, false))
        assertFalse(TipsPolicy.due(state, 9 * day, true, false, false))
        assertFalse(TipsPolicy.due(state, 9 * day, true, true, true))
        assertFalse(TipsPolicy.due(TipHistory(), 9 * day, true, true, false))
        assertFalse(TipsPolicy.due(state, 0, true, true, false))
    }
    @Test fun rotatesAllFeaturesAndSkipsUnavailableWhatsapp() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5), (0..5).map { TipsPolicy.feature(it, true) })
        assertEquals(0, TipsPolicy.feature(6, true))
        assertEquals(4, TipsPolicy.feature(3, false))
    }
}
