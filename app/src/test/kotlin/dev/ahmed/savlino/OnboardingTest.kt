package dev.ahmed.savlino

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.jobs.JobStore
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.OnboardingScreen
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.robolectric.RuntimeEnvironment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h800dp-mdpi", shadows = [CountingClipboard::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Before fun freshInstall() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as dev.ahmed.savlino.jobs.IsolatedTestApplication
        app.getSharedPreferences("nezzel", 0).edit().clear().commit()
        app.coordinatorOverride = dev.ahmed.savlino.jobs.DownloadCoordinator(app, engine = dev.ahmed.savlino.engine.FakeEngine())
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @Test fun firstLaunchShowsTourBeforeAnyClipboardRead() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            val clipboard = controller.get().getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("text", "https://example.com/video"))
            controller.windowFocusChanged(true)
            assertEquals(0, Shadow.extract<CountingClipboard>(clipboard).reads)
            assertEquals(0, (controller.get().application as dev.ahmed.savlino.jobs.IsolatedTestApplication).maintenanceRequests)
            compose.onNodeWithText("Save from a link").assertIsDisplayed()
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun skipPersistsAndNextLaunchOpensHome() {
        withActivity { controller ->
            compose.onNodeWithText("Skip").assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithTag("onboarding").assertDoesNotExist()
            assertTrue(AppPreferences(controller.get()).onboardingSeen)
        }
        withActivity { compose.onNodeWithTag("onboarding").assertDoesNotExist() }
    }

    @Test fun startPersistsAfterAllFourPages() = withActivity { controller ->
        next(); compose.onNodeWithText("Bring a link").assertIsDisplayed()
        next(); compose.onNodeWithText("Choose what to save").assertIsDisplayed()
        next(); compose.onNodeWithText("Tap to save").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertDoesNotExist()
        compose.onNodeWithText("Get started").assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(AppPreferences(controller.get()).onboardingSeen)
        compose.onNodeWithTag("onboarding").assertDoesNotExist()
    }

    @Test fun existingJobsSkipPermanentlyEvenAfterHistoryIsCleared() {
        val app = RuntimeEnvironment.getApplication()
        JobStore(app).use { store ->
            val source = LinkRevision(LinkInput.parse("https://example.com/video")!!, 1)
            val format = Format("hd", "mp4", true, true, height = 720)
            val command = (DownloadCommand.confirm("prior-job", Inspection(source, "engine", listOf(format)),
                source, format.id, "video") as ConfirmResult.Confirmed).command
            assertTrue(store.enqueue(command)); store.fail("prior-job", DownloadError.Network)
            withActivity { compose.onNodeWithTag("onboarding").assertDoesNotExist() }
            assertTrue(AppPreferences(app).onboardingSeen)
            store.removeFailed("prior-job")
        }
        withActivity { compose.onNodeWithTag("onboarding").assertDoesNotExist() }
    }

    @Test fun seenInstallOpensHome() {
        AppPreferences(RuntimeEnvironment.getApplication()).markOnboardingSeen()
        withActivity { compose.onNodeWithTag("onboarding").assertDoesNotExist() }
    }

    @Test fun aboutReplaysAndReturnsToSettingsWithoutReadingClipboard() {
        AppPreferences(RuntimeEnvironment.getApplication()).markOnboardingSeen()
        withActivity { controller ->
            controller.get().links.navigate("Settings")
            compose.onNodeWithText("How it works").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithText("Save from a link").assertIsDisplayed()
            val clipboard = controller.get().getSystemService(ClipboardManager::class.java)
            val before = Shadow.extract<CountingClipboard>(clipboard).reads
            controller.windowFocusChanged(true)
            assertEquals(before, Shadow.extract<CountingClipboard>(clipboard).reads)
            compose.onNodeWithText("Skip").performClick()
            assertEquals("Settings", controller.get().links.destination)
        }
    }

    @Test fun shareLaunchSkipsTourMarksSeenAndOpensOptions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java, share("https://example.com/first")).setup()
        try {
            assertShareOpened(controller.get(), "https://example.com/first")
            assertTrue((controller.get().application as dev.ahmed.savlino.jobs.IsolatedTestApplication).maintenanceRequests > 0)
        } finally { controller.pause().stop().destroy() }
        withActivity { compose.onNodeWithTag("onboarding").assertDoesNotExist() }
    }

    @Test fun shareDuringTourFinishesItAndOpensOptionsBeforeClipboardIntake() = withActivity { controller ->
        compose.onNodeWithText("Save from a link").assertIsDisplayed()
        val clipboard = controller.get().getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("text", "https://example.com/clipboard"))
        controller.windowFocusChanged(true)
        controller.newIntent(share("https://example.com/latest"))
        assertShareOpened(controller.get(), "https://example.com/latest")
        assertEquals(0, Shadow.extract<CountingClipboard>(clipboard).reads)
    }

    @Test fun shareDuringAboutReplayFinishesItAndOpensOptions() {
        AppPreferences(RuntimeEnvironment.getApplication()).markOnboardingSeen()
        withActivity { controller ->
            controller.get().links.navigate("Settings")
            compose.onNodeWithText("How it works").performScrollTo().performClick()
            compose.onNodeWithText("Save from a link").assertIsDisplayed()
            controller.newIntent(share("https://example.com/replay"))
            assertShareOpened(controller.get(), "https://example.com/replay")
        }
    }

    @Test fun shareLaunchSkipsRestoredAboutReplay() {
        AppPreferences(RuntimeEnvironment.getApplication()).markOnboardingSeen()
        val controller = Robolectric.buildActivity(MainActivity::class.java, share("https://example.com/replay"))
            .create(Bundle().apply { putBoolean("onboardingReplay", true) }).start().resume().visible()
        try { assertShareOpened(controller.get(), "https://example.com/replay") }
        finally { controller.pause().stop().destroy() }
    }

    private fun assertShareOpened(activity: MainActivity, url: String) {
        compose.onNodeWithTag("onboarding").assertDoesNotExist()
        assertTrue(AppPreferences(activity).onboardingSeen)
        assertEquals("Home", activity.links.destination)
        assertEquals(url, activity.links.text)
        assertNotEquals(dev.ahmed.savlino.ui.OptionsState.Hidden, activity.links.optionsState)
    }

    @Test fun backReturnsToPreviousPageAndRecreationRetainsPage() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        next(); next(); next()
        val state = Bundle()
        first.pause().saveInstanceState(state).stop().destroy()
        val restored = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        try {
            compose.onNodeWithText("Tap to save").assertIsDisplayed()
            compose.runOnIdle { restored.get().onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Choose what to save").assertIsDisplayed()
            assertFalse(AppPreferences(restored.get()).onboardingSeen)
        } finally { restored.pause().stop().destroy() }
    }

    @Test fun englishSwipesForwardToCopy() = screen(rtl = false) {
        compose.onNodeWithTag("onboarding_pager").performTouchInput { swipeLeft() }
        compose.onNodeWithText("Bring a link").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "ar-w360dp-h800dp-mdpi")
    fun arabicSwipesForwardInMirroredDirection() = screen(rtl = true) {
        compose.onNodeWithText("احفظ من رابط").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_pager").performTouchInput { swipeRight() }
        compose.onNodeWithText("أضف رابطًا").assertIsDisplayed()
        compose.onNodeWithContentDescription("الصفحة 2 من 4، أضف رابطًا").assertExists()
    }

    @Test fun pagePositionAnnouncesSettledHeadingAndBackReturns() = screen {
        fun assertPosition(page: Int, title: String) {
            compose.onNodeWithTag("onboarding_position")
                .assertContentDescriptionEquals("Page $page of 4, $title")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,
                    androidx.compose.ui.semantics.LiveRegionMode.Polite))
        }
        assertPosition(1, "Save from a link")
        compose.onNodeWithText("Back").assertDoesNotExist()
        next()
        assertPosition(2, "Bring a link")
        compose.onNodeWithText("Back").assertHeightIsAtLeast(48.dp).performClick()
        assertPosition(1, "Save from a link")
    }

    @Test fun completeIllustrationsStayStillWithAnimationsEnabled() {
        val app = RuntimeEnvironment.getApplication()
        android.provider.Settings.Global.putFloat(app.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        compose.mainClock.autoAdvance = false
        try {
            screen {
                for (page in 0..3) {
                    val description = app.getString(listOf(R.string.onboarding_anywhere_illustration,
                        R.string.onboarding_copy_illustration, R.string.onboarding_quality_illustration,
                        R.string.onboarding_saved_illustration)[page])
                    compose.onNodeWithContentDescription(description).assertIsDisplayed()
                    if (page == 1) compose.onNodeWithText(app.getString(R.string.onboarding_example_url),
                        useUnmergedTree = true).assertIsDisplayed()
                    if (page == 2) compose.onNodeWithText("Example", useUnmergedTree = true).assertIsDisplayed()
                    if (page == 3) {
                        compose.onNodeWithTag("tour_saved_check", useUnmergedTree = true).assertIsDisplayed()
                        compose.onNodeWithTag("tour_downloads", useUnmergedTree = true).assertIsDisplayed()
                        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                            useUnmergedTree = true).assertCountEquals(0)
                    }
                    compose.mainClock.advanceTimeBy(1000)
                    val before = tourPixels()
                    compose.mainClock.advanceTimeBy(6000)
                    assertArrayEquals("Page $page must stay complete", before, tourPixels())
                    if (page < 3) {
                        compose.onNodeWithText("Next").performClick()
                        compose.mainClock.advanceTimeBy(1000)
                    }
                }
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun fixedMediaSymbolsNeverOverlapAtSupportedFontScales() = checkMediaBounds(false)
    @Test @Config(qualifiers = "ar-w360dp-h800dp-mdpi")
    fun arabicFixedMediaSymbolsNeverOverlapAtSupportedFontScales() = checkMediaBounds(true)
    @Test @Config(qualifiers = "en-w800dp-h360dp-land-mdpi")
    fun landscapeFixedMediaSymbolsNeverOverlapAtSupportedFontScales() = checkMediaBounds(false)
    @Test @Config(qualifiers = "ar-w800dp-h360dp-land-mdpi")
    fun arabicLandscapeFixedMediaSymbolsNeverOverlapAtSupportedFontScales() = checkMediaBounds(true)

    private fun checkMediaBounds(rtl: Boolean) {
        for (scale in listOf(1f, 1.5f, 2f)) screen(rtl = rtl, fontScale = scale) {
            val art = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Image)).fetchSemanticsNode().boundsInRoot
            val bounds = listOf("tour_mark", "tour_video", "tour_audio", "tour_photo").map {
                compose.onNodeWithTag(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            }
            bounds.forEach {
                assertTrue("Visible symbol at $scale", it.width > 0 && it.height > 0)
                assertTrue("Symbol stays inside art at $scale", it.left >= art.left && it.top >= art.top &&
                    it.right <= art.right && it.bottom <= art.bottom)
            }
            bounds.forEachIndexed { i, a -> bounds.drop(i + 1).forEach { b ->
                assertFalse("Symbols overlap at $scale", a.overlaps(b))
            } }
        }
    }

    @Test fun inactiveDotsUseOutlineWithThreeToOneContrastInBothThemes() {
        for (dark in listOf(false, true)) screen(rtl = dark) {
            val scheme = if (dark) dev.ahmed.savlino.ui.theme.NezzelDarkColors
                else dev.ahmed.savlino.ui.theme.NezzelLightColors
            val bounds = compose.onNodeWithTag("onboarding_dot_1", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInWindow
            val view = requireNotNull(renderView)
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height,
                android.graphics.Bitmap.Config.ARGB_8888)
            try {
                compose.runOnIdle { view.draw(android.graphics.Canvas(bitmap)) }
                assertEquals(scheme.outline.toArgb(), bitmap.getPixel(bounds.center.x.toInt(), bounds.center.y.toInt()))
            } finally { bitmap.recycle() }
            val a = scheme.outline.luminance()
            val b = scheme.surface.luminance()
            assertTrue("Inactive dots need >=3:1", (maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 3f)
        }
    }

    private var renderView: android.view.View? = null

    private fun tourPixels(): IntArray {
        compose.waitForIdle()
        val bounds = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role,
            androidx.compose.ui.semantics.Role.Image)).fetchSemanticsNode().boundsInWindow
        val width = bounds.width.toInt(); val height = bounds.height.toInt()
        val view = requireNotNull(renderView)
        val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            compose.runOnIdle { view.draw(android.graphics.Canvas(image)) }
            // Compare the illustration, excluding the navigation button's ordinary pressed ripple.
            return IntArray(width * height).also {
                image.getPixels(it, 0, width, bounds.left.toInt(), bounds.top.toInt(), width, height)
                assertTrue("Capture must contain rendered content", it.toSet().size > 20)
            }
        } finally { image.recycle() }
    }

    @Test fun portraitDoubleTextKeepsCopyAndActionsReachable() = screen(fontScale = 2f) { checkPages(false) }
    @Test @Config(qualifiers = "en-w800dp-h360dp-land-mdpi")
    fun landscapeIllustrationCardsFitPagerAtLargeText() = screen(fontScale = 1.3f) {
        next(); next()
        repeat(2) { index ->
            val card = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Image)).fetchSemanticsNode()
            val pager = compose.onNodeWithTag("onboarding_pager").fetchSemanticsNode().boundsInRoot
            // Use the unclipped position + measured size: boundsInRoot alone hides viewport clipping.
            assertTrue("Illustration top leaves pager", card.positionInRoot.y >= pager.top - .5f)
            assertTrue("Illustration bottom leaves pager",
                card.positionInRoot.y + card.size.height <= pager.bottom + .5f)
            if (index == 0) next()
        }
    }
    @Test @Config(qualifiers = "en-w800dp-h360dp-mdpi")
    fun landscapeDoubleTextKeepsCopyAndActionsReachable() = screen(fontScale = 2f) { checkPages(false) }
    @Test @Config(qualifiers = "ar-w800dp-h360dp-mdpi")
    fun arabicDarkLandscapeDoubleTextKeepsCopyAndActionsReachable() = screen(rtl = true, fontScale = 2f) { checkPages(true) }

    @Test fun englishArabicResourcesHaveMatchingKeysAndShortCopy() {
        val res = java.io.File("src/main/res").takeIf { it.isDirectory } ?: java.io.File("app/src/main/res")
        fun strings(locale: String): Map<String, String> {
            val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(java.io.File(res, "$locale/strings.xml"))
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
                .filter { it.getAttribute("name").startsWith("onboarding_") }
                .associate { it.getAttribute("name") to it.textContent }
        }
        val english = strings("values"); val arabic = strings("values-ar")
        val expectedEnglish = mapOf(
            "onboarding_anywhere_title" to "Save from a link",
            "onboarding_anywhere_body" to "Save videos, audio and photos from supported public links, with no ads or Nezzel account",
            "onboarding_anywhere_illustration" to "Video, audio and photo symbols beside Nezzel",
            "onboarding_copy_title" to "Bring a link",
            "onboarding_copy_body" to "Copy a link and open Nezzel, paste it here, or choose Nezzel from Share\\n\\nOptions and previews load automatically by default — you can turn this off in Settings",
            "onboarding_copy_illustration" to "Copy or share a link to open it in Nezzel",
            "onboarding_quality_title" to "Choose what to save",
            "onboarding_quality_body" to "Video and audio options vary by link, and you choose which photos to save — sizes may be estimated or unknown",
            "onboarding_quality_illustration" to "Example: select a video quality or photos to save",
            "onboarding_saved_title" to "Tap to save",
            "onboarding_saved_body" to "Tap Download for video or audio, or Save for photos — find your files in Downloads and change the save folder in Settings",
            "onboarding_saved_illustration" to "Example: a saved file appears in Downloads",
            "onboarding_skip" to "Skip",
            "onboarding_next" to "Next",
            "onboarding_back" to "Back",
            "onboarding_start" to "Get started"
        )
        val expectedArabic = mapOf(
            "onboarding_anywhere_title" to "احفظ من رابط",
            "onboarding_anywhere_body" to "احفظ الفيديو والصوت والصور من الروابط العامة المدعومة بلا إعلانات أو حساب في Nezzel",
            "onboarding_anywhere_illustration" to "رموز الفيديو والصوت والصور بجانب Nezzel",
            "onboarding_copy_title" to "أضف رابطًا",
            "onboarding_copy_body" to "انسخ رابطًا وافتح Nezzel، أو الصقه هنا، أو اختر Nezzel من قائمة المشاركة\\n\\nيجلب التطبيق الخيارات والمعاينات تلقائيًا، ويمكنك إيقاف ذلك من الإعدادات",
            "onboarding_copy_illustration" to "انسخ رابطًا أو شاركه لفتحه في Nezzel",
            "onboarding_quality_title" to "اختر ما تريد حفظه",
            "onboarding_quality_body" to "تختلف خيارات الفيديو والصوت حسب الرابط، وللصور حدّد ما تريد حفظه، وقد يكون الحجم تقديريًا أو غير معروف",
            "onboarding_quality_illustration" to "مثال: اختيار جودة فيديو أو صور لحفظها",
            "onboarding_saved_title" to "اضغط للحفظ",
            "onboarding_saved_body" to "اضغط «تنزيل» للفيديو أو الصوت، أو «حفظ» للصور، وستجد ملفاتك في «التنزيلات»، ويمكنك تغيير مجلد الحفظ من الإعدادات",
            "onboarding_saved_illustration" to "مثال: ظهور ملف محفوظ في التنزيلات",
            "onboarding_skip" to "تخطّي",
            "onboarding_next" to "التالي",
            "onboarding_back" to "السابق",
            "onboarding_start" to "ابدأ"
        )
        for ((actual, expected) in listOf(english to expectedEnglish, arabic to expectedArabic)) {
            expected.forEach { (key, value) -> assertEquals(key, value, actual[key]) }
        }
        assertEquals(english.keys, arabic.keys)
        assertTrue(english.isNotEmpty())
        for (copy in listOf(english, arabic)) for ((key, value) in copy) {
            assertTrue(value.isNotBlank())
            assertFalse("Latin digits only", value.any { it in '٠'..'٩' })
            val words = value.trim().split(Regex("\\s+")).size
            if (key.endsWith("_title")) assertTrue("$key: $words", words <= 4)
            if (key.endsWith("_body")) assertTrue("$key: $words", words <= 50)
        }
    }

    private fun checkPages(arabic: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        for ((index, body) in listOf(R.string.onboarding_anywhere_body, R.string.onboarding_copy_body, R.string.onboarding_quality_body, R.string.onboarding_saved_body).withIndex()) {
            val text = compose.onNodeWithText(app.getString(body)).performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Body must wrap without clipping", layouts.single().hasVisualOverflow)
            compose.onNodeWithText(app.getString(if (index == 3) R.string.onboarding_start else R.string.onboarding_next))
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            if (index < 3) compose.onNodeWithText(if (arabic) "التالي" else "Next").performClick()
        }
    }
    private fun next() {
        compose.onNodeWithText("Next").performClick()
        if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }
    private fun share(url: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
    private fun withActivity(block: (org.robolectric.android.controller.ActivityController<MainActivity>) -> Unit) {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try { block(controller) } finally { controller.pause().stop().destroy() }
    }
    private fun screen(rtl: Boolean = false, fontScale: Float = 1f, block: () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            controller.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    NezzelTheme(if (rtl) ThemePref.Dark else ThemePref.Light, false) { OnboardingScreen {} }
                }
            }
            renderView = controller.get().window.decorView
            block()
        } finally { renderView = null; controller.pause().stop().destroy() }
    }

}

/** Runs the real Application startup, substituting only the native/network engine. */
class OnboardingStartupApplication : dev.ahmed.savlino.jobs.NezzelApplication() {
    val updated = kotlinx.coroutines.CompletableDeferred<Unit>()
    val warmed = kotlinx.coroutines.CompletableDeferred<Boolean>()
    val inspections = java.util.concurrent.atomic.AtomicInteger()
    val transfers = java.util.concurrent.atomic.AtomicInteger()
    val journal by lazy { JobStore(this) }
    override val downloads by lazy {
        val engine = object : dev.ahmed.savlino.engine.EngineAdapter by dev.ahmed.savlino.engine.FakeEngine() {
            override suspend fun warmUp(): Boolean {
                warmed.complete(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
                return true
            }
            override suspend fun inspect(source: dev.ahmed.savlino.domain.LinkRevision, operationId: String): dev.ahmed.savlino.engine.InspectResult {
                inspections.incrementAndGet()
                return dev.ahmed.savlino.engine.InspectResult.Failure(dev.ahmed.savlino.domain.DownloadError.NoFormats)
            }
            override suspend fun download(command: dev.ahmed.savlino.domain.DownloadCommand, directory: java.io.File,
                progress: (dev.ahmed.savlino.engine.EngineEvent) -> Unit): dev.ahmed.savlino.engine.TransferResult {
                transfers.incrementAndGet()
                return dev.ahmed.savlino.engine.TransferResult.Failure(dev.ahmed.savlino.domain.DownloadError.Failed)
            }
            override suspend fun installedVersion() = "test-engine"
            override suspend fun updateYtdlp(): dev.ahmed.savlino.engine.EngineUpdateResult {
                updated.complete(Unit)
                return dev.ahmed.savlino.engine.EngineUpdateResult.Success("test-engine", "test-engine")
            }
        }
        dev.ahmed.savlino.jobs.DownloadCoordinator(this, engine, store = journal)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = OnboardingStartupApplication::class)
class OnboardingStartupTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun applicationDefersAutomaticNetworkMaintenanceUntilTourFinishes() = kotlinx.coroutines.runBlocking {
        val app = RuntimeEnvironment.getApplication() as OnboardingStartupApplication
        // Join real startup jobs: this also catches maintenance being added back to Application.onCreate.
        app.scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.join() }
        assertTrue("App start must warm locally without a link, off Main", app.warmed.isCompleted)
        assertTrue(app.warmed.await())
        assertEquals("test-engine", app.downloads.maintenance.state.value.version)
        assertEquals(0, app.inspections.get())
        assertEquals(0, app.transfers.get())
        assertFalse(app.updated.isCompleted)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            compose.onNodeWithText("Save from a link").assertIsDisplayed()
            assertFalse(app.updated.isCompleted)
            compose.onNodeWithText("Skip").performClick()
            assertFalse(app.updated.isCompleted)
            app.downloads.maintenance.withInspection { } // First attempt ends, even if it found no media.
            kotlinx.coroutines.withTimeout(5000) { app.updated.await() }
            assertTrue(app.getSharedPreferences("engine-maintenance", 0).contains("attempt"))
        } finally {
            controller.pause().stop().destroy()
            app.scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            app.scope.coroutineContext[kotlinx.coroutines.Job]!!.join()
            app.journal.close()
        }
    }
}
