package dev.ahmed.savlino.domain

import kotlin.test.*

class DerivedFilenameTest {
    private val source = LinkRevision(assertNotNull(LinkInput.parse("https://media.example/clip")), 1)
    private fun filename(title: String, ext: String = "mp4"): String {
        val video = Format("http", ext, true, true)
        return assertIs<ConfirmResult.Confirmed>(DownloadCommand.confirm("job",
            Inspection(source, "fixture", listOf(video), title = title), source, "http", "Clip")).command.filename
    }

    @Test fun longPostCaptionBecomesAShortFilenameAtAWordBoundaryWithItsExtension() {
        val caption = "A sample post about a quiet afternoon near the water with friends and family enjoying the sunshine. " +
            "This second sentence should never be part of the saved filename."
        assertEquals("A sample post about a quiet afternoon near the water with friends and family.mov", filename(caption, "mov"))
        assertEquals("A short title.webm", filename("A short title", "webm"))
    }

    @Test fun arabicWordsAndHarakatRemainWholeAtTheStemLimit() {
        val caption = "هذا وصف عربي للفيديو مع كلمات إضافية لتجربة اسم الملف القصير وحفظ الحروف العربية كاملة في كل مرة"
        assertEquals("هذا وصف عربي للفيديو مع كلمات إضافية لتجربة اسم الملف القصير وحفظ الحروف العربية.mp4", filename(caption))
        assertEquals("a".repeat(79) + ".mp4", filename("a".repeat(79) + "بُ"))
        assertEquals("a".repeat(78) + "بُ.mp4", filename("a".repeat(78) + "بُz"))
    }

    @Test fun unbrokenTitlesAndEmojiRespectBothCharacterAndByteLimitsWithoutSplittingSurrogates() {
        assertEquals("a".repeat(80) + ".mp4", filename("a".repeat(170)))
        assertEquals("a".repeat(79) + "🌊.mp4", filename("a".repeat(79) + "🌊more"))
        assertEquals("🌊".repeat(59) + ".mp4", filename("🌊".repeat(170)))
        assertEquals("é".repeat(80) + ".mp4", filename("e\u0301".repeat(170)))
    }
}
