package dev.ahmed.savlino.preview

import android.app.Application
import android.graphics.Color
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PreviewCacheStorageTest : PreviewCacheTestSupport() {
    @Test fun missingKeyDoesNotCreateAnEntryOrAnyArtwork() {
        val cache = open()
        assertNull(cache.get(key(cache), cache.token()))
        assertEquals(0, cache.usage().entries)
        assertTrue("usable empty local cache must initialize", cache.usage().available)
        assertDiskBounds(cache)
    }

    @Test fun diskHitSurvivesCloseAndRestartWithOnlyDisplayData() {
        val first = open()
        val saved = store(first)
        assertTrue("one committed entry must exist on disk", entry(saved).isFile)
        assertTrue(entry(saved).length() > 0)
        first.close()
        val restarted = open()
        val preview = hit(restarted, key(restarted))
        assertEquals("Display one", preview.title)
        assertEquals("قناة", preview.uploader)
        assertEquals(42L, preview.durationSeconds)
        assertNotNull(preview.artwork)
        assertEquals(Color.RED, preview.artwork!!.getPixel(0, 0))
        assertDiskBounds(restarted)
    }

    @Test fun stringsActuallyNormalizeControlsBidiWhitespaceAndKeepArabicEmojiJoiners() {
        val cache = open()
        val token = cache.token()
        val key = key(cache)
        assertTrue(cache.put(key, token, "  عنوان\u202E\n 👩‍💻  ", "قناة\u2066\u0000", 1))
        val preview = hit(cache, key)
        assertEquals("عنوان 👩‍💻", preview.title)
        assertEquals("قناة", preview.uploader)
        assertEquals(1L, preview.durationSeconds)
        cache.close()
        val restored = hit(open(), key)
        assertEquals("عنوان 👩‍💻", restored.title)
        assertEquals("قناة", restored.uploader)
        assertEquals(1L, restored.durationSeconds)
    }

    @Test fun titleAndUploaderBoundsDoNotSplitUnicodeCodePoints() {
        val cache = open()
        val token = cache.token()
        val key = key(cache)
        assertTrue(cache.put(key, token, "🌊".repeat(601), "أ".repeat(401)))
        val preview = hit(cache, key)
        assertEquals("🌊".repeat(300), preview.title)
        assertEquals("أ".repeat(120), preview.uploader)
        cache.close()
        assertEquals("🌊".repeat(300), hit(open(), key).title)
    }

    @Test fun invalidDurationsAreOmittedWhileMaximumPlausibleDurationSurvivesRestart() {
        val cache = open()
        for (duration in listOf(-1L, 0L, 2_678_401L, Long.MAX_VALUE)) {
            val key = key(cache, "$duration")
            assertTrue(cache.put(key, cache.token(), "Title", durationSeconds = duration))
            assertNull(hit(cache, key).durationSeconds)
        }
        val key = key(cache, "maximum")
        assertTrue(cache.put(key, cache.token(), "Title", durationSeconds = 2_678_400L))
        cache.close()
        val restarted = open()
        assertEquals(2_678_400L, hit(restarted, key).durationSeconds)
    }

    @Test fun urlAndCredentialShapedDisplayFieldsAreOmittedAndNeverPersisted() {
        val cache = open()
        val privateFields = listOf(
            "caption https://secret.example/image?sig=PRIVATE_SOURCE",
            "www.secret.example/item", "//secret.example/image", "user:pass@secret.example",
            "Cookie: session=PRIVATE_COOKIE", "Authorization: Bearer PRIVATE_BEARER",
            "password=PRIVATE_PASSWORD", "token=PRIVATE_TOKEN", "signature=PRIVATE_SIGNATURE",
        )
        for ((index, value) in privateFields.withIndex()) {
            val key = key(cache, "private-$index")
            assertTrue("invalid optional text should be dropped without losing safe artwork", cache.put(
                key, cache.token(), value, value, 42, image(),
            ))
            val preview = hit(cache, key)
            assertNull("PreviewMetadata.text alone does not reject URLs", preview.title)
            assertNull(preview.uploader)
            assertEquals(42L, preview.durationSeconds)
            assertNotNull(preview.artwork)
        }
        val persisted = ownedFiles().map { it.readBytes().toString(Charsets.UTF_8) }
        for (value in privateFields) assertFalse(persisted.any { it.contains(value) })
        assertFalse(persisted.any { it.contains("PRIVATE_") || it.contains("secret.example") })
    }

    @Test fun identityIsHashedWithoutSourceRecipeRuntimeOrPrivateExecutablePayloadsOnDisk() {
        val cache = open()
        val source = "https://private.example/item?source=SOURCE_SENTINEL&sig=SIGNED_SENTINEL"
        val key = cache.key(cache.token(), source, "RECIPE_SENTINEL", "RUNTIME_SENTINEL")
        assertTrue(cache.put(key, cache.token(), "Safe display", "Uploader", 42, image()))
        assertTrue("a real payload, not just an in-memory hit, must exist", entry(key).isFile)
        for (file in ownedFiles()) {
            assertTrue("all cache/control/temp names must be fixed hex", file.name.matches(Regex("[a-f0-9]{64}")))
            val persisted = file.readBytes().toString(Charsets.UTF_8)
            for (sentinel in listOf("SOURCE_SENTINEL", "SIGNED_SENTINEL", "RECIPE_SENTINEL", "RUNTIME_SENTINEL",
                "private.example", "Inspection", "thumbnailUrl", "formats", "command", "diagnostics")) {
                assertFalse("display cache persisted private/executable data: $sentinel", persisted.contains(sentinel))
            }
        }
    }

    @Test fun keySeparatesSourceRecipeRuntimeAndLengthDelimitedComponents() {
        val cache = open()
        val token = cache.token()
        val original = cache.key(token, "https://example.org/a", "ab", "c")
        assertTrue(cache.put(original, token, "Original"))
        for (other in listOf(
            cache.key(token, "https://example.org/b", "ab", "c"),
            cache.key(token, "https://example.org/a", "ab2", "c"),
            cache.key(token, "https://example.org/a", "ab", "c2"),
            cache.key(token, "https://example.org/a", "a", "bc"),
        )) {
            assertNotEquals(original.hex, other.hex)
            assertNull(cache.get(other, token))
        }
        assertEquals("Original", hit(cache, original).title)
    }

    @Test fun unsafeFilenameKeyCannotEscapeOwnedRoot() {
        val cache = open()
        val outside = File(temporary.root, "outside").apply { writeText("untouched") }
        for (invalid in listOf("../outside", "../" + "a".repeat(64), "F".repeat(64), "a".repeat(65), "")) {
            val key = PreviewCacheKey(invalid)
            assertFalse(cache.put(key, cache.token(), "Must not write"))
            assertNull(cache.get(key, cache.token()))
        }
        assertEquals("untouched", outside.readText())
        store(cache)
        assertEquals("untouched", outside.readText())
    }

    @Test fun processSharedAccessorReturnsTheSameLiveOwnerAndIsolatedSeamCannotOpenItsRoot() {
        val app = RuntimeEnvironment.getApplication()
        val first = track(PreviewCache.shared(app))
        val second = track(PreviewCache.shared(app))
        assertSame("shared owner must serialize generation across consumers", first, second)
        val attempt = runCatching { open(parent = app.cacheDir) }
        attempt.getOrNull()?.close()
        assertTrue("test seam must never create a second production owner", attempt.isFailure)
    }

    @Test fun secondLiveIsolatedOwnerIsRejectedAndCloseReleasesTheRootLease() {
        val first = open()
        val saved = store(first)
        val attempt = runCatching { open() }
        attempt.getOrNull()?.close()
        assertTrue("instance-local locking is insufficient", attempt.isFailure)
        assertEquals("Display one", hit(first, saved).title)
        first.close()
        assertEquals("Display one", hit(open(), saved).title)
    }

    @Test fun corruptEntryIsUnlinkedWithoutTouchingAnUnrelatedSibling() {
        val cache = open()
        val broken = store(cache, "broken")
        val good = store(cache, "good")
        cache.close()
        val bytes = entry(broken).readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x7f).toByte()
        entry(broken).writeBytes(bytes)
        val restarted = open()
        assertNull(restarted.get(broken, restarted.token()))
        assertFalse(entry(broken).exists())
        assertEquals("Display good", hit(restarted, good).title)
    }

    @Test fun tornEntryIsRemovedBeforeItCanReachADecoder() {
        val first = open()
        val saved = store(first)
        first.close()
        entry(saved).writeBytes(entry(saved).readBytes().copyOf(7))
        val codec = CountingPreviewCodec()
        val restarted = open(codec = codec)
        assertNull(restarted.get(saved, restarted.token()))
        assertFalse(entry(saved).exists())
        assertEquals("header validation must precede allocation/decode", 0, codec.decodes)
        store(restarted, "after-torn")
    }

    @Test fun sparseOversizedEntryIsRemovedWithoutAllocatingItsAdvertisedLength() {
        val cache = open()
        val saved = store(cache)
        cache.close()
        java.io.RandomAccessFile(entry(saved), "rw").use { it.setLength(3L * 1024 * 1024 * 1024) }
        val codec = CountingPreviewCodec()
        val restarted = open(codec = codec)
        assertNull(restarted.get(saved, restarted.token()))
        assertFalse(entry(saved).exists())
        assertEquals(0, codec.decodes)
        store(restarted, "after-oversize")
        assertDiskBounds(restarted)
    }

    @Test fun childSymlinkIsUnlinkedWithoutReadingOrDeletingItsTarget() {
        val cache = open()
        val saved = store(cache)
        cache.close()
        val outside = File(temporary.root, "outside-artwork").apply { writeText("private-original") }
        assertTrue(entry(saved).delete())
        Files.createSymbolicLink(entry(saved).toPath(), outside.toPath())
        val restarted = open()
        assertNull(restarted.get(saved, restarted.token()))
        assertFalse(Files.exists(entry(saved).toPath(), NOFOLLOW_LINKS))
        assertEquals("private-original", outside.readText())
        store(restarted, "after-symlink")
    }

    @Test fun rootSymlinkDisablesCacheAndLeavesOutsideDirectoryUntouched() {
        val outside = temporary.newFolder("outside-root")
        val marker = File(outside, "private").apply { writeText("untouched") }
        Files.createSymbolicLink(root.toPath(), outside.toPath())
        val cache = open()
        assertFalse(cache.usage().available)
        assertFalse(cache.put(key(cache), cache.token(), "No write", artwork = image()))
        assertFalse(cache.clear())
        assertEquals("untouched", marker.readText())
        assertEquals(listOf("private"), outside.list()!!.toList())
        cache.close()
        Files.delete(root.toPath()) // Test fixture removes only its link, never the target.
        val admissible = open()
        val saved = store(admissible, "root-positive")
        assertEquals("Display root-positive", hit(admissible, saved).title)
        assertTrue(entry(saved).isFile)
        assertEquals("untouched", marker.readText())
    }

    @Test fun wrongExistingRootPermissionsAreRejectedWithoutRepairOrDeletion() {
        assertTrue(root.mkdir())
        Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val marker = File(root, "unrecognized").apply { writeText("leave-me") }
        val cache = open()
        assertFalse(cache.usage().available)
        assertFalse(cache.put(key(cache), cache.token(), "No write"))
        assertEquals("leave-me", marker.readText())
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(root.toPath()))
        cache.close()
        // Only the fixture repairs its root. Cache code must never silently chmod an untrusted existing directory.
        Files.setPosixFilePermissions(root.toPath(), PosixFilePermissions.fromString("rwx------"))
        assertTrue(marker.delete())
        val admissible = open()
        val saved = store(admissible, "mode-positive")
        assertEquals("Display mode-positive", hit(admissible, saved).title)
        assertTrue(entry(saved).isFile)
    }

    @Test fun fileAlreadyExistsDuringMkdirUsesExistingRootVerificationWithoutRetryOrDeletion() {
        var attempts = 0
        val fileSystem = object : TemporaryPreviewFileSystem() {
            override fun createDirectory(path: java.nio.file.Path) {
                attempts++
                Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                throw java.nio.file.FileAlreadyExistsException(path.toString())
            }
        }
        val cache = open(fileSystem = fileSystem)
        assertTrue("FileAlreadyExists is an existing-root path, not an automatic failure", cache.usage().available)
        store(cache)
        assertEquals("do not retry or delete after a competing mkdir", 1, attempts)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root.toPath()))
    }

    @Test fun unexpectedOwnerPrincipalDisablesBeforePayloadIo() {
        val actual = Files.getOwner(temporary.root.toPath(), NOFOLLOW_LINKS)
        val different = java.nio.file.attribute.UserPrincipal { "definitely-not-${actual.name}" }
        val cache = track(PreviewCache.openIsolated(temporary.root, different, { now }, fileSystem = TemporaryPreviewFileSystem()))
        assertFalse(cache.usage().available)
        assertFalse(cache.put(key(cache), cache.token(), "No write", artwork = image()))
        assertFalse(root.exists())
        cache.close()
        val admissible = open()
        val saved = store(admissible, "owner-positive")
        assertEquals("Display owner-positive", hit(admissible, saved).title)
        assertTrue(entry(saved).isFile)
    }

    @Test fun nonemptyChildDirectoryDisablesInsteadOfRecursivelyDeletingOrUnderCounting() {
        val cache = open()
        store(cache)
        val nested = File(root, "unexpected-directory").apply { mkdir() }
        val marker = File(nested, "private-original").apply { writeText("untouched") }
        assertFalse(cache.put(key(cache, "later"), cache.token(), "No write"))
        assertFalse(cache.usage().available)
        assertEquals("untouched", marker.readText())
        assertFalse(cache.clear())
        assertEquals("untouched", marker.readText())
    }

    @Test fun emptyChildDirectoryAndUnknownFlatFileAreCleanedWithoutRecursion() {
        val cache = open()
        val saved = store(cache)
        val empty = File(root, "empty-child").apply { mkdir() }
        val junk = File(root, "not-an-entry").apply { writeText("junk") }
        assertEquals("Display one", hit(cache, saved).title)
        assertFalse(empty.exists())
        assertFalse(junk.exists())
        assertDiskBounds(cache)
    }

    @Test fun replacementOfOwnedRootDisablesExistingHandleAndNeverUsesReplacement() {
        val cache = open()
        val saved = store(cache)
        val retired = File(temporary.root, "retired-cache")
        Files.move(root.toPath(), retired.toPath())
        assertTrue(root.mkdir())
        val marker = File(root, "replacement-marker").apply { writeText("untouched") }
        assertNull(cache.get(saved, cache.token()))
        assertFalse(cache.put(key(cache, "new"), cache.token(), "No write"))
        assertFalse(cache.usage().available)
        assertEquals("untouched", marker.readText())
        assertTrue(File(retired, saved.hex).exists())
    }


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

    @Test fun diskBudgetEvictsActualEncodedArtworkEvenBelowThirtyTwoEntries() {
        assertEquals(16L * 1024 * 1024, PreviewCacheLimits().diskBytes)
        // Scale the bytes and real pixels together; eviction must still precede the unchanged 32-entry cap.
        val budget = 512L * 1024
        val cache = open(limits = PreviewCacheLimits(diskBytes = budget))
        val bitmap = image(96, 96, noisy = true)
        val oldest = store(cache, "0", bitmap)
        for (i in 1..30) {
            now++
            store(cache, "$i", bitmap)
            assertDiskBounds(cache, maxBytes = budget)
        }
        assertNull("real encoded bytes must evict before the entry cap", cache.get(oldest, cache.token()))
        assertTrue(cache.usage().entries < 31)
        assertTrue(ownedBytes() > budget / 2)
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


    @Test fun clearRemovesOnlyThisCacheAndPersistsNewGenerationAcrossRestart() {
        val jobs = temporary.newFile("jobs.db").apply { writeText("confirmed-job") }
        val staging = temporary.newFolder("staging")
        val partial = File(staging, "active-part").apply { writeText("active-transfer") }
        val published = temporary.newFile("published.mp4").apply { writeText("published-media") }
        val cache = open()
        val oldToken = cache.token()
        val oldKey = store(cache)
        val displayed = hit(cache, oldKey).artwork!!
        assertTrue(cache.clear())
        assertNull(cache.get(oldKey, oldToken))
        assertFalse(cache.put(oldKey, oldToken, "Late completion", artwork = image()))
        assertFalse(entry(oldKey).exists())
        assertEquals(0, cache.usage().entries)
        assertEquals(0, cache.usage().memoryEntries)
        val newKey = key(cache)
        assertNotEquals("clear generation must affect caller identity", oldKey.hex, newKey.hex)
        assertFalse(displayed.isRecycled)
        assertEquals(Color.RED, displayed.getPixel(0, 0))
        cache.close()
        val restarted = open()
        assertEquals(newKey.hex, key(restarted).hex)
        assertNull(restarted.get(oldKey, restarted.token()))
        store(restarted, "after-clear")
        assertEquals("confirmed-job", jobs.readText())
        assertEquals("active-transfer", partial.readText())
        assertEquals("published-media", published.readText())
        assertDiskBounds(restarted)
    }

    @Test fun clearBetweenStageForceAndCommitRejectsOldTokenWithoutRepopulating() {
        val fs = RecordingPreviewFileSystem(root)
        val cache = open(fileSystem = fs)
        val saved = store(cache)
        val token = cache.token()
        var cleared = false
        fs.afterNextFileForce = {
            assertTrue(cache.clear())
            cleared = true
        }
        assertFalse("in-flight old-generation write must fail after clear", cache.put(
            saved, token, "Late completion", artwork = image(),
        ))
        assertTrue("test must cross an actual file force, not simulate a stale token only", cleared)
        assertEquals(0, cache.usage().entries)
        assertFalse(entry(saved).exists())
        assertNull(cache.get(key(cache), cache.token()))
        cache.close()
        val restarted = open()
        assertNull(restarted.get(key(restarted), restarted.token()))
        store(restarted, "new-generation")
    }

    @Test fun tokenFromClosedOrDifferentOwnerCannotWriteEvenIfGenerationNumberMatches() {
        val first = open()
        val token = first.token()
        val saved = store(first)
        first.close()
        assertFalse(first.put(saved, token, "Closed owner"))
        val restarted = open()
        assertFalse(restarted.put(saved, token, "Different owner"))
        assertEquals("Display one", hit(restarted, saved).title)
    }

    @Test fun failedClearBeforeTempCreationInvalidatesLocalTokensAndReturnsFailureWithoutDurabilityClaim() {
        val fs = RecordingPreviewFileSystem(root)
        val cache = open(fileSystem = fs)
        val saved = store(cache)
        val token = cache.token()
        fs.rejectNewFile = true
        assertFalse(cache.clear())
        assertFalse(cache.usage().available)
        assertNull(cache.get(saved, token))
        assertFalse(cache.put(saved, token, "Late completion"))
        cache.close()
        val restarted = open()
        // A failed clear before a durable marker is not promised to survive restart.
        restarted.get(saved, restarted.token())?.let {
            it.artwork?.let(::remember)
            assertEquals("Display one", it.title)
        }
    }

    @Test fun clearFileForceFailureLeavesMarkerAndRestartFailsClosedBeforeCleanup() {
        val fs = RecordingPreviewFileSystem(root)
        val cache = open(fileSystem = fs)
        val saved = store(cache)
        val before = ownedFiles().map { it.name }.toSet()
        fs.failNextFileForce = true
        assertFalse("no success before durable clear barrier", cache.clear())
        assertFalse(cache.usage().available)
        val unfinished = ownedFiles().map { it.name }.toSet() - before
        assertTrue("real unfinished control marker must remain", unfinished.isNotEmpty())
        cache.close()
        val restarted = open()
        assertFalse(restarted.usage().available)
        assertNull(restarted.get(saved, restarted.token()))
        assertFalse(restarted.put(key(restarted, "new"), restarted.token(), "No write"))
        assertTrue("startup must not erase the marker then admit old data", unfinished.all { File(root, it).exists() })
    }

    @Test fun clearAtomicMoveFailureDoesNotReturnSuccessAndMarkerBlocksRestart() {
        val fs = RecordingPreviewFileSystem(root)
        val cache = open(fileSystem = fs)
        val saved = store(cache)
        fs.failNextMove = true
        assertFalse(cache.clear())
        assertFalse(cache.usage().available)
        cache.close()
        val restarted = open()
        assertFalse(restarted.usage().available)
        assertNull(restarted.get(saved, restarted.token()))
    }

    @Test fun clearDirectoryForceFailureNeverReportsSuccessOrServesOldGeneration() {
        val fs = RecordingPreviewFileSystem(root)
        val cache = open(fileSystem = fs)
        val saved = store(cache)
        val token = cache.token()
        fs.failNextDirectoryForce = true
        assertFalse(cache.clear())
        assertFalse(cache.usage().available)
        assertNull(cache.get(saved, token))
        assertFalse(cache.put(saved, token, "Late completion"))
        cache.close()
        val restarted = open()
        assertNull(restarted.get(saved, restarted.token()))
    }

    @Test fun readThatCannotDurablyAdvanceHighWaterDoesNotPublishAMemoryHit() {
        val fs = RecordingPreviewFileSystem(root)
        val cache = open(fileSystem = fs)
        val saved = store(cache)
        hit(cache, saved)
        now += 60_000
        fs.failNextDirectoryForce = true
        assertNull("memory hits also need durable highWater before publication", cache.get(saved, cache.token()))
        assertFalse(cache.usage().available)
    }

    @Test fun persistedGenerationIsRecheckedOnMemoryHitsAfterControlCorruption() {
        val cache = open()
        val saved = store(cache)
        hit(cache, saved)
        val controlFiles = ownedFiles().filterNot { it.name == saved.hex }
        assertEquals("single atomic control record expected", 1, controlFiles.size)
        val control = controlFiles.single()
        val bytes = control.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        control.writeBytes(bytes)
        assertNull("memory cannot bypass persisted control validation", cache.get(saved, cache.token()))
        assertFalse(cache.usage().available)
        cache.close()
        val restarted = open()
        assertFalse(restarted.usage().available)
        assertNull(restarted.get(saved, restarted.token()))
    }

    @Test fun missingControlWithEntriesDoesNotAdoptOldEntriesIntoAFreshGeneration() {
        val cache = open()
        val saved = store(cache)
        cache.close()
        val controlFiles = ownedFiles().filterNot { it.name == saved.hex }
        assertEquals(1, controlFiles.size)
        assertTrue(controlFiles.single().delete())
        val restarted = open()
        assertFalse(restarted.usage().available)
        assertNull(restarted.get(saved, restarted.token()))
        assertFalse(restarted.put(key(restarted, "new"), restarted.token(), "No write"))
    }

    @Test fun symlinkSwapAtActualChildOpenNeverReadsOrDeletesTheOutsideTarget() {
        val first = open()
        val saved = store(first)
        first.close()
        val outside = temporary.newFile("private-source").apply { writeText("never-read-or-delete") }
        var swapped = false
        val fileSystem = object : TemporaryPreviewFileSystem() {
            override fun openFile(directory: PreviewCacheDirectory, name: Path, options: Set<OpenOption>): SeekableByteChannel {
                if (!swapped && name.toString() == saved.hex && READ in options) {
                    swapped = true
                    Files.delete(entry(saved).toPath())
                    Files.createSymbolicLink(entry(saved).toPath(), outside.toPath())
                }
                return super.openFile(directory, name, options)
            }
        }
        val cache = open(fileSystem = fileSystem)
        assertNull(cache.get(saved, cache.token()))
        assertTrue("swap must occur at real open boundary", swapped)
        assertEquals("never-read-or-delete", outside.readText())
    }

    @Test fun unsupportedNonSecureProviderRefusesDiskWithoutPathnameFallback() {
        val outside = temporary.newFile("other-file").apply { writeText("untouched") }
        val fileSystem = object : PreviewCacheFileSystem() {
            override fun openRawDirectory(path: Path): DirectoryStream<Path> {
                val actual = Files.newDirectoryStream(path)
                // Deliberately exposes only DirectoryStream even on hosts with a secure implementation.
                return object : DirectoryStream<Path> {
                    override fun iterator(): MutableIterator<Path> = actual.iterator()
                    override fun close() = actual.close()
                }
            }
        }
        val cache = open(fileSystem = fileSystem)
        assertFalse(cache.usage().available)
        assertFalse(cache.put(key(cache), cache.token(), "No write", artwork = image()))
        assertFalse(root.exists())
        assertEquals("untouched", outside.readText())
        cache.close()
        val admissible = open()
        val saved = store(admissible, "provider-positive")
        assertEquals("Display provider-positive", hit(admissible, saved).title)
        assertTrue(entry(saved).isFile)
    }

    @Test fun unsupportedDirectoryForceDisablesBeforeAnyPayloadIsAdmitted() {
        val fs = RecordingPreviewFileSystem(root).apply { failNextDirectoryForce = true }
        val cache = open(fileSystem = fs)
        assertFalse(cache.usage().available)
        val key = key(cache)
        assertFalse(cache.put(key, cache.token(), "No write", artwork = image()))
        assertFalse(entry(key).exists())
        assertNull(cache.get(key, cache.token()))
        cache.close()
        val admissible = open()
        val saved = store(admissible, "force-positive")
        assertEquals("Display force-positive", hit(admissible, saved).title)
        assertTrue(entry(saved).isFile)
    }

    @Test fun productionOwnerFailsClosedOnActualHostWithoutSecureDirectoryStreams() {
        val secure = Files.newDirectoryStream(temporary.root.toPath()).use { it is SecureDirectoryStream<*> }
        org.junit.Assume.assumeFalse("this negative is specific to hosts without SDS; not Android acceptance", secure)
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val cache = track(PreviewCache.shared(app))
        assertFalse("production must not select portable test filesystem", cache.usage().available)
        val key = key(cache)
        assertFalse(cache.put(key, cache.token(), "No production disk fallback", artwork = image()))
        assertNull(cache.get(key, cache.token()))
        // Same generated payload with the explicitly TEST-only admissible backend, not an Android SDS positive.
        val admissible = open()
        val saved = store(admissible, "host-positive")
        assertEquals("Display host-positive", hit(admissible, saved).title)
        assertTrue(entry(saved).isFile)
    }


    @Test fun coldThenMemoryAndRestartDiskConsumersMeasureActualLoaderAndDecodeCounts() {
        var fixtureLoads = 0
        val codec = CountingPreviewCodec()
        val first = open(codec = codec)
        fun consume(cache: PreviewCache): CachedPreview {
            val token = cache.token()
            val key = key(cache, "fixture", token)
            cache.get(key, token)?.let { cached ->
                cached.artwork?.let(::remember)
                return cached
            }
            fixtureLoads++
            // Local generated pixels stand in for one already-authorized caller load. No URL is fetched.
            assertTrue(cache.put(key, token, "Fixture display", artwork = image(80, 60)))
            return hit(cache, key, token)
        }

        val cold = consume(first)
        assertEquals(Color.RED, cold.artwork!!.getPixel(0, 0))
        assertEquals(1, fixtureLoads)
        val coldDecodes = codec.decodes
        val coldBytes = ownedBytes()
        assertTrue("fixture must actually populate disk", coldBytes > 0)

        val warm = consume(first)
        assertEquals(Color.RED, warm.artwork!!.getPixel(0, 0))
        assertNotSame(cold.artwork, warm.artwork)
        assertEquals("warm memory hit avoids a second caller load", 1, fixtureLoads)
        assertEquals("warm memory hit avoids a second decode", coldDecodes, codec.decodes)
        first.close()

        val restarted = open(codec = codec)
        val diskWarm = consume(restarted)
        assertEquals(Color.RED, diskWarm.artwork!!.getPixel(0, 0))
        assertEquals("validated disk hit also avoids a second caller load", 1, fixtureLoads)
        assertEquals("restart decodes sampled disk artwork once", coldDecodes + 1, codec.decodes)
        consume(restarted)
        assertEquals(coldDecodes + 1, codec.decodes)
        assertDiskBounds(restarted)
        println("PreviewCache fixture: loads cold=1 memory=0 restart=0; decodes cold=$coldDecodes memory=0 restart=1; ownedBytes=$coldBytes/${ownedBytes()}; app/phone unmeasured")
    }
}
