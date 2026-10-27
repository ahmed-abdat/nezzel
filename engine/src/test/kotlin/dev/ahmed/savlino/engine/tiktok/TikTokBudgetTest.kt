package dev.ahmed.savlino.engine.tiktok

import dev.ahmed.savlino.engine.mediahttp.*
import org.junit.Test
import kotlin.test.*

class TikTokBudgetTest {
    @Test fun previewsShareSixteenMiBAndBoundResizeFallbacksWithoutChangingOtherProviders() {
        val budget = OperationBudget(MediaHttpOperation.TIKTOK_PAGE_AND_CAROUSEL)
        repeat(8) {
            val cap = budget.reserveImage(true) { true }
            assertEquals(2 * 1024 * 1024, cap)
            budget.consumed(cap, image = true)
            budget.releaseImage(cap)
        }
        assertEquals(0, budget.reserveImage(true) { true })
        val ordinary = OperationBudget(MediaHttpOperation.PAGE_AND_CAROUSEL)
        assertEquals(192 * 1024, ordinary.imageLimit(true))
        val token = OperationToken(MediaHttpOperation.TIKTOK_PAGE_AND_CAROUSEL, Any()) {}
        token.use {
            it.begin(MediaHttpPurpose.PAGE); it.endFetch()
            assertEquals(MediaHttpFailure.RESOURCE_LIMIT, assertFailsWith<MediaHttpException> { it.begin(MediaHttpPurpose.PAGE) }.reason)
            repeat(80) { _ -> it.begin(MediaHttpPurpose.IMAGE); it.endFetch() }
            assertEquals(MediaHttpFailure.RESOURCE_LIMIT, assertFailsWith<MediaHttpException> { it.begin(MediaHttpPurpose.IMAGE) }.reason)
        }
    }
    @Test fun signedHostSuffixesAreBoundedAndNeverMatchLookalikes() {
        for (host in listOf("p16-sign.example.tiktokcdn.com", "p19-sign.example.tiktokcdn-us.com", "p16-sign-va.tiktokcdn.com"))
            assertTrue(MediaHttpPolicy.isTikTokImage(assertNotNull(MediaHttpPolicy.parse("https://$host/synthetic.jpeg?sig=fake"))))
        for (host in listOf("tiktokcdn.com.evil.example", "evil-tiktokcdn.com", "p16-sign.evil.example", "tiktok.com"))
            assertFalse(MediaHttpPolicy.isTikTokImage(assertNotNull(MediaHttpPolicy.parse("https://$host/synthetic.jpeg"))))
    }
}
