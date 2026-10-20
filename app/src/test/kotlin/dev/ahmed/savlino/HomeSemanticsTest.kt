package dev.ahmed.savlino

import androidx.activity.compose.setContent
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HomeSemanticsTest {
    // Looper idling alone does not drive the window recomposer after the initial composition.
    @get:Rule val compose = createEmptyComposeRule()

    private fun semantics(activity: androidx.activity.ComponentActivity): List<SemanticsNode> {
        compose.waitForIdle()
        return semanticsNodes(activity, rounds = 3, idleMillis = 100)
    }
    @Test @Config(qualifiers = "en")
    fun distinctShareShowsShortEnglishFirstLinkNotice() = assertMultipleLinkNotice(
        "3 links found. Starting with the first.")

    @Test @Config(qualifiers = "ar")
    fun distinctShareShowsShortArabicFirstLinkNotice() = assertMultipleLinkNotice(
        "وُجدت 3 روابط، سنبدأ بالأول.")

    @Test fun repeatedIdenticalShareDoesNotShowMultipleLinkNotice() = assertMultipleLinkNotice(
        "3 links found. Starting with the first.", repeated = true)

    @Test fun distinctPasteShowsFirstLinkNotice() = assertMultipleLinkNotice(
        "3 links found. Starting with the first.", paste = true)

    @Test fun automaticShareShowsNoticeInsideTheOptionsWindow() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as dev.ahmed.savlino.jobs.IsolatedTestApplication
        val store = dev.ahmed.savlino.jobs.JobStore(app)
        app.coordinatorOverride = dev.ahmed.savlino.jobs.DownloadCoordinator(app,
            engine = dev.ahmed.savlino.engine.FakeEngine(dev.ahmed.savlino.engine.InspectResult.Failure(
                dev.ahmed.savlino.domain.DownloadError.Offline)), store = store)
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            controller.visible()
            val links = controller.get().links
            links.automaticOptions = true
            links.onNewShare(dev.ahmed.savlino.entry.ShareInput.from(android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,
                    "https://youtu.be/clipA https://youtu.be/clipB https://example.org/clipC")))
            semantics(controller.get())
            assertTrue(links.optionsState is dev.ahmed.savlino.ui.OptionsState.Error)
            val modalTexts = texts(windowViews(controller.get()).filter { it !== controller.get().window.decorView }
                .flatMap(::roots).flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) })
            assertTrue("Notice must be visible above the modal sheet", "3 links found. Starting with the first." in modalTexts)
        } finally { controller.pause().stop().destroy(); store.close(); app.coordinatorOverride = null }
    }

    @Test @Config(qualifiers = "en")
    fun nextLinkCardOpensSecondAndSkipClearsTheRest() = assertNextLinkCard("Next link (2 of 3)")

    @Test @Config(qualifiers = "ar")
    fun nextLinkCardHasArabicCopyAndIsolatedHost() = assertNextLinkCard("الرابط التالي (2 من 3)")

    private fun assertNextLinkCard(expected: String) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            controller.visible()
            val links = controller.get().links
            links.automaticOptions = true
            links.onNewShare(dev.ahmed.savlino.entry.ShareInput.from(android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,
                    "https://youtu.be/first https://example.org/second?secret=private https://third.org/third")))
            semantics(controller.get())
            links.dismissOptions()
            val nodes = semantics(controller.get())
            assertTrue(expected in texts(nodes))
            assertTrue("\u2066example.org\u2069" in texts(nodes))
            assertFalse(texts(nodes).any { "secret=private" in it })
            links.automaticOptions = false
            val open = chipAction(controller.get(), controller.get().getString(R.string.open_file))
            assertTrue(open.boundsInRoot.height >= 48f * controller.get().resources.displayMetrics.density)
            assertEquals(true, open.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertEquals("https://example.org/second?secret=private", links.text)
            assertEquals(dev.ahmed.savlino.ui.OptionsState.Hidden, links.optionsState)
            assertFalse(expected in texts(semantics(controller.get())))
            links.checkLink(); semantics(controller.get()); links.dismissOptions()
            val skip = chipAction(controller.get(), if (controller.get().resources.configuration.locales[0].language == "ar") "تخطي الكل" else "Skip all")
            assertTrue(skip.boundsInRoot.height >= 48f * controller.get().resources.displayMetrics.density)
            assertEquals(true, skip.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertFalse(texts(semantics(controller.get())).any { it.startsWith("Next link (") || it.startsWith("الرابط التالي (") })
        } finally { controller.pause().stop().destroy() }
    }

    private fun assertMultipleLinkNotice(expected: String, repeated: Boolean = false, paste: Boolean = false) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            controller.visible()
            val links = controller.get().links
            links.automaticOptions = false
            val input = if (repeated) "https://youtu.be/clipA https://youtu.be/clipA" else
                "https://youtu.be/clipA https://youtu.be/clipB https://example.org/clipC"
            if (paste) links.paste(input) else links.onNewShare(dev.ahmed.savlino.entry.ShareInput.from(
                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, input)))
            val texts = semantics(controller.get()).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
                .map { it.text }
            assertEquals(!repeated, expected in texts)
            assertEquals("https://youtu.be/clipA", links.text)
        } finally { controller.pause().stop().destroy() }
    }
    @Test @Config(qualifiers = "en")
    fun coordinatorFailureShowsLocalizedPoliteError() = assertCoordinatorError(
        "Downloads are unavailable. Reopen Nezzel to try again.")

    @Test @Config(qualifiers = "ar")
    fun coordinatorFailureShowsArabicPoliteError() = assertCoordinatorError(
        "التنزيلات غير متاحة. أعد فتح Nezzel لإعادة المحاولة.")

    private fun assertCoordinatorError(expected: String) {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as dev.ahmed.savlino.jobs.IsolatedTestApplication
        val store = dev.ahmed.savlino.jobs.JobStore(app)
        store.writableDatabase.execSQL("DROP TABLE jobs")
        val coordinator = dev.ahmed.savlino.jobs.DownloadCoordinator(app,
            engine = dev.ahmed.savlino.engine.FakeEngine(), store = store)
        app.coordinatorOverride = coordinator
        kotlinx.coroutines.runBlocking { coordinator.start(app.scope).join() }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            controller.visible()
            val errors = semantics(controller.get()).filter {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text ->
                    text.text == expected
                } == true
            }
            assertEquals(1, errors.size)
            assertEquals(LiveRegionMode.Polite, errors.single().config.getOrNull(SemanticsProperties.LiveRegion))
        } finally { controller.pause().stop().destroy(); store.close(); app.coordinatorOverride = null }
    }

    /** Intake needs window focus and the off-Main keystore/prefs load; production retries via ClipboardEntryEffect. */
    private fun awaitClipboardReady(links: dev.ahmed.savlino.ui.LinkViewModel) {
        val deadline = System.nanoTime() + 5_000_000_000
        while (!links.clipboardEligible && System.nanoTime() < deadline) {
            Thread.sleep(10); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        }
        assertTrue("Clipboard intake never became ready", links.clipboardEligible)
    }

    private fun chipAction(activity: MainActivity, label: String): SemanticsNode = semantics(activity).last { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any { n ->
            n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } ||
                label in n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        }
    }

    @Test fun suggestionShowsIsolatedActualHostBeforeAcceptance() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val links = controller.get().links
            links.automaticOptions = false
            controller.visible().windowFocusChanged(true)
            awaitClipboardReady(links)
            compose.waitForIdle() // Exercise a suggestion arriving after the first composition/focus.
            links.suggest(true, true, true) { "https://example.org/video?secret=private" }
            val texts = semantics(controller.get()).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertTrue(texts.toString(), texts.contains("\u2066example.org\u2069"))
            assertFalse(texts.any { it.contains("secret=private") })
            // Showing the chip is not acceptance: nothing is filled or inspected until the person taps it.
            assertEquals("", links.text)
            assertEquals(dev.ahmed.savlino.ui.OptionsState.Hidden, links.optionsState)
            val chip = chipAction(controller.get(), controller.get().getString(R.string.copied_chip))
            assertEquals(true, chip.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertEquals("https://example.org/video?secret=private", links.text)
            assertNotEquals(dev.ahmed.savlino.ui.OptionsState.Hidden, links.optionsState)
            assertFalse(links.hasSuggestion)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun notNowOnSuggestionChipLeavesDraftEmptyAndUninspected() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            val links = controller.get().links
            links.automaticOptions = false
            controller.visible().windowFocusChanged(true)
            awaitClipboardReady(links)
            compose.waitForIdle()
            links.suggest(true, true, true) { "https://example.org/video" }
            assertTrue("Offer must be pending before Not now", links.hasSuggestion)
            val notNow = chipAction(controller.get(), controller.get().getString(R.string.not_now))
            assertEquals(true, notNow.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertFalse("Not now must drop the offer", links.hasSuggestion)
            assertEquals("", links.text)
            assertEquals(dev.ahmed.savlino.ui.OptionsState.Hidden, links.optionsState)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun invalidShareShowsSeparateSnackbarWithoutMarkingDraftInvalid() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            controller.get().links.edit("https://example.org/video")
            controller.newIntent(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_TEXT, "invalid"))
            controller.visible()
            val texts = semantics(controller.get()).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            assertTrue(texts.toString(), texts.contains(controller.get().getString(R.string.invalid_link)))
            assertFalse(controller.get().links.invalid)
            assertEquals("https://example.org/video", controller.get().links.text)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun invalidLinkSupportingTextAnnouncesPolitely() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            controller.get().links.edit("invalid"); controller.get().links.checkLink()
            controller.visible()
            val all = semantics(controller.get())
            val error = all.singleOrNull {
                it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == controller.get().getString(R.string.invalid_link) } == true
            }
            assertNotNull(all.map { it.config }.toString(), error)
            assertEquals(LiveRegionMode.Polite, error!!.config.getOrNull(SemanticsProperties.LiveRegion))
        } finally { controller.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "en-w320dp-h640dp")
    fun focusedHomeActionsWorkAtLargeTextInEnglishLight() = assertFocusedHome(dev.ahmed.savlino.locale.ThemePref.Light)

    @Test @Config(qualifiers = "ar-w320dp-h640dp")
    fun focusedHomeActionsWorkAtLargeTextInArabicDark() = assertFocusedHome(dev.ahmed.savlino.locale.ThemePref.Dark)

    private fun assertFocusedHome(theme: dev.ahmed.savlino.locale.ThemePref) {
        val controller = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).create().start().resume()
        val activity = controller.get()
        val configuration = android.content.res.Configuration(activity.resources.configuration).apply { fontScale = 2f }
        val context = activity.createConfigurationContext(configuration)
        val clock = androidx.compose.runtime.BroadcastFrameClock()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate + clock)
        val recomposer = androidx.compose.runtime.Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        val vm = dev.ahmed.savlino.ui.LinkViewModel(androidx.lifecycle.SavedStateHandle()).apply { automaticOptions = false }
        var pasteClicks = 0
        try {
            activity.setContent(parent = recomposer) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.ui.platform.LocalContext provides context,
                    androidx.compose.ui.platform.LocalConfiguration provides configuration,
                    androidx.compose.ui.platform.LocalLayoutDirection provides if (configuration.locales[0].language == "ar") androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr) {
                    dev.ahmed.savlino.ui.theme.NezzelTheme(theme, false) {
                        HomeLinkCard(vm) { pasteClicks++; vm.paste("not a URL") }
                    }
                }
            }
            controller.visible()
            fun allTexts(node: SemanticsNode): List<String> = descendants(node).flatMap {
                it.config.getOrNull(SemanticsProperties.Text).orEmpty()
            }.map { it.text }
            fun current(): List<SemanticsNode> {
                Snapshot.sendApplyNotifications(); clock.sendFrame(System.nanoTime())
                return semantics(activity)
            }
            // In-field paste/clear are icon buttons: their accessible name is the content description.
            // Take the innermost clickable: the text field itself is clickable and contains them.
            fun action(label: String): SemanticsNode = current().last {
                it.config.getOrNull(SemanticsActions.OnClick) != null && (label in allTexts(it) ||
                    descendants(it).any { n -> label in n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            }
            assertFalse(current().any { context.getString(R.string.check_link) in allTexts(it) })
            val paste = action(context.getString(R.string.paste_link))
            assertTrue(paste.boundsInRoot.height >= 48f * activity.resources.displayMetrics.density)
            assertEquals(true, paste.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertEquals(1, pasteClicks)
            val check = action(context.getString(R.string.check_link))
            assertTrue(check.boundsInRoot.height >= 48f * activity.resources.displayMetrics.density)
            assertEquals(true, check.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertTrue(vm.invalid); assertNull(vm.takeConfirmedCommand())
            assertEquals(true, action(context.getString(R.string.clear_link)).config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertEquals("", vm.text)
            assertNotNull(action(context.getString(R.string.paste_link)))
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }
}
