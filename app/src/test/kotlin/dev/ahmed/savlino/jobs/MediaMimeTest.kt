package dev.ahmed.savlino.jobs

import dev.ahmed.savlino.domain.Format
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// SDK coverage: DownloadCoordinator.kt:1125/1148 maps format/container to MIME/name without an API branch.
@Config(sdk = [29], manifest = Config.NONE)
class MediaMimeTest {
    @Test fun audioRoleAndKnownContainersHaveHonestMimeAndPublicationNames() {
        for ((container, wanted) in listOf("webm" to "audio/webm", "opus" to "audio/ogg", "ogg" to "audio/ogg",
            "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "flac" to "audio/flac")) {
            val format = Format(container, container, false, true)
            assertEquals(container, wanted, mimeTypeFor(format))
            assertEquals(if (container == "webm") "clip.weba" else "clip.$container", publicationName("clip.$container", format))
        }
        assertEquals("video/webm", mimeTypeFor(Format("v", "webm", true, true)))
        assertEquals("video/x-matroska", mimeTypeFor(Format("v", "mkv", true, true)))
        assertEquals("video/3gpp", mimeTypeFor(Format("v", "3gp", true, true)))
        assertEquals("audio/3gpp", mimeTypeFor(Format("a", "3gp", false, true)))
        assertEquals("video/x-flv", mimeTypeFor(Format("v", "flv", true, true)))
        assertEquals("application/octet-stream", mimeTypeFor(Format("v", "notaformat", true, true)))
    }
}
