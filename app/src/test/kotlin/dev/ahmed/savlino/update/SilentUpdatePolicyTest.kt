package dev.ahmed.savlino.update

import org.junit.Assert.*
import org.junit.Test

class SilentUpdatePolicyTest {
    @Test fun onlyVerifiedAutomaticIdleBackgroundUpdatesOnAndroidTwelveOrLater() {
        assertTrue(canSilentlyUpdate(31, true, false, false, true))
        assertTrue(canSilentlyUpdate(35, true, false, false, true))
        assertFalse(canSilentlyUpdate(30, true, false, false, true))
        assertFalse(canSilentlyUpdate(35, false, false, false, true))
        assertFalse(canSilentlyUpdate(35, true, true, false, true))
        assertFalse(canSilentlyUpdate(35, true, false, true, true))
        assertFalse(canSilentlyUpdate(35, true, false, false, false))
    }
}
