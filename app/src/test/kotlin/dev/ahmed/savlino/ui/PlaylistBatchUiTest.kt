package dev.ahmed.savlino.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.*
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PlaylistBatchUiTest {
    @Test fun englishOptionsOpenThePlaylistWithoutInspectingTheFirstVideo() = sheet("en", true)
    @Test @Config(qualifiers = "ar") fun arabicOptionsOpenThePlaylistWithoutInspectingTheFirstVideo() = sheet("ar", true)
    @Test @Config(qualifiers = "fr") fun frenchOptionsOpenThePlaylistWithoutInspectingTheFirstVideo() = sheet("fr", true)
    @Test fun videoSharedFromAPlaylistDoesNotShowTheFirstVideoWarning() = sheet("en", false)

    private fun sheet(language: String, pure: Boolean) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) =
                PlaylistResult.Success(PlaylistPage("Fixture playlist", emptyList(), 0, null))
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                check(!pure) { "Pure playlists must not inspect the first video" }
                return InspectResult.Success(
                Inspection(source, "fixture", listOf(Format("18", "mp4", true, true, height = 720)),
                    listOf(PresetChoice(Preset.BALANCED, "18")), title = "Fixture"))
            }
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        try {
            vm.paste(if (pure) "https://youtube.com/playlist?list=PLabcdefghij"
                else "https://youtube.com/watch?v=abcdefghijk&list=PLabcdefghij")
            shadowOf(Looper.getMainLooper()).idle()
            controller.get().setContent(parent = recomposer) {
                CompositionLocalProvider(LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    NezzelTheme(ThemePref.Dark, false) { OptionsSheetContent(vm, language, vm::dismissOptions) }
                }
            }
            repeat(4) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                clock.sendFrame(System.nanoTime())
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            }
            val labels = texts(semanticsNodes(controller.get(), rounds = 4, idleMillis = 200))
            assertEquals(pure, vm.playlist.visible)
            assertEquals(!pure, vm.optionsState is OptionsState.Ready)
            assertFalse(controller.get().getString(R.string.playlist_first_video_warning) in labels)
        } finally { vm.dismissOptions(); recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }

    @Test fun videoInListFetchesNothingUntilSeeAllAndManualPurePlaylistWaitsForCheck() {
        var listings = 0
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?): PlaylistResult {
                listings++
                return PlaylistResult.Success(PlaylistPage("List", emptyList(), 0, null))
            }
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        try {
            vm.paste("https://youtube.com/watch?v=abcdefghijk&list=PLabcdefghij")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, listings)
            vm.showPlaylist(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, listings)
            vm.dismissOptions()
            vm.automaticOptions = false
            vm.paste("https://youtube.com/playlist?list=PLabcdefghij")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, listings); assertFalse(vm.playlist.visible)
            vm.checkLink(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, listings); assertTrue(vm.playlist.visible)
        } finally { vm.dismissOptions() }
    }

    @Test fun onePlaylistTapUsesTheExistingNotificationChoiceAndDismissalInvalidatesIt() {
        var prompts = 0
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun listPlaylist(source: LinkRevision, operationId: String, page: PlaylistPageToken?) = PlaylistResult.Success(
                PlaylistPage("List", listOf(PlaylistItem("abcdefghijk", "https://youtube.com/watch?v=abcdefghijk", "Video", 60)), 1, null))
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        vm.notificationPrompt = { prompts++ }
        try {
            vm.paste("https://youtube.com/playlist?list=PLabcdefghij")
            shadowOf(Looper.getMainLooper()).idle()
            vm.playlist.confirm(); vm.playlist.confirm()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, prompts); assertTrue(vm.hasPendingNotificationChoice); assertTrue(vm.playlist.submitting)
            vm.dismissOptions(); vm.finishNotificationRequest()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(vm.hasPendingNotificationChoice); assertFalse(vm.playlist.visible); assertNull(vm.acceptedJob)
        } finally { vm.dismissOptions() }
    }

    @Test fun videosSharedFromPrivateListsStillReachReadyOptions() {
        for (url in listOf("https://youtube.com/watch?v=abcdefghijk&list=WL",
            "https://youtube.com/watch?v=abcdefghijk&list=LL",
            "https://youtu.be/abcdefghijk?list=WL", "https://youtu.be/abcdefghijk?list=LL")) {
            val engine = object : EngineAdapter by FakeEngine() {
                override suspend fun inspect(source: LinkRevision, operationId: String) = InspectResult.Success(
                    Inspection(source, "fixture", listOf(Format("18", "mp4", true, true))))
            }
            val vm = LinkViewModel(SavedStateHandle(), engine = engine)
            try {
                vm.paste(url)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(vm.optionsState is OptionsState.Ready)
            } finally { vm.dismissOptions() }
        }
    }

    @Test fun mixPlaylistLinkInspectsItsSeedVideoOnly() {
        var inspected: String? = null
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult {
                inspected = source.link.rawUrl
                return InspectResult.Success(Inspection(source, "fixture", listOf(Format("18", "mp4", true, true))))
            }
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        try {
            vm.paste("https://youtube.com/playlist?list=RDabcdefghijk")
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(vm.optionsState is OptionsState.Ready)
            assertEquals("https://www.youtube.com/watch?v=abcdefghijk&list=RDabcdefghijk", inspected)
        } finally { vm.dismissOptions() }
    }

    @Test fun privateListsNeverReachInspection() {
        val engine = object : EngineAdapter by FakeEngine() {
            override suspend fun inspect(source: LinkRevision, operationId: String): InspectResult = error("Private list must not be fetched")
        }
        val vm = LinkViewModel(SavedStateHandle(), engine = engine)
        try {
            vm.paste("https://youtube.com/playlist?list=WL")
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(DownloadError.LoginRequired, (vm.optionsState as OptionsState.Error).error)
        } finally { vm.dismissOptions() }
    }
}
