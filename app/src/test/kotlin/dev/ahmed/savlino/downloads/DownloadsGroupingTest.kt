package dev.ahmed.savlino.downloads

import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class DownloadsGroupingTest {
    private fun file(id: Int, name: String, time: Long, path: String = "Download/Nezzel/", volume: String = "external_primary") =
        SavedMedia(Uri.parse("content://media/external/downloads/$id"), name, name.substringBeforeLast('.'),
            if (name.endsWith(".mp4")) "video/mp4" else "image/jpeg", null, time, null, null, null, relativePath = path, volumeName = volume)

    @Test fun slowFortyPhotoSaveKeepsOneGroupAcrossFiveMinutes() {
        val rows = groupDownloads((1..40).map { file(it, "Post_${it.toString().padStart(2, '0')}.jpg", (it - 1) * 8L) })
        assertEquals((1..40).map { SavedPostPosition(it, 40) }, rows.map { it.postPosition })
        assertEquals((1..40).map { it.toString() }, rows.map { it.uri.lastPathSegment })
    }
    @Test fun plainNumericTitlesAndDifferentPaddingWidthsStaySeparate() {
        val rows = groupDownloads(listOf(file(1, "Lecture_1.mp4", 100), file(2, "Lecture_2.mp4", 101),
            file(3, "Post_01.jpg", 102), file(4, "Post_002.jpg", 103)))
        assertTrue(rows.all { it.postPosition == null })
    }

    @Test fun interleavedPostFilesStayTogetherInNumericOrder() {
        val rows = groupDownloads(listOf(file(10, "Post_10.jpg", 110), file(99, "Other.jpg", 105),
            file(2, "Post_02.jpg", 102), file(1, "Post_01.jpg", 100)))
        assertEquals(listOf(1, 2, 10, 99), rows.map { it.uri.lastPathSegment!!.toInt() })
        assertEquals(listOf(SavedPostPosition(1, 3), SavedPostPosition(2, 3), SavedPostPosition(3, 3), null), rows.map { it.postPosition })
    }
    @Test fun separateSavesAndFoldersDoNotMerge() {
        val rows = groupDownloads(listOf(file(1, "Post_01.jpg", 0), file(2, "Post_02.jpg", 120),
            file(3, "Post_03.jpg", 241), file(4, "Post_04.jpg", 119, volume = "card"),
            file(5, "Post_05.jpg", 119, path = "Download/Nezzel/Statuses/"), file(6, "Post_06 (1).jpg", 118)))
        assertEquals(setOf("1", "2"), rows.filter { it.postPosition != null }.map { it.uri.lastPathSegment }.toSet())
    }
    @Test fun aPostSavedAcrossMidnightKeepsOneDateSection() {
        val zone = java.time.ZoneId.systemDefault()
        val midnight = java.time.LocalDate.of(2026, 1, 2).atStartOfDay(zone).toEpochSecond()
        val rows = groupDownloads(listOf(file(1, "Post_01.jpg", midnight - 1), file(2, "Post_02.jpg", midnight + 1)))
        assertEquals(listOf(java.time.LocalDate.of(2026, 1, 2)), rows.groupBy { it.date }.keys.toList())
        assertEquals(listOf("1", "2"), rows.map { it.uri.lastPathSegment })
    }
    @Test fun duplicatePositionsAndNonSuffixNamesRemainStandalone() {
        val input = listOf(file(1, "Post_01.jpg", 100), file(2, "Post_01.png", 101),
            file(3, "Post_00.jpg", 102), file(4, "Post_sound.m4a", 103))
        assertTrue(groupDownloads(input).all { it.postPosition == null })
    }
    @Test fun homeListsAPostOnceByItsFirstFile() {
        val rows = homeEntries(listOf(file(3, "Post_03.jpg", 102), file(9, "Clip.mp4", 50),
            file(2, "Post_02.jpg", 101), file(1, "Post_01.jpg", 100)))
        assertEquals(listOf("1", "9"), rows.map { it.uri.lastPathSegment })
    }
}
