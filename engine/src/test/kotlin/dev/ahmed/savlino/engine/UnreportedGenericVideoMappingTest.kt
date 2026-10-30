package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** Sanitized generic-extractor shapes from the 2026-10-19 device pass. */
class UnreportedGenericVideoMappingTest {
    @get:Rule val temp = TemporaryFolder()
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://media.example/clip.mp4")), 1)
    private fun map(row: String, extra: String = "") = YtdlpMetadataMapper.map(
        """{"extractor":"generic","extractor_key":"Generic","title":"Sample clip"$extra,"formats":[$row]}""", source, "fixture")
    private fun row(ext: String, codecs: String = "", facts: String = "") =
        """{"format_id":"http","ext":"$ext","protocol":"https","url":"https://media.example/clip.$ext"$codecs$facts}"""

    @Test fun loneDirectVideoWithUnreportedCodecsOffersOnePlainVideoInItsActualContainer() {
        for (ext in listOf("mp4", "mov", "webm")) for (codecs in listOf("", ",\"vcodec\":null,\"acodec\":null",
            ",\"vcodec\":\"none\",\"acodec\":\"none\"", ",\"vcodec\":\"unknown\",\"acodec\":\"unknown\"",
            ",\"vcodec\":\"none\",\"acodec\":\"unknown\"")) {
            val inspection = map(row(ext, codecs))
            val video = inspection.formats.single()
            assertEquals("http", video.id)
            assertEquals(ext, video.container)
            assertTrue(video.hasVideo && video.unreportedStreams)
            assertNull(video.videoCodec); assertNull(video.audioCodec); assertNull(video.height)
            assertEquals(Size.Unknown, video.size)
            assertTrue(inspection.presets.isNotEmpty())
            assertTrue(inspection.presets.all { it.formatId == "http" })
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, "http", "Clip")).command
            assertEquals("Sample clip.$ext", command.filename)
        }
    }

    @Test fun directVideoKeepsReportedHeightAndSizeWithoutInventingACodecOrSize() {
        val video = map(row("mov", facts = ",\"height\":720,\"filesize\":12345")).formats.single()
        assertEquals(720, video.height); assertEquals(Size.Exact(12345), video.size)
        assertNull(video.videoCodec); assertNull(video.audioCodec)
        assertEquals(Size.Estimated(12000), map(row("webm", facts = ",\"filesize_approx\":12000")).formats.single().size)
        val unknown = map(row("mp4", facts = ",\"tbr\":800"), extra = ",\"duration\":10")
        assertEquals(Size.Unknown, unknown.formats.single().size)
        assertTrue(unknown.roughSizeBytes.isEmpty(), "a generic codec-less file offers only a reported size")
    }

    @Test fun genericMimeSubtypeFormatIdsAreSelectedByLiteralIdRatherThanContainerSelector() {
        // yt-dlp generic uses the Content-Type subtype as the ID: video/mp4 -> "mp4".
        for (ext in listOf("mp4", "mov", "webm")) {
            val inspection = map(row(ext).replace("\"format_id\":\"http\"", "\"format_id\":\"$ext\""))
            assertEquals(ext, inspection.formats.single().sourceFormatId)
            val command = assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", inspection, source, ext, "Clip")).command
            val args = YtdlpCommand.download(command, temp.newFolder().canonicalFile)
            assertEquals("all[format_id=$ext]", args[args.indexOf("-f") + 1])
        }
    }

    @Test fun genericFallbackDoesNotInventStreamsForManifestsAudioImagesDrmOrSeveralFiles() {
        for (bad in listOf(row("m4a"), row("jpg"), row("mov").replace("https\"", "m3u8_native\""),
            row("mov").replace("clip.mov", "manifest.mpd"), row("webm", facts = ",\"fragments\":[]"),
            row("webm", facts = ",\"has_drm\":true"), row("webm", facts = ",\"is_live\":true"))) {
            assertTrue(map(bad).formats.isEmpty(), bad)
        }
        assertTrue(map(row("mov") + "," + row("webm").replace("\"http\"", "\"http2\"")).formats.isEmpty())
        // An explicitly silent single file is offered as a silent video (owner 2026-10-30).
        val silent = map(row("mov", ",\"vcodec\":\"avc1\",\"acodec\":\"none\""))
        assertTrue(silent.formats.single().sourceHasNoSound)
        assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job", silent, source, "http", "Clip"))
    }
}
