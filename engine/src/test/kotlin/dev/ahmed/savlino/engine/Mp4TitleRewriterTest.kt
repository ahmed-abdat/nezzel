package dev.ahmed.savlino.engine

import java.io.File
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class Mp4TitleRewriterTest {
    @get:Rule val temp = TemporaryFolder()
    private fun input(extension: String = "mp4") = temp.newFolder().resolve("output.$extension").apply { writeText("original") }

    @Test fun numericAndDifferentTitlesRewriteToConfirmedTitleWithoutInheritedMetadata() {
        for (existing in listOf("1058356515425744", "Different title")) {
            val file = input()
            Mp4TitleRewriter(readTitle = { if (it == file) existing else "Confirmed title" }, ffmpeg = { args ->
                assertEquals(listOf("-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-i", file.absolutePath,
                    "-map", "0", "-c", "copy", "-map_metadata", "-1", "-map_metadata:s", "-1",
                    "-map_chapters", "-1", "-movflags", "+faststart", "-metadata", "title=Confirmed title", "-metadata", "encoder=",
                    "-metadata:s", "encoder=", File(file.parentFile, "output.nezzel-title.mp4").absolutePath), args)
                File(args.last()).writeText("rewritten")
                true
            }).rewrite(file, "Confirmed title")
            assertEquals("rewritten", file.readText())
            assertEquals(listOf(file), file.parentFile!!.listFiles()!!.toList())
        }
    }

    @Test fun matchingTitleAvoidsCopyingLargeMp4() {
        val file = input()
        Mp4TitleRewriter(readTitle = { "Confirmed title" }, ffmpeg = { fail("Needless remux") })
            .rewrite(file, "Confirmed title")
        assertEquals("original", file.readText())
    }

    @Test fun titleIsOneLiteralArgumentWithoutShellOrYtdlpMetadataFields() {
        val file = input()
        val title = "العنوان 'quoted' \$(touch ignored); %s = title"
        Mp4TitleRewriter(readTitle = { if (it == file) "123" else title }, ffmpeg = { args ->
            assertEquals(listOf("title=$title", "encoder=", "encoder="),
                args.filterIndexed { index, _ -> index > 0 && args[index - 1] in setOf("-metadata", "-metadata:s") })
            assertTrue(args.none { it.contains("https://example.invalid") || it.contains("webpage_url") ||
                it.contains("description") || it.contains("uploader") || it.contains("comment") || it.contains("purl") })
            File(args.last()).writeText("rewritten")
            true
        }).rewrite(file, title)
        assertEquals("rewritten", file.readText())
    }

    @Test fun audioImagesGifAndOtherVideoContainersAreNeverRemuxed() {
        for (extension in listOf("mp3", "m4a", "mkv", "webm", "mov", "jpg", "png", "webp", "gif")) {
            val file = input(extension)
            Mp4TitleRewriter(readTitle = { fail("Unsupported title probe") }, ffmpeg = { fail("Unsupported remux") })
                .rewrite(file, "Confirmed title")
            assertEquals("original", file.readText())
        }
    }

    @Test fun failedRemuxEmptyOrWrongOutputAndFailedReplacementRetainOriginalAndCleanTemporaryFile() {
        for (failure in listOf("exit", "throw", "empty", "wrong-title", "replace")) {
            val file = input()
            Mp4TitleRewriter(readTitle = { if (it == file) "123" else if (failure == "wrong-title") "123" else "Confirmed title" },
                ffmpeg = { args ->
                    File(args.last()).writeText(if (failure == "empty") "" else "partial")
                    if (failure == "throw") throw IOException("Private diagnostics must not escape")
                    failure != "exit"
                }, replace = { _, _ ->
                    if (failure == "replace") throw IOException("Replacement denied")
                    fail("Invalid output must never replace original")
                }).rewrite(file, "Confirmed title")
            assertEquals("original", file.readText(), failure)
            assertEquals(listOf(file), file.parentFile!!.listFiles()!!.toList(), failure)
        }
    }

    @Test fun missingOrUnreadableTitleTagNeverCopiesTheFile() {
        for (probe in listOf<(File) -> String?>({ null }, { "" }, { throw IOException() })) {
            val file = input()
            Mp4TitleRewriter(readTitle = probe, ffmpeg = { fail("Needless remux") }).rewrite(file, "Confirmed title")
            assertEquals("original", file.readText())
        }
    }
}
