package dev.ahmed.savlino.downloads

import android.content.Context
import android.net.Uri
import dev.ahmed.savlino.locale.AppPreferences
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class LibraryPolishTest {
    private fun file(id: Int, name: String, mime: String = "video/mp4", time: Long = id.toLong(), size: Long? = 10) =
        SavedMedia(Uri.parse("content://media/external/downloads/$id"), name, name.substringBeforeLast('.'), mime,
            size, time, null, null, null, relativePath = "Download/Nezzel/", volumeName = "external_primary")
    private val mixed = listOf(file(1, "أَحْمَد_01.jpg", "image/jpeg", 100, 40),
        file(2, "أَحْمَد_02.mp4", time = 105, size = 50), file(3, "Song.mp3", "audio/mpeg", 102, 70))
    private fun ids(rows: List<SavedMedia>) = rows.map { it.uri.lastPathSegment }

    @Test fun typeAndArabicSearchKeepTheWholeMixedPostWithOriginalPositions() {
        for (filter in listOf(MediaFilter.IMAGE, MediaFilter.VIDEO)) {
            val result = savedLibraryItems(mixed, filter, "احمد 02", SavedSort.NEWEST, Locale("ar"))
            assertEquals(listOf("1", "2"), ids(result))
            assertEquals(listOf(SavedPostPosition(1, 2), SavedPostPosition(2, 2)), result.map { it.postPosition })
        }
        assertEquals(listOf("3"), ids(savedLibraryItems(mixed, MediaFilter.AUDIO, "song", SavedSort.NEWEST, Locale.ENGLISH)))
        assertTrue(savedLibraryItems(mixed, MediaFilter.AUDIO, "احمد", SavedSort.NEWEST, Locale("ar")).isEmpty())
    }
    @Test fun newestUsesNewestSiblingAndLargestUsesWholePostBytesWithUnknownLast() {
        val input = mixed + file(4, "Unknown.mp4", time = 200, size = null) + file(5, "Small.mp4", time = 90, size = 80)
        assertEquals(listOf("4", "1", "2", "3", "5"), ids(savedLibraryItems(input)))
        assertEquals(listOf("1", "2", "5", "3", "4"), ids(savedLibraryItems(input, sort = SavedSort.LARGEST)))
    }
    @Test fun largestKeepsPartiallyUnknownGroupsBehindKnownSizes() {
        val input = mixed.map { if (it.uri.lastPathSegment == "2") it.copy(size = null) else it }
        assertEquals(listOf("3", "1", "2"), ids(savedLibraryItems(input, sort = SavedSort.LARGEST)))
    }
    @Test fun alphabeticalUsesArabicCollationRatherThanUnicodeCodePointOrder() {
        val input = listOf(file(1, "بدر.mp4"), file(2, "أحمد.mp4"), file(3, "احمد.mp4"), file(4, "آدم.mp4"))
        assertEquals(listOf("2", "4", "3", "1"), ids(savedLibraryItems(input, sort = SavedSort.NAME, locale = Locale("ar"))))
    }
    @Test fun alphabeticalUsesLatinCollationAndKeepsGroupedSiblingsTogether() {
        val input = listOf(file(1, "Zulu.mp4"), file(2, "été_01.jpg", "image/jpeg"),
            file(3, "été_02.mp4"), file(4, "Apple.mp4"))
        assertEquals(listOf("4", "2", "3", "1"), ids(savedLibraryItems(input, sort = SavedSort.NAME, locale = Locale.FRENCH)))
    }
    @Test fun chipsOnlyIncludePresentKindsAndHideForSingleKindOrEmptyLibrary() {
        assertEquals(listOf(MediaFilter.ALL, MediaFilter.VIDEO, MediaFilter.AUDIO, MediaFilter.IMAGE), availableMediaFilters(mixed))
        assertEquals(listOf(MediaFilter.ALL, MediaFilter.VIDEO, MediaFilter.IMAGE), availableMediaFilters(mixed.take(2)))
        assertTrue(availableMediaFilters(mixed.take(1)).isEmpty())
        assertTrue(availableMediaFilters(emptyList()).isEmpty())
    }
    @Test fun sortDefaultsToNewestAndSurvivesNewPreferenceInstance() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("nezzel-portable", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(SavedSort.NEWEST, AppPreferences(app).savedSort)
        for (sort in SavedSort.entries) {
            AppPreferences(app).savedSort = sort
            assertEquals(sort, AppPreferences(app).savedSort)
        }
        prefs.edit().putString("saved_sort", "removed_choice").commit()
        assertEquals(SavedSort.NEWEST, AppPreferences(app).savedSort)
    }
}
