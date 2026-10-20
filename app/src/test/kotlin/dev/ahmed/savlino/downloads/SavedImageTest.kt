package dev.ahmed.savlino.downloads

import android.content.ContentUris
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SavedImageTest {
    private fun media(id: Long, mime: String, filename: String) = SavedMedia(
        uri = Uri.parse("content://media/external/downloads/$id"), filename = filename,
        title = filename.substringBeforeLast('.'), mimeType = mime, size = null, addedSeconds = 100,
        height = 720, bitrateKbps = 128, durationMillis = 84000,
    )
    private val mixed get() = listOf(media(1, "video/mp4", "Trip.mp4"), media(2, "audio/mpeg", "Song.mp3"),
        media(3, "image/jpeg", "Trip.jpg"), media(4, "image/png", "Map.png"), media(5, "image/webp", "Art.webp"))

    @Test fun videoFilterCannotIncludeStillImages() {
        assertEquals(listOf(1L), filterMedia(mixed, MediaFilter.VIDEO).map { ContentUris.parseId(it.uri) })
        assertEquals(listOf(2L), filterMedia(mixed, MediaFilter.AUDIO).map { ContentUris.parseId(it.uri) })
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), filterMedia(mixed, MediaFilter.ALL).map { ContentUris.parseId(it.uri) })
    }
    @Test fun imageFilterAndSearchReturnOnlyMatchingStillImageRows() {
        val images = MediaFilter.entries.singleOrNull { it.name == "IMAGE" }
        assertNotNull("An image filter must be selectable", images)
        assertEquals(listOf(3L, 4L, 5L), filterMedia(mixed, images!!).map { ContentUris.parseId(it.uri) })
        assertEquals(listOf(3L), filterMedia(mixed, images, "  tRiP  ").map { ContentUris.parseId(it.uri) })
        assertEquals(listOf(4L), filterMedia(mixed, images, ".png").map { ContentUris.parseId(it.uri) })
        assertTrue(filterMedia(mixed, images, "song").isEmpty())
    }
    @Test fun imageFormatsDoNotFallBackToMp4() {
        assertEquals(listOf("JPEG", "PNG", "WebP"), mixed.takeLast(3).map { it.format })
        assertFalse(mixed.last().audio)
    }
}
