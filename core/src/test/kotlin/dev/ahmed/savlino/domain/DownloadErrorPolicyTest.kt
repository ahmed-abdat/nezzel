package dev.ahmed.savlino.domain

import dev.ahmed.savlino.domain.DownloadError.*
import dev.ahmed.savlino.domain.FailureStage.*
import kotlin.test.*

class DownloadErrorPolicyTest {
    private data class Policy(val stage: FailureStage, val retryable: Boolean, val keepsSource: Boolean,
        val spendsResumeBudget: Boolean, val stopsSave: Boolean, val blocksReextraction: Boolean)

    private fun p(stage: FailureStage, retry: Boolean = false, keep: Boolean = false, budget: Boolean = false,
        stops: Boolean = false, local: Boolean = false) = Policy(stage, retry, keep, budget, stops, local)

    /** The whole table: a new DownloadError fails this test until its row is written here. */
    private val table = mapOf(
        InvalidInput to p(TRANSFER), Unsupported to p(TRANSFER), LoginRequired to p(TRANSFER), DrmProtected to p(TRANSFER),
        EngineUnavailable to p(TRANSFER, retry = true), NoFormats to p(TRANSFER),
        Network to p(TRANSFER, retry = true, budget = true, stops = true, local = true),
        Offline to p(TRANSFER, retry = true, budget = true, local = true),
        FormatExpired to p(TRANSFER),
        InsufficientSpace to p(TRANSFER, retry = true, stops = true, local = true),
        MergeFailed to p(CONVERT, retry = true, local = true),
        ConvertFailed to p(CONVERT, retry = true, keep = true, local = true),
        SaveFailed to p(SAVE, retry = true, local = true),
        FolderUnavailable to p(SAVE, retry = true, stops = true, local = true),
        ExecutionRestricted to p(TRANSFER, retry = true, local = true),
        Interrupted to p(TRANSFER, retry = true, stops = true, local = true),
        Failed to p(TRANSFER, retry = true),
        Cancelled to p(TRANSFER, local = true),
        NoVideo to p(TRANSFER), Unavailable to p(TRANSFER), NotAPost to p(TRANSFER), SourceRefused to p(TRANSFER),
        StoppedByBattery to p(TRANSFER, retry = true, keep = true, stops = true),
        ForegroundDenied to p(TRANSFER, retry = true, keep = true),
        PhotoPost to p(TRANSFER),
    )

    @Test fun everyErrorHasItsPolicy() {
        assertEquals(DownloadError.entries.toSet(), table.keys)
        for (error in DownloadError.entries) assertEquals(table.getValue(error),
            Policy(error.stage, error.retryable, error.keepsSource, error.spendsResumeBudget, error.stopsSave,
                error.blocksReextraction), error.name)
    }

    @Test fun keptSourceIsAlwaysRetryable() {
        for (error in DownloadError.entries.filter { it.keepsSource }) assertTrue(error.retryable, error.name)
    }

    @Test fun conversionFailureIsNotAMerge() {
        assertNotEquals(MergeFailed, ConvertFailed)
        assertFalse(MergeFailed.keepsSource)
        assertTrue(ConvertFailed.keepsSource)
    }

    @Test fun storedNamesReadBackAndUnknownNamesNeverCrash() {
        for (error in DownloadError.entries) assertEquals(error, DownloadError.fromName(error.name))
        assertEquals(Failed, DownloadError.fromName("SomeFutureError"))
        assertEquals(Failed, DownloadError.fromName(""))
    }
}
