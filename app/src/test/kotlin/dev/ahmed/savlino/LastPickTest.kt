package dev.ahmed.savlino

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.*
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LastPickTest {
    private val url = "https://youtube.com/watch?v=one"
    private val nextUrl = "https://youtube.com/watch?v=two"
    private val audio = Format("audio", "m4a", false, true, bitrateKbps = 128)
    private fun video(height: Int) = Format("v$height", "mp4", true, true, height = height)
    private fun store(): AppPreferences {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("nezzel", 0).edit().clear().commit()
        return AppPreferences(app)
    }
    private fun inspection(heights: List<Int>, link: String = url, revision: Long = 1,
        presets: List<PresetChoice>? = null, extra: List<Format> = emptyList()): Inspection =
        Inspection(LinkRevision(LinkInput.parse(link)!!, revision), "fixture-v1", heights.map(::video) + extra + audio,
            presets ?: listOfNotNull(
                heights.maxOrNull()?.let { PresetChoice(Preset.BEST, "v$it") },
                heights.firstOrNull { it == 720 }?.let { PresetChoice(Preset.BALANCED, "v$it") },
                PresetChoice(Preset.AUDIO_M4A, audio.id)))
    private fun model(prefs: AppPreferences, heights: List<Int> = listOf(2160, 1440, 720),
        saved: SavedStateHandle = SavedStateHandle(), presets: List<PresetChoice>? = null,
        extra: List<Format> = emptyList(), link: String = url, checked: Boolean = true): LinkViewModel =
        LinkViewModel(saved, engine = FakeEngine(InspectResult.Success(inspection(heights, link, presets = presets, extra = extra))),
            currentEngineVersion = { "fixture-v1" }, initialPreset = prefs.lastPreset,
            rememberPreset = { prefs.lastPreset = it }, initialHeight = prefs.lastHeight,
            rememberHeight = { prefs.lastHeight = it }, storedPick = { prefs.lastPreset to prefs.lastHeight })
            .also { if (checked) check(it, link) }
    private fun check(vm: LinkViewModel, link: String = url) {
        vm.edit(link); vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
    }
    private fun confirmAllQualities(vm: LinkViewModel, id: String) {
        vm.showMoreFormats(); vm.selectFormat(id); vm.confirmDownload()
        assertEquals(id, vm.takeConfirmedCommand()?.format?.id)
        vm.dismissOptions()
    }
    private fun remember1440(prefs: AppPreferences) {
        prefs.lastPreset = Preset.BALANCED
        confirmAllQualities(model(prefs), "v1440")
    }

    @Test fun aPickConfirmedInTheShareSheetPreselectsInTheAlreadyOpenApp() {
        val prefs = store()
        val app = model(prefs, link = nextUrl, checked = false)
        remember1440(prefs)
        check(app, nextUrl)
        assertEquals("v1440", app.selectedFormat?.id)
        assertBadge(app, "1440p")
    }

    @Test fun confirmedAllQualitiesPickReplacesPresetAndPreselectsExactOutsideRowOnNextLink() {
        val prefs = store()
        remember1440(prefs)
        val restored = AppPreferences(RuntimeEnvironment.getApplication())
        assertEquals(1440, restored.lastHeight)
        assertNull(restored.lastPreset)
        val next = model(restored, link = nextUrl)
        assertEquals("v1440", next.selectedFormat?.id)
        assertNull(next.selectedPreset)
        assertNull(next.lastUsedPreset)
        assertBadge(next, "1440p")
    }

    @Test @Config(qualifiers = "ar") fun missingHeightSelectsLargestEligibleLowerOutsideRowWithArabicBadge() {
        val prefs = store()
        remember1440(prefs)
        // BEST is above the remembered height; 1080 is an outside row, not a preset.
        val next = model(prefs, listOf(2160, 1080, 720), link = nextUrl)
        assertEquals("v1080", next.selectedFormat?.id)
        assertNull(next.selectedPreset)
        assertBadge(next, "1080p", "ar")
    }

    @Test fun missing1440On1080And720LinkUses1080PresetRowWithBadge() {
        val prefs = store()
        remember1440(prefs)
        val next = model(prefs, listOf(1080, 720), link = nextUrl)
        assertEquals("v1080", next.selectedFormat?.id)
        assertEquals(Preset.BEST, next.selectedPreset)
        assertBadge(next, "1080p")
    }

    @Test fun exactHeightPrefersPresetRenditionOverOtherReturnedVersions() {
        val prefs = store().apply { lastHeight = 1440 }
        val presetVersion = video(1440).copy(id = "preset1440")
        val vm = model(prefs, extra = listOf(presetVersion), presets = listOf(
            PresetChoice(Preset.BEST, "preset1440"), PresetChoice(Preset.BALANCED, "v720")))
        assertEquals("preset1440", vm.selectedFormat?.id)
        assertEquals(Preset.BEST, vm.selectedPreset)
        assertBadge(vm, "1440p")
    }

    @Test fun confirmedPresetReplacesHeightAndWinsOnNextVideo() {
        val prefs = store().apply { lastHeight = 1440 }
        val saved = SavedStateHandle()
        val vm = model(prefs, saved = saved)
        vm.selectPreset(Preset.BALANCED); vm.confirmDownload()
        assertEquals("v720", vm.takeConfirmedCommand()?.format?.id)
        assertNull(prefs.lastHeight)
        assertEquals(Preset.BALANCED, prefs.lastPreset)
        assertNull(saved.get<Int>("lastHeight"))
        assertEquals("BALANCED", saved.get<String>("lastPreset"))
        vm.dismissOptions()
        val next = model(AppPreferences(RuntimeEnvironment.getApplication()), link = nextUrl)
        assertEquals("v720", next.selectedFormat?.id)
        assertBadge(next, "720p")
    }

    @Test fun audioFromEitherScreenNeverChangesRememberedHeight() {
        val prefs = store().apply { lastHeight = 1440 }
        val presetAudio = model(prefs)
        presetAudio.selectPreset(Preset.AUDIO_M4A); presetAudio.confirmDownload()
        assertEquals(audio.id, presetAudio.takeConfirmedCommand()?.format?.id)
        presetAudio.dismissOptions()
        confirmAllQualities(model(prefs), audio.id)
        assertEquals(1440, prefs.lastHeight)
        assertNull(prefs.lastPreset)
        assertEquals("v1440", model(prefs, link = nextUrl).selectedFormat?.id)
    }

    @Test fun existingLastPresetOnlyPreferencesStillPreselectAndBadgeThePreset() {
        val prefs = store()
        RuntimeEnvironment.getApplication().getSharedPreferences("nezzel", 0).edit()
            .putString("lastPreset", "BALANCED").commit()
        assertNull(prefs.lastHeight)
        val vm = model(prefs)
        assertEquals("v720", vm.selectedFormat?.id)
        assertEquals(Preset.BALANCED, vm.lastUsedPreset)
        assertBadge(vm, "720p")
    }

    @Test fun savedHeightReplacesStaleInitialPresetAfterRecreation() {
        val prefs = store().apply { lastPreset = Preset.BALANCED }
        val saved = SavedStateHandle()
        confirmAllQualities(model(prefs, saved = saved), "v1440")
        assertEquals(1440, saved.get<Int>("lastHeight"))
        assertNull(saved.get<String>("lastPreset"))
        // Initial preferences may lag the restored state; they must not resurrect the old preset.
        prefs.lastPreset = Preset.BALANCED
        val restored = model(prefs, saved = saved)
        assertNull(restored.lastUsedPreset)
        assertEquals("v1440", restored.selectedFormat?.id)
    }

    @Test fun savedPresetReplacesStaleInitialHeightAfterRecreation() {
        val prefs = store().apply { lastHeight = 1440 }
        val saved = SavedStateHandle()
        val vm = model(prefs, saved = saved)
        vm.selectPreset(Preset.BALANCED); vm.confirmDownload(); vm.dismissOptions()
        prefs.lastHeight = 1440
        val restored = model(prefs, saved = saved)
        assertEquals(Preset.BALANCED, restored.lastUsedPreset)
        assertEquals("v720", restored.selectedFormat?.id)
    }

    @Test fun heightIsRememberedAsShortSideForPortraitVideo() {
        val prefs = store()
        val portrait = Format("portrait", "mp4", true, true, width = 1440, height = 2560)
        confirmAllQualities(model(prefs, extra = listOf(portrait)), portrait.id)
        assertEquals(1440, prefs.lastHeight)
        assertEquals("v1440", model(prefs, link = nextUrl).selectedFormat?.id)
    }

    @Test fun unavailableLowerHeightFallsBackToBalancedWithoutFalseBadge() {
        val prefs = store().apply { lastHeight = 240 }
        val vm = model(prefs)
        assertEquals("v720", vm.selectedFormat?.id)
        assertNull(vm.lastUsedFormat)
        assertBadge(vm, null)
    }

    @Test fun heightFallbackSkipsIneligibleEngineFormatsAndNeverInventsAFormat() {
        val prefs = store().apply { lastHeight = 1440 }
        val unsupported = video(1440).copy(container = "webm")
        val silent = video(1080).copy(hasAudio = false)
        val vm = model(prefs, listOf(2160, 720), extra = listOf(unsupported, silent))
        assertEquals("v720", vm.selectedFormat?.id)
        assertEquals("v720", vm.lastUsedFormat?.id)
        assertFalse(vm.formatOptions.any { it.id == unsupported.id || it.id == silent.id })
    }

    @Test fun aLoneVideoRowHasNoBadge() {
        val prefs = store().apply { lastHeight = 720 }
        val vm = model(prefs, listOf(720))
        assertEquals("v720", vm.lastUsedFormat?.id)
        assertNull(vm.lastUsedBadge)
        assertBadge(vm, null)
    }

    @Test fun rememberedHeightDoesNotChangeAudioOnlyPreselectionOrBadge() {
        val prefs = store().apply { lastHeight = 1440 }
        val vm = model(prefs, emptyList())
        assertEquals(audio.id, vm.selectedFormat?.id)
        assertNull(vm.lastUsedFormat)
        assertBadge(vm, null)
    }

    @Test fun unconfirmedAllQualitiesSelectionDoesNotReplaceRememberedPreset() {
        val prefs = store().apply { lastPreset = Preset.BALANCED }
        val vm = model(prefs)
        vm.showMoreFormats(); vm.selectFormat("v1440"); vm.dismissOptions()
        assertNull(prefs.lastHeight)
        assertEquals(Preset.BALANCED, prefs.lastPreset)
    }

    @Test fun nextLinkInSameViewModelUsesNewHeightDespiteInitialPreset() {
        val first = inspection(listOf(2160, 1440, 720))
        val second = inspection(listOf(2160, 1440, 720), nextUrl, revision = 2)
        var operation = 0
        val vm = LinkViewModel(SavedStateHandle(), engine = FakeEngine(inspectionScripts = mapOf(
            "1" to FakeEngine.InspectionScript(InspectResult.Success(first)),
            "2" to FakeEngine.InspectionScript(InspectResult.Success(second)))),
            currentEngineVersion = { "fixture-v1" }, newOperationId = { (++operation).toString() }, initialPreset = Preset.BALANCED)
        check(vm)
        confirmAllQualities(vm, "v1440")
        check(vm, nextUrl)
        assertEquals("v1440", vm.selectedFormat?.id)
        assertEquals("v1440", vm.lastUsedFormat?.id)
    }

    /** Assert the actual visible badge and TalkBack description belong only to the resolved row. */
    private fun assertBadge(vm: LinkViewModel, quality: String?, language: String = "en") {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            val activity = controller.get()
            activity.setContent { NezzelTheme(ThemePref.Light, false) { OptionsSheetContent(vm, language, vm::dismissOptions) } }
            val nodes = semanticsNodes(activity, rounds = 4, idleMillis = 200)
            val badges = nodes.filter { it.config.getOrNull(SemanticsProperties.TestTag) == "last_used_badge" }
            if (quality == null) {
                assertTrue(badges.toString(), badges.isEmpty())
            } else {
                val badge = badges.single()
                assertEquals(listOf(activity.getString(R.string.last_used)), texts(descendants(badge)))
                val row = nodes.single { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton &&
                    descendants(it).any { child -> child.id == badge.id } }
                assertTrue(texts(descendants(row)).toString(), texts(descendants(row)).any { it.contains(quality) })
                assertTrue(row.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                    .any { it.contains(activity.getString(R.string.last_used)) })
                assertEquals(true, row.config.getOrNull(SemanticsProperties.Selected))
            }
        } finally { vm.dismissOptions(); controller.pause().stop().destroy() }
    }
}
