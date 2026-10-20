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
}
