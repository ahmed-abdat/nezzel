package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.storage.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TransferSpaceResumeTest {
    @Test fun stagedBytesReduceOnlyRemainingTransferAndKeepIntermediatePublicationHeadroom() {
        // Single: 100-40 + 100 publication. Merge/convert: 100-40 + 200 intermediate + 100 publication.
        val cases = listOf(
            Format("single", "mp4", true, true, size = Size.Exact(100)) to 160L,
            Format("merge", "mp4", true, true, size = Size.Exact(100), audioFormatId = "audio", requiresMerge = true) to 360L,
            Format("convert", "mp3", false, true, size = Size.Exact(100), conversion = AudioConversion.MP3_128) to 360L,
            Format("estimate", "mp4", true, true, size = Size.Estimated(100)) to 85L,
            Format("unknown", "mp4", true, true) to 1_073_741_784L,
        )
        for ((format, required) in cases) {
            val fits = SpaceBudget(File("stage"), File("public"), FreeSpace { required }, marginBytes = 0)
            TransferSpacePolicy.check(fits, format, 40)
            val short = SpaceBudget(File("stage"), File("public"), FreeSpace { required - 1 }, marginBytes = 0)
            try { TransferSpacePolicy.check(short, format, 40); fail("under-budget transfer admitted") }
            catch (failure: StorageException) { assertEquals(StorageError.InsufficientSpace, failure.error) }
        }
    }
    @Test fun moreStagedBytesThanBudgetNeverSubtractPublicationOrGoNegative() {
        val format = Format("single", "mp4", true, true, size = Size.Exact(100))
        TransferSpacePolicy.check(SpaceBudget(File("stage"), File("public"), FreeSpace { 100 }, marginBytes = 0), format, 500)
        try {
            TransferSpacePolicy.check(SpaceBudget(File("stage"), File("public"), FreeSpace { 99 }, marginBytes = 0), format, 500)
            fail("publication headroom was subtracted")
        } catch (failure: StorageException) { assertEquals(StorageError.InsufficientSpace, failure.error) }
    }
}
