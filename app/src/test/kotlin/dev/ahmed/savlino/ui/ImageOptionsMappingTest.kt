package dev.ahmed.savlino.ui

import dev.ahmed.savlino.R
import dev.ahmed.savlino.engine.mediaimage.*
import org.junit.Test
import org.junit.Assert.*

class ImageOptionsMappingTest {
    @Test fun pageBudgetUsesReadFailureCopy() {
        for (reason in listOf(ImageFailure.BUDGET)) {
            assertEquals(Triple(R.string.read_failed_title, R.string.read_failed_body, true),
                imageErrorCopy(OptionsState.ImageError(reason, phase = ImagePhase.PAGE)))
        }
        assertEquals(R.string.image_large_title, imageErrorCopy(OptionsState.ImageError(ImageFailure.BUDGET)).first)
    }
    @Test fun networkFailureNeverClaimsTheDeviceIsOffline() {
        for (phase in ImagePhase.entries) {
            assertEquals(Triple(R.string.network_title, R.string.network_body, true),
                imageErrorCopy(OptionsState.ImageError(ImageFailure.NETWORK, phase = phase)))
        }
    }
    @Test fun linkedPostHasTruthfulTerminalCopy() {
        assertEquals(Triple(R.string.image_linked_elsewhere, null, false),
            imageErrorCopy(OptionsState.ImageError(ImageFailure.LINKED_ELSEWHERE, phase = ImagePhase.PAGE)))
    }
}
