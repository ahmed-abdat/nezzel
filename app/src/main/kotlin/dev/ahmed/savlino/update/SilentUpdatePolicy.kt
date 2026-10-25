package dev.ahmed.savlino.update

internal fun canSilentlyUpdate(sdk: Int, automatic: Boolean, foreground: Boolean, busy: Boolean, verified: Boolean) =
    sdk >= 31 && automatic && !foreground && !busy && verified
