package dev.ahmed.savlino.preview

import android.app.Application
import android.graphics.Color
import java.io.File
import java.nio.channels.SeekableByteChannel
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption.READ
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PreviewCacheClearTest : PreviewCacheTestSupport() {
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
}
