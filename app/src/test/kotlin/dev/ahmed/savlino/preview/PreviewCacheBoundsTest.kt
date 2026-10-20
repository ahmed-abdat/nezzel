package dev.ahmed.savlino.preview

import android.app.Application
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PreviewCacheBoundsTest : PreviewCacheTestSupport() {
    @Test fun exactSixHourBoundaryExpiresAndSuccessfulReadDoesNotRenewWriteTime() {
        val cache = open()
        val saved = store(cache)
        now += 21_599_999
        assertEquals("Display one", hit(cache, saved).title)
        now++
        assertNull("six hours is expired even after a local hit one millisecond earlier", cache.get(saved, cache.token()))
        assertFalse(entry(saved).exists())
        assertEquals(0, cache.usage().memoryEntries)
    }

    @Test fun startupRemovesExpiredEntriesWithoutAnAccessOrTimer() {
        val first = open()
        val saved = store(first)
        first.close()
        now += 21_600_000
        val restarted = open()
        assertFalse("startup must perform TTL cleanup", entry(saved).exists())
        assertNull(restarted.get(saved, restarted.token()))
        assertEquals(0, restarted.usage().entries)
    }

    @Test fun ordinaryForwardClockAllowsWritesAndRefreshesPersistedHighWater() {
        val cache = open()
        val first = store(cache, "first")
        now += 60_000
        val second = store(cache, "second")
        assertEquals("Display first", hit(cache, first).title)
        assertEquals("Display second", hit(cache, second).title)
        assertDiskBounds(cache)
    }

    @Test fun backwardClockExpiresBothDiskAndMemoryInsteadOfExtendingTtl() {
        val cache = open()
        val saved = store(cache)
        now += 60_000
        hit(cache, saved)
        now--
        assertNull(cache.get(saved, cache.token()))
        assertFalse(entry(saved).exists())
        assertEquals(0, cache.usage().memoryEntries)
        assertFalse("no admission behind persisted highWater", cache.put(key(cache, "behind"), cache.token(), "No write"))
        now++
        store(cache, "caught-up")
    }

    @Test fun readHighWaterSurvivesRestartAndRejectsRollbackWhileWriteTimestampStillLooksFresh() {
        val first = open()
        val saved = store(first)
        now += 3_600_000
        hit(first, saved)
        first.close()
        now -= 1_800_000 // still newer than write; a write-time-only check would incorrectly serve it.
        val restarted = open()
        assertNull(restarted.get(saved, restarted.token()))
        assertFalse(entry(saved).exists())
        assertFalse(restarted.put(key(restarted, "behind"), restarted.token(), "No write"))
        now += 1_800_000
        store(restarted, "caught-up")
    }

    @Test fun persistedFutureWriteIsNeverShownAfterClockRollbackAcrossRestart() {
        val first = open()
        val saved = store(first)
        first.close()
        now -= 1
        val restarted = open()
        assertNull(restarted.get(saved, restarted.token()))
        assertFalse(entry(saved).exists())
        assertEquals(0, restarted.usage().entries)
    }

    @Test fun nonpositiveAndOverflowClocksCannotAdmitOrExtendEntries() {
        val cache = open()
        val saved = store(cache)
        for (invalid in listOf(0L, -1L, Long.MIN_VALUE)) {
            now = invalid
            assertNull(cache.get(saved, cache.token()))
            assertFalse(cache.put(key(cache, "invalid-$invalid"), cache.token(), "No write"))
        }
        cache.close()
        now = 1_800_000_000_000L
        val restarted = open()
        store(restarted, "valid-again")
        now = Long.MAX_VALUE
        assertNull("subtraction must not overflow into a fresh age", restarted.get(key(restarted, "valid-again"), restarted.token()))
    }

    @Test fun entryLimitEvictsLeastRecentlySuccessfullyAccessedAndKeepsOrderAcrossRestart() {
        val cache = open()
        val keys = (0 until 32).map { store(cache, "$it", artwork = null).also { now++ } }
        hit(cache, keys[0])
        now++
        store(cache, "32", artwork = null)
        assertNull(cache.get(keys[1], cache.token()))
        assertTrue(entry(keys[0]).exists())
        assertDiskBounds(cache)
        cache.close()
        val restarted = open()
        now++
        store(restarted, "33", artwork = null)
        assertNull("restart must preserve access order, not fall back to write order", restarted.get(keys[2], restarted.token()))
        assertEquals("Display 0", hit(restarted, keys[0]).title)
        assertDiskBounds(restarted)
    }

    @Test fun aMissDoesNotPromoteSomeOtherEntryInLruOrder() {
        val cache = open(limits = PreviewCacheLimits(entries = 2))
        val first = store(cache, "first", artwork = null)
        now++
        val second = store(cache, "second", artwork = null)
        assertNull(cache.get(key(cache, "missing"), cache.token()))
        now++
        store(cache, "third", artwork = null)
        assertNull(cache.get(first, cache.token()))
        assertEquals("Display second", hit(cache, second).title)
    }

    @Test fun defaultDiskBudgetEvictsActualEncodedArtworkEvenBelowThirtyTwoEntries() {
        val cache = open()
        val bitmap = image(480, 480, noisy = true)
        val oldest = store(cache, "0", bitmap)
        for (i in 1..30) {
            now++
            store(cache, "$i", bitmap)
            assertDiskBounds(cache)
        }
        assertNull("real encoded bytes must evict before the entry cap", cache.get(oldest, cache.token()))
        assertTrue(cache.usage().entries < 31)
        assertTrue(ownedBytes() > 8L * 1024 * 1024)
    }

    @Test fun peakBudgetCountsLiveEntryIncomingTempAndControlTempBeforeCreation() {
        val fs = RecordingPreviewFileSystem(root)
        val cache = open(limits = PreviewCacheLimits(diskBytes = 65_536), fileSystem = fs)
        val bitmap = image(96, 96, noisy = true)
        val first = store(cache, "first", bitmap)
        now++
        store(cache, "second", bitmap)
        assertNull(cache.get(first, cache.token()))
        assertTrue("actual staging observations required", fs.observedBytes.isNotEmpty())
        assertTrue("a write crossed the cap before rename/eviction", fs.observedBytes.all { it <= 65_536 })
        assertDiskBounds(cache, maxBytes = 65_536)
    }

    @Test fun replacementDoesNotDoubleCountIncomingPayloadOrExceedPeakWithOldPayload() {
        val fs = RecordingPreviewFileSystem(root)
        val cache = open(limits = PreviewCacheLimits(diskBytes = 100_000), fileSystem = fs)
        val bitmap = image(96, 96, noisy = true)
        val saved = store(cache, "same", bitmap)
        assertTrue(cache.put(saved, cache.token(), "Replacement", artwork = bitmap))
        assertEquals("Replacement", hit(cache, saved).title)
        assertEquals(1, cache.usage().entries)
        assertTrue(fs.observedBytes.all { it <= 100_000 })
        assertDiskBounds(cache, maxBytes = 100_000)
    }

    @Test fun impossiblePeakWriteIsRejectedBeforeEvictingAnUnrelatedValidEntry() {
        val cache = open(limits = PreviewCacheLimits(diskBytes = 4096))
        val good = store(cache, "good", artwork = null)
        assertFalse(cache.put(key(cache, "impossible"), cache.token(), "Too big", artwork = image(96, 96, noisy = true)))
        assertEquals("Display good", hit(cache, good).title)
        assertDiskBounds(cache, maxBytes = 4096)
    }

    @Test fun metadataEntryLimitRejectsWithoutDeletingAnUnrelatedValidEntry() {
        val cache = open(limits = PreviewCacheLimits(metadataEntryBytes = 512))
        val good = store(cache, "good", artwork = null)
        assertFalse(cache.put(key(cache, "large"), cache.token(), "🌊".repeat(300), "🌊".repeat(120)))
        assertEquals("Display good", hit(cache, good).title)
        assertEquals(1, cache.usage().entries)
        assertDiskBounds(cache)
    }

    @Test fun totalMetadataLimitEvictsBeforeThirtyTwoEntriesAndDoesNotChargeArtworkAsMetadata() {
        val cache = open(limits = PreviewCacheLimits(metadataTotalBytes = 4096))
        val first = store(cache, "0", artwork = null, title = "🌊".repeat(300))
        for (i in 1..7) {
            now++
            store(cache, "$i", artwork = null, title = "🌊".repeat(300))
            assertTrue(cache.usage().metadataBytes <= 4096)
        }
        assertNull(cache.get(first, cache.token()))
        assertTrue(cache.usage().entries < 8)
        assertDiskBounds(cache)
    }

    @Test fun encodedArtworkLimitRejectsBeforeItCanCreateAnOversizedEntry() {
        val cache = open(limits = PreviewCacheLimits(artworkEncodedBytes = 256))
        val good = store(cache, "good", artwork = null)
        assertFalse(cache.put(key(cache, "large"), cache.token(), "Too big", artwork = image(96, 96, noisy = true)))
        assertEquals("Display good", hit(cache, good).title)
        assertEquals(1, cache.usage().entries)
        assertDiskBounds(cache)
    }

    @Test fun artworkIsSampledInBothAxesAndOnlySampledPixelsSurviveRestart() {
        val cache = open()
        val original = image(1920, 1080)
        val saved = store(cache, artwork = original)
        val first = hit(cache, saved).artwork!!
        assertTrue(first.width <= 480 && first.height <= 480)
        assertEquals(16.0 / 9.0, first.width.toDouble() / first.height, 0.03)
        assertTrue(entry(saved).length() <= 2L * 1024 * 1024 + 16 * 1024)
        cache.close()
        val restarted = open()
        val second = hit(restarted, saved).artwork!!
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        assertFalse("caller original never belongs to cache recycling", original.isRecycled)
    }

    @Test fun unusuallyTallArtworkCannotHideAnUnboundedSecondAxis() {
        val cache = open()
        val saved = store(cache, artwork = image(8, 4096))
        val bitmap = hit(cache, saved).artwork!!
        assertTrue(bitmap.width in 1..480)
        assertTrue(bitmap.height in 1..480)
        assertDiskBounds(cache)
    }

    @Test fun memoryEntryCapEvictsRealDecodedImagesAndWarmHitsAvoidDecode() {
        val codec = CountingPreviewCodec()
        val cache = open(codec = codec)
        val keys = (0..8).map { store(cache, "$it") }
        assertEquals(8, cache.usage().memoryEntries)
        assertTrue(cache.usage().memoryBytes <= 4L * 1024 * 1024)
        val beforeWarm = codec.decodes
        assertEquals(Color.RED, hit(cache, keys.last()).artwork!!.getPixel(0, 0))
        assertEquals(beforeWarm, codec.decodes)
        assertEquals(Color.RED, hit(cache, keys.first()).artwork!!.getPixel(0, 0))
        assertEquals("evicted artwork must be decoded from actual disk once", beforeWarm + 1, codec.decodes)
        assertEquals(8, cache.usage().memoryEntries)
    }

    @Test fun memoryBytesUseMeasuredAllocationAndEvictBelowTheEntryCap() {
        val codec = CountingPreviewCodec()
        val cache = open(codec = codec)
        val source = image(480, 480)
        val keys = (0..5).map { store(cache, "$it", source) }
        val usage = cache.usage()
        assertTrue(usage.memoryEntries in 1..4)
        assertTrue(usage.memoryBytes in 1..4L * 1024 * 1024)
        val latest = hit(cache, keys.last()).artwork!!
        assertEquals(latest.allocationByteCount.toLong() * usage.memoryEntries, usage.memoryBytes)
        val before = codec.decodes
        hit(cache, keys.first())
        assertEquals(before + 1, codec.decodes)
    }

    @Test fun callerBitmapMutationAndRecycleAfterAdmitCannotPoisonCacheOwnedPixels() {
        val cache = open()
        val source = image()
        val saved = store(cache, artwork = source)
        source.eraseColor(Color.BLUE)
        source.recycle()
        assertEquals(Color.RED, hit(cache, saved).artwork!!.getPixel(0, 0))
        cache.close()
        assertEquals(Color.RED, hit(open(), saved).artwork!!.getPixel(0, 0))
    }

    @Test fun everyGetOwnsACopyAndEvictionClearCloseCannotRecycleAPublishedSnapshot() {
        val cache = open(limits = PreviewCacheLimits(memoryEntries = 1))
        val source = image()
        val saved = store(cache, artwork = source)
        val first = hit(cache, saved).artwork!!
        val second = hit(cache, saved).artwork!!
        assertNotSame(source, first)
        assertNotSame(first, second)
        assertNotSame(first, source)
        second.recycle()
        assertEquals(Color.RED, first.getPixel(0, 0))
        assertEquals(Color.RED, hit(cache, saved).artwork!!.getPixel(0, 0))
        store(cache, "evict", image())
        assertFalse(first.isRecycled)
        assertTrue(cache.clear())
        cache.close()
        assertFalse(first.isRecycled)
        assertFalse(source.isRecycled)
        assertEquals(Color.RED, first.getPixel(0, 0))
    }

    @Test fun snapshotMutationCannotChangeTheNextConsumerOrThePersistedImage() {
        val cache = open()
        val saved = store(cache)
        val snapshot = hit(cache, saved).artwork!!
        if (snapshot.isMutable) snapshot.eraseColor(Color.BLUE)
        else snapshot.recycle()
        assertEquals(Color.RED, hit(cache, saved).artwork!!.getPixel(0, 0))
        cache.close()
        assertEquals(Color.RED, hit(open(), saved).artwork!!.getPixel(0, 0))
    }
}
