package dev.ahmed.savlino.engine.mediahttp

import org.junit.Test
import kotlin.test.*

class CarouselBudgetTest {
    @Test fun linkedInCarouselAllowsAllSixtyFourCandidatesWithinSharedLimits() {
        OperationToken(MediaHttpOperation.LINKEDIN_PAGE_AND_CAROUSEL, Any(), retired = {}).use { token ->
            token.begin(MediaHttpPurpose.PAGE); token.endFetch()
            assertEquals(MediaHttpFailure.RESOURCE_LIMIT,
                assertFailsWith<MediaHttpException> { token.begin(MediaHttpPurpose.PAGE) }.reason)
            repeat(64) { token.begin(MediaHttpPurpose.IMAGE); token.endFetch() }
            assertEquals(MediaHttpFailure.RESOURCE_LIMIT,
                assertFailsWith<MediaHttpException> { token.begin(MediaHttpPurpose.IMAGE) }.reason)
            repeat(68) { token.budget.request() }
            assertEquals(MediaHttpFailure.REQUEST_LIMIT,
                assertFailsWith<MediaHttpException> { token.budget.request() }.reason)
            token.budget.consumed(6 * 1024 * 1024) // Page bytes do not consume the image budget.
            assertEquals(2 * 1024 * 1024, token.budget.imageLimit())
            token.budget.consumed(2 * 1024 * 1024, image = true)
            assertEquals(0, token.budget.imageLimit())
        }
    }

    @Test fun carouselSharesImageBytesAndLimitsImagesAndRequests() {
        val token = OperationToken(MediaHttpOperation.PAGE_AND_CAROUSEL, Any(), retired = {})
        token.use {
            it.begin(MediaHttpPurpose.PAGE); it.endFetch()
            repeat(20) { _ -> it.begin(MediaHttpPurpose.IMAGE); it.endFetch() }
            assertEquals(MediaHttpFailure.RESOURCE_LIMIT,
                assertFailsWith<MediaHttpException> { it.begin(MediaHttpPurpose.IMAGE) }.reason)
            repeat(24) { _ -> it.budget.request() }
            assertEquals(MediaHttpFailure.REQUEST_LIMIT,
                assertFailsWith<MediaHttpException> { it.budget.request() }.reason)
            it.budget.consumed(1024 * 1024) // Page bytes do not steal the existing image allowance.
            it.budget.consumed(512 * 1024, image = true)
            assertEquals(1536 * 1024, it.budget.imageLimit())
            it.budget.consumed(1536 * 1024, image = true)
            assertEquals(0, it.budget.imageLimit())
        }
    }

    @Test fun tenMaximumThumbnailBodiesFitWithoutOneOriginalSpendingTheBudget() {
        OperationToken(MediaHttpOperation.PAGE_AND_CAROUSEL, Any(), retired = {}).use { token ->
            repeat(10) {
                assertEquals(192 * 1024, token.budget.imageLimit(thumbnail = true))
                token.budget.consumed(192 * 1024, image = true)
            }
            assertEquals(128 * 1024, token.budget.imageLimit(thumbnail = true))
            token.budget.consumed(128 * 1024, image = true)
            assertEquals(0, token.budget.imageLimit(thumbnail = true))
        }
    }

    @Test fun twoPageCarouselKeepsTheSameImageAndRequestBudgets() {
        OperationToken(MediaHttpOperation.PAGES_AND_CAROUSEL, Any(), retired = {}).use {
            repeat(2) { _ -> it.begin(MediaHttpPurpose.PAGE); it.endFetch() }
            assertEquals(MediaHttpFailure.RESOURCE_LIMIT,
                assertFailsWith<MediaHttpException> { it.begin(MediaHttpPurpose.PAGE) }.reason)
            repeat(20) { _ -> it.begin(MediaHttpPurpose.IMAGE); it.endFetch() }
            assertEquals(MediaHttpFailure.RESOURCE_LIMIT,
                assertFailsWith<MediaHttpException> { it.begin(MediaHttpPurpose.IMAGE) }.reason)
            repeat(24) { _ -> it.budget.request() }
            assertEquals(MediaHttpFailure.REQUEST_LIMIT,
                assertFailsWith<MediaHttpException> { it.budget.request() }.reason)
            it.budget.consumed(2 * 1024 * 1024, image = true)
            assertEquals(0, it.budget.imageLimit())
        }
    }

    @Test fun ordinaryInspectionStillPermitsOnlyOneImage() {
        OperationToken(MediaHttpOperation.PAGE_AND_IMAGE, Any(), retired = {}).use {
            it.begin(MediaHttpPurpose.IMAGE); it.endFetch()
            assertEquals(MediaHttpFailure.RESOURCE_LIMIT,
                assertFailsWith<MediaHttpException> { it.begin(MediaHttpPurpose.IMAGE) }.reason)
        }
    }
}
