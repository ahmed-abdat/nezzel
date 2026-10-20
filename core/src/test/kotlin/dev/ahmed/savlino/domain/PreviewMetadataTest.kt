package dev.ahmed.savlino.domain

import kotlin.test.*

class PreviewMetadataTest {
    private val source = LinkRevision(LinkInput.parse("https://example.org/video")!!, 1)
    private val video = Format("18", "mp4", true, true)
    private fun filename(title: String?) = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job",
        Inspection(source, "v1", listOf(video), title = title), source, "18", "Nezzel")).command.filename

    @Test fun confirmedFilenameUsesArabicEmojiTitleAndBoundsUtf8() {
        assertEquals("رحلة 🌊 رائعة.mp4", filename("رحلة 🌊 رائعة"))
        val long = filename("رحلة 🌊 ".repeat(100))
        assertTrue(long.startsWith("رحلة 🌊"))
        assertTrue(long.toByteArray(Charsets.UTF_8).size <= 240)
        assertFalse(long.contains('\uFFFD'))
        assertEquals("Nezzel.mp4", filename(null))
        assertEquals("Nezzel.mp4", filename("  "))
    }
    @Test fun metadataStripsUnsafeControlsRetainsJoinersAndBoundsWithoutSplittingSurrogates() {
        val inspection = Inspection(source, "v1", emptyList(), title = "  عنوان\u202E\n 👩‍💻  ",
            uploader = "قناة\u2066\u0000", durationSeconds = -1, thumbnailUrl = "http://example.org/image")
        assertEquals("عنوان 👩‍💻", inspection.title)
        assertEquals("قناة", inspection.uploader)
        assertNull(inspection.durationSeconds); assertNull(inspection.thumbnailUrl)
        val bounded = Inspection(source, "v1", emptyList(), title = "🌊".repeat(600), uploader = "أ".repeat(400))
        val boundedTitle = assertNotNull(bounded.title)
        assertEquals(300, boundedTitle.codePointCount(0, boundedTitle.length))
        assertEquals(120, bounded.uploader!!.length)
    }
    @Test fun thumbnailsRequireOneExactPublicHttpsUrl() {
        for (url in listOf("https://user:pass@example.org/image", "https://localhost/image", "https://127.0.0.1/image",
            "https://image.local/image", "https://xn--mgbh0fb.com/image", "caption https://example.org/image",
            "https://example.org/a https://example.org/b", "https://example.org/\u202E", "https://example.org/" + "a".repeat(8192))) {
            assertNull(Inspection(source, "v1", emptyList(), thumbnailUrl = url).thumbnailUrl, url)
        }
        assertEquals("https://i.ytimg.com/vi/id/default.jpg?sig=Ab%2Fc", Inspection(source, "v1", emptyList(),
            thumbnailUrl = "https://i.ytimg.com/vi/id/default.jpg?sig=Ab%2Fc").thumbnailUrl)
    }
}
