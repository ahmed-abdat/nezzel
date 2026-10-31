package dev.ahmed.savlino.ui

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.SavedStateHandle
import dev.ahmed.savlino.NezzelApp
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.domain.LinkRevision
import dev.ahmed.savlino.domain.MediaImageFormat
import dev.ahmed.savlino.domain.MediaImageMetadata
import dev.ahmed.savlino.domain.MediaInspection
import dev.ahmed.savlino.domain.MediaItem
import dev.ahmed.savlino.domain.MediaRendition
import dev.ahmed.savlino.engine.UnavailableEngine
import dev.ahmed.savlino.engine.mediaimage.ImageFailure
import dev.ahmed.savlino.engine.mediaimage.ImagePhase
import dev.ahmed.savlino.engine.mediaimage.MediaInspectResult
import dev.ahmed.savlino.entry.ShareInput
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.media.ImageSaveState
import dev.ahmed.savlino.media.ImageSaver
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import dev.ahmed.savlino.ui.theme.NezzelTheme
import java.io.File
import java.util.Locale
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Sheet body in place (EN/AR, 320dp, 200% text) and the saved snackbar through NezzelApp. JVM evidence only. */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ImageOptionsUiTest {
    private val url = "https://www.linkedin.com/posts/someone_title-activity-7380000000000000000-AbCd"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var saver: ImageSaver
    @After fun tearDown() { scope.cancel() }

    private fun vm(activity: ComponentActivity, found: Int, previewQuality: Boolean = false, link: String = url,
        result: (LinkRevision) -> MediaInspectResult = { imageSuccess(it, found = found, previewQuality = previewQuality) }): LinkViewModel {
        saver = ImageSaver(FakeFetch(), RecordingPublisher(), File(activity.noBackupFilesDir, "image-save").apply { deleteRecursively() },
            noSpaceCheck, scope)
        return LinkViewModel(SavedStateHandle(), engine = UnavailableEngine(), images = FakeImages(result),
            imageSaver = saver, previewDispatcher = kotlinx.coroutines.Dispatchers.Unconfined, previewImage = ::testBitmap).also {
            it.onNewShare(ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)))
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun configured(activity: ComponentActivity, language: String): Pair<Configuration, Context> {
        val configuration = Configuration(activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag("$language-u-nu-latn")); fontScale = 2f; screenWidthDp = 320
        }
        return configuration to activity.createConfigurationContext(configuration)
    }
    private fun clickable(nodes: List<SemanticsNode>, label: String): SemanticsNode {
        // The innermost clickable carrying the label, never a container that merely encloses it.
        val found = nodes.filter { node ->
            node.config.getOrNull(SemanticsActions.OnClick) != null && texts(descendants(node)).any { it.startsWith(label) }
        }.minByOrNull { descendants(it).size }
        assertNotNull("no clickable '$label' in ${texts(nodes)}", found)
        return found!!
    }

    @Test fun englishSheetIsHonestAboutTheLinkAndTheImage() = assertSheet("en", "Shared link")
    @Test @Config(qualifiers = "ar") fun arabicSheetIsHonestAboutTheLinkAndTheImage() = assertSheet("ar", "رابط شاركته")

    @Test fun englishSeparatePreviewNeverSuppliesSavedImageDimensions() = assertUnknownDimensions("en")
    @Test @Config(qualifiers = "ar") fun arabicSeparatePreviewNeverSuppliesSavedImageDimensions() = assertUnknownDimensions("ar")

    private fun assertUnknownDimensions(language: String) {
        val (labels, context) = sheetLabels(language) { activity ->
            vm(activity, found = 1, link = "https://bsky.app/profile/synthetic.test/post/3synthetic001") { source ->
                val result = imageSuccess(source, preview = TestPreview(188, 250))
                val item = result.inspection.items.single()
                val rendition = MediaRendition.Image(item.renditions.single().ref, MediaImageMetadata(MediaImageFormat.JPEG))
                val inspection = MediaInspection(source, result.inspection.identity,
                    listOf(MediaItem(item.ref, 0, item.sourceKind, item.metadata, listOf(rendition))),
                    result.inspection.validity)
                MediaInspectResult.Success(inspection, result.preview, result.discovery, result.saveAvailability,
                    mapOf(rendition.ref to result.preview!!)) { result.close() }
            }
        }
        assertTrue(labels.toString(), NezzelFormat.isolate("JPEG") in labels)
        assertFalse(labels.toString(), labels.any { it.contains("×") })
        assertTrue(labels.toString(), context.getString(R.string.image_save) in labels)
    }

    private fun assertSheet(language: String, header: String) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        val clock = androidx.compose.runtime.BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = androidx.compose.runtime.Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        fun frames() {
            repeat(4) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                clock.sendFrame(System.nanoTime())
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            }
        }
        try {
            val activity = controller.get()
            val vm = vm(activity, found = 3)
            val (configuration, context) = configured(activity, language)
            activity.setContent(parent = recomposer) {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                    LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    NezzelTheme(ThemePref.Dark, false) { OptionsSheetContent(vm, language, vm::dismissOptions) }
                }
            }
            frames()
            val nodes = semanticsNodes(activity, rounds = 4, idleMillis = 200)
            val labels = texts(nodes)
            assertTrue(labels.toString(), labels.contains(header))
            assertFalse(labels.toString(), labels.any { it.contains("video", ignoreCase = true) || it.contains("فيديو") })
            // W × H is ONE LTR isolate: separately isolated numbers flip inside an RTL paragraph.
            val dims = NezzelFormat.isolate(context.getString(R.string.saved_image_dimensions,
                NezzelFormat.count(1080, language), NezzelFormat.count(1350, language)))
            assertFalse(labels.toString(), labels.contains(dims + context.getString(R.string.separator) + NezzelFormat.isolate("JPEG")))
            val descriptions = nodes.mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() }
            for (n in 1..3) assertTrue(descriptions.toString(), descriptions.contains(context.getString(
                R.string.image_thumbnail_desc, NezzelFormat.count(n, language), NezzelFormat.count(3, language))))
            assertTrue(labels.toString(), labels.contains(context.resources.getQuantityString(R.plurals.image_available_count,
                3, NezzelFormat.count(3, language))))
            assertFalse(labels.toString(), labels.contains(context.getString(R.string.image_preview_quality)))
            val save = clickable(nodes, context.resources.getQuantityString(R.plurals.image_save_count, 3, NezzelFormat.count(3, language)))
            val density = context.resources.displayMetrics.density
            assertTrue("Save target ${save.boundsInRoot}", save.boundsInRoot.height / density >= 48f)
            assertTrue(save.config.getOrNull(SemanticsProperties.Disabled) == null)
            val thumbnails = nodes.filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertEquals(3, thumbnails.size)
            assertTrue(thumbnails.all { it.config.getOrNull(SemanticsProperties.ToggleableState) == androidx.compose.ui.state.ToggleableState.On })
            assertTrue(thumbnails.all { it.boundsInRoot.width / density >= 48f && it.boundsInRoot.height / density >= 48f })
            val ordered = thumbnails.sortedBy { it.config.getOrNull(SemanticsProperties.ContentDescription)?.first() }
            assertTrue(if (language == "ar") ordered[0].boundsInRoot.left > ordered[1].boundsInRoot.left
                else ordered[0].boundsInRoot.left < ordered[1].boundsInRoot.left)
            assertEquals(true, thumbnails.first().config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertEquals(2, vm.selectedImageRefs.size)
            frames()
            val toggled = semanticsNodes(activity)
            assertNotNull(clickable(toggled, context.resources.getQuantityString(R.plurals.image_save_count, 2,
                NezzelFormat.count(2, language))))
            assertEquals(true, clickable(toggled, context.getString(R.string.image_select_all))
                .config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            frames()
            assertEquals(true, clickable(semanticsNodes(activity), context.getString(R.string.image_select_none))
                .config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertTrue(vm.selectedImageRefs.isEmpty())
            frames()
            val empty = semanticsNodes(activity, rounds = 4, idleMillis = 200)
            assertTrue(clickable(empty, context.resources.getQuantityString(R.plurals.image_save_count, 0,
                NezzelFormat.count(0, language))).config.getOrNull(SemanticsProperties.Disabled) != null)
            assertEquals(true, clickable(empty, context.getString(R.string.image_select_all))
                .config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertEquals(3, vm.selectedImageRefs.size)

        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }

    @Test @Config(qualifiers = "ar") fun arabicPartialCountsAvoidIncorrectCountedNouns() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals("المعروض: 1 من أصل 2", context.getString(R.string.image_available_of, "1", "2"))
        assertEquals("تم حفظ 1 من أصل 2، وتعذّر حفظ البقية", context.getString(R.string.image_save_partial_count, "1", "2"))
        assertEquals("أُلغي الحفظ بعد حفظ 1 من أصل 2", context.getString(R.string.image_save_cancelled_count, "1", "2"))
    }

    @Test fun enumeratedButUnselectableSiblingsKeepSinglePhotoFactsAndFullFrame() {
        val (labels, context) = sheetLabels("en") { activity ->
            vm(activity, found = 1) { source ->
                val result = imageSuccess(source)
                MediaInspectResult.Success(result.inspection, result.preview,
                    result.discovery.copy(discoveredCount = 3), result.saveAvailability) { result.close() }
            }
        }
        assertTrue(labels.toString(), context.getString(R.string.image_available_of, "1", "3") in labels)
        assertTrue(labels.toString(), labels.any { it.contains("1080") && it.contains("1350") && it.contains("JPEG") })
        assertFalse(labels.toString(), context.getString(R.string.image_select_all) in labels)
        assertFalse(labels.toString(), context.getString(R.string.image_select_none) in labels)
    }

    @Test fun englishBudgetNoticeDoesNotCallPhotosFailed() = assertBudgetNotice("en")
    @Test @Config(qualifiers = "ar") fun arabicBudgetNoticeDoesNotCallPhotosFailed() = assertBudgetNotice("ar")
    private fun assertBudgetNotice(language: String) {
        for (count in listOf(1, 2, 3, 11, 100)) {
            val (labels, context) = sheetLabels(language) { activity ->
                vm(activity, found = 1) { source ->
                    val result = imageSuccess(source)
                    MediaInspectResult.Success(result.inspection, result.preview,
                        result.discovery.copy(notPreviewedCount = count), result.saveAvailability) { result.close() }
                }
            }
            val formatted = NezzelFormat.count(count, language)
            assertTrue(labels.toString(), context.resources.getQuantityString(R.plurals.image_not_previewed_count, count, formatted) in labels)
            assertFalse(labels.toString(), context.resources.getQuantityString(R.plurals.image_unavailable_count, count, formatted) in labels)
        }
    }

    @Test fun englishAllPreviewsOverBudgetHasDistinctCopy() = assertAllPreviewsOverBudget("en")
    @Test @Config(qualifiers = "ar") fun arabicAllPreviewsOverBudgetHasDistinctCopy() = assertAllPreviewsOverBudget("ar")
    private fun assertAllPreviewsOverBudget(language: String) {
        val (labels, context) = sheetLabels(language) { activity ->
            vm(activity, found = 1) { MediaInspectResult.Failure(ImageFailure.PREVIEW_LIMIT, ImagePhase.IMAGE) }
        }
        assertTrue(labels.toString(), context.getString(R.string.image_preview_limit_title) in labels)
        assertTrue(labels.toString(), context.getString(R.string.image_preview_limit_body) in labels)
        assertFalse(labels.toString(), context.getString(R.string.image_large_title) in labels)
        assertFalse(labels.toString(), context.getString(R.string.try_again) in labels)
    }

    @Test fun englishMixedPhotoFailureNoticeAppearsBeforeVideoOptions() = assertMixedNotice("en")
    @Test @Config(qualifiers = "ar") fun arabicMixedPhotoFailureNoticeAppearsBeforeVideoOptions() = assertMixedNotice("ar")
    private fun assertMixedNotice(language: String) {
        val (labels, context) = sheetLabels(language) { activity ->
            vm(activity, found = 1, link = "https://www.instagram.com/p/SynthPost01/") {
                MediaInspectResult.VideoRedirect(it, photosUnavailable = true)
            }
        }
        assertTrue(labels.toString(), context.getString(R.string.image_photos_unavailable) in labels)
    }

    @Test fun englishLinkedPostCopy() = assertLinkedPostCopy("en")
    @Test @Config(qualifiers = "ar") fun arabicLinkedPostCopy() = assertLinkedPostCopy("ar")
    private fun assertLinkedPostCopy(language: String) {
        val (labels, context) = sheetLabels(language) {
            vm(it, found = 1) { MediaInspectResult.Failure(ImageFailure.LINKED_ELSEWHERE, ImagePhase.PAGE) }
        }
        assertTrue(labels.toString(), labels.contains(context.getString(R.string.image_linked_elsewhere)))
        assertFalse(labels.toString(), labels.contains(context.getString(R.string.try_again)))
    }

    @Test fun englishPreviewQualityNoteAndPrivateCopy() = assertNoteAndPrivate("en", "This post isn’t public")
    @Test @Config(qualifiers = "ar") fun arabicPreviewQualityNoteAndPrivateCopy() = assertNoteAndPrivate("ar", "هذا المنشور ليس عامًا")

    @Test fun englishStoriesOfferOnlyPasteAnotherAndClose() = assertStoryCopy("en",
        "Facebook stories can’t be saved", "Stories need a Facebook login. Nezzel saves public posts, reels and videos")
    @Test @Config(qualifiers = "ar") fun arabicStoriesOfferOnlyPasteAnotherAndClose() = assertStoryCopy("ar",
        "لا يمكن حفظ قصص فيسبوك", "تتطلب القصص تسجيل الدخول إلى فيسبوك. يحفظ Nezzel المنشورات والريلز والفيديوهات العامة")
    private fun assertStoryCopy(language: String, title: String, body: String) {
        for (link in listOf("https://www.facebook.com/stories/123/Synthetic/", "https://www.facebook.com/share/Synthetic/")) {
            val (labels, context) = sheetLabels(language) { activity ->
                vm(activity, found = 1, link = link) { MediaInspectResult.Failure(ImageFailure.FACEBOOK_STORY, ImagePhase.PAGE) }
            }
            assertTrue(labels.toString(), title in labels)
            assertTrue(labels.toString(), body in labels)
            for (shown in listOf(R.string.paste_another_link, R.string.nezzel_close))
                assertTrue(labels.toString(), context.getString(shown) in labels)
            for (hidden in listOf(R.string.try_again, R.string.engine_update_retry))
                assertFalse(labels.toString(), context.getString(hidden) in labels)
        }
    }

    /** A reduced copy is labelled; a login wall reads as "not public", never as a broken link. */
    private fun assertNoteAndPrivate(language: String, privateTitle: String) {
        val (note, context) = sheetLabels(language) { vm(it, found = 1, previewQuality = true) }
        assertTrue(note.toString(), note.contains(context.getString(R.string.image_preview_quality)))
        val (private, _) = sheetLabels(language) { vm(it, found = 1) { MediaInspectResult.Failure(ImageFailure.PRIVATE, ImagePhase.PAGE) } }
        assertTrue(private.toString(), private.contains(privateTitle))
        assertTrue(private.toString(), private.contains(context.getString(R.string.private_body)))
        assertFalse(private.toString(), private.contains(context.getString(R.string.try_again)))
    }

    @Test fun englishSourceRefusalAndImageTypeCopy() = assertRefusalCopy("en", "Can’t read this post",
        "Nezzel couldn’t read the image in this post — try again later", "Image type not supported")
    @Test @Config(qualifiers = "ar") fun arabicSourceRefusalAndImageTypeCopy() = assertRefusalCopy("ar", "تعذّرت قراءة هذا المنشور",
        "لم يستطع Nezzel قراءة الصورة في هذا المنشور، أعد المحاولة لاحقًا", "نوع الصورة غير مدعوم")

    /** A refused post/link never reads as an image-type problem; a real unsupported type still does. */
    private fun assertRefusalCopy(language: String, title: String, body: String, typeTitle: String) {
        val (refused, context) = sheetLabels(language) {
            vm(it, found = 1) { MediaInspectResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE) }
        }
        assertTrue(refused.toString(), refused.contains(title) && refused.contains(body))
        assertTrue(refused.toString(), refused.contains(context.getString(R.string.try_again)))
        assertFalse(refused.toString(), refused.contains(typeTitle))
        val (type, _) = sheetLabels(language) { vm(it, found = 1) { MediaInspectResult.Failure(ImageFailure.UNSUPPORTED, ImagePhase.IMAGE) } }
        assertTrue(type.toString(), type.contains(typeTitle) && type.contains(context.getString(R.string.image_type_body)))
        assertFalse(type.toString(), type.contains(title))
    }

    @Test fun englishDirectImageRefusalSaysImageNotPost() = assertDirectRefusal("en", "Can’t read this image",
        "Nezzel couldn’t read this image — try again later", "Can’t read this post")
    @Test @Config(qualifiers = "ar") fun arabicDirectImageRefusalSaysImageNotPost() = assertDirectRefusal("ar",
        "تعذّرت قراءة هذه الصورة", "لم يستطع Nezzel قراءة هذه الصورة، أعد المحاولة لاحقًا", "تعذّرت قراءة هذا المنشور")

    private fun assertDirectRefusal(language: String, title: String, body: String, postTitle: String) {
        val (labels, context) = sheetLabels(language) {
            vm(it, found = 1, link = "https://example.com/photos/photo.jpg") {
                MediaInspectResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
            }
        }
        assertTrue(labels.toString(), labels.contains(title) && labels.contains(body))
        assertTrue(labels.toString(), labels.contains(context.getString(R.string.try_again)))
        assertFalse(labels.toString(), labels.contains(postTitle))
    }

    /** One fresh activity per sheet, so a previous composition can never answer for the next. */
    private fun sheetLabels(language: String, build: (ComponentActivity) -> LinkViewModel): Pair<List<String>, Context> =
        sheetNodes(language, build = build).let { (nodes, context) -> texts(nodes) to context }
    private fun sheetNodes(language: String,
        check: ((LinkViewModel, List<SemanticsNode>, Context) -> Unit)? = null,
        build: (ComponentActivity) -> LinkViewModel): Pair<List<SemanticsNode>, Context> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        try {
            val activity = controller.get()
            val vm = build(activity)
            val (configuration, context) = configured(activity, language)
            activity.setContent {
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                    LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    NezzelTheme(ThemePref.Light, false) { OptionsSheetContent(vm, language, vm::dismissOptions) }
                }
            }
            val nodes = semanticsNodes(activity, rounds = 4, idleMillis = 200)
            check?.invoke(vm, nodes, context) // Parent click semantics must be read while the composition is attached.
            return nodes to context
        } finally { controller.pause().stop().destroy() }
    }

    /** The sound tile is a checkbox above the grid; the one Save names what will be saved. */
    @Test fun englishSoundChoiceNamesTheSave() = assertSoundChoice("en")
    @Test @Config(qualifiers = "ar") fun arabicSoundChoiceNamesTheSave() = assertSoundChoice("ar")
    private fun assertSoundChoice(language: String) {
        val tiktok = "https://www.tiktok.com/@synthetic/photo/900000001"
        for ((photos, sound) in listOf(true to false, true to true, false to true)) {
            sheetNodes(language, check = { vm, nodes, context ->
                val n = NezzelFormat.count(3, language)
                val expected = when {
                    !sound -> context.resources.getQuantityString(R.plurals.image_save_count, 3, n)
                    photos -> context.resources.getQuantityString(R.plurals.image_save_count_sound, 3, n)
                    else -> context.getString(R.string.sound_save)
                }
                val labels = texts(nodes)
                assertTrue(labels.toString(), expected in labels)
                assertFalse(vm.imageSaving)
                assertTrue(vm.imageSaveEnabled)
                assertNull(clickable(nodes, expected).config.getOrNull(SemanticsProperties.Disabled))
                val onlySound = clickable(nodes, context.getString(R.string.image_save_sound_only))
                assertNull(onlySound.config.getOrNull(SemanticsProperties.Disabled))
                assertTrue(onlySound.size.height / context.resources.displayMetrics.density >= 48f)
                // The sound checkbox sits beside the photo tiles, which carry thumbnail descriptions.
                val tile = nodes.single { it.config.getOrNull(SemanticsProperties.Role) == Role.Checkbox &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription) == null }
                assertEquals(if (sound) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off,
                    tile.config.getOrNull(SemanticsProperties.ToggleableState))
                assertTrue("tile ${tile.boundsInRoot} ${tile.size}", tile.size.height / context.resources.displayMetrics.density >= 48f)
                assertEquals(true, onlySound.config[SemanticsActions.OnClick].action?.invoke())
            }) { activity ->
                vm(activity, found = 3, link = tiktok) { tikTokPhotoSuccess(it, 3) }.also {
                    assertFalse(it.soundSelected)
                    if (!photos) it.selectAllImages(false)
                    if (sound) it.toggleSound()
                }
            }
        }

        val context = RuntimeEnvironment.getApplication()
        if (language == "ar") {
            assertEquals("حفظ 3 صور والصوت", context.resources.getQuantityString(R.plurals.image_save_count_sound, 3, "3"))
            assertEquals("حفظ الصوت", context.getString(R.string.sound_save))
        }
    }

    @Test fun savingShowsTheSavedSnackbar() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
        // Own recomposer + frame clock (as ClipboardEntryTest/HomeSemanticsTest): the shared AndroidUiDispatcher can be
        // left stalled by an earlier test in the same Robolectric JVM, which starved the snackbar collector.
        val clock = androidx.compose.runtime.BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + clock)
        val recomposer = androidx.compose.runtime.Recomposer(scope.coroutineContext)
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        fun frames(): List<String> {
            repeat(4) {
                androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
                clock.sendFrame(System.nanoTime())
                shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            }
            return texts(semanticsNodes(activity = controller.get(), rounds = 1))
        }
        try {
            val activity = controller.get()
            val vm = vm(activity, found = 1)
            activity.setContent(parent = recomposer) {
                NezzelTheme(ThemePref.Light, false) {
                    NezzelApp(vm, "en", ThemePref.Light, false, true, onPaste = {}, onDismissSuggestion = {},
                        onAcceptSuggestion = {}, onLanguage = {}, onTheme = {}, onWallpaper = {}, onSuggestions = {})
                }
            }
            frames()
            val save = clickable(semanticsNodes(activity, rounds = 2), activity.getString(R.string.image_save))
            assertEquals(true, save.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke())
            assertTrue("Save did not start: ${saver.outcome.value}", vm.imageSaving)
            runBlocking { withTimeout(10_000) { saver.awaitIdle() } }
            assertTrue("${saver.outcome.value}", saver.outcome.value is ImageSaveState.Saved)
            val expected = activity.getString(R.string.saved_snackbar, NezzelFormat.isolate(activity.getString(R.string.save_path)))
            var labels = frames()
            repeat(10) { if (expected !in labels) labels = frames() }
            assertEquals(OptionsState.Hidden, vm.optionsState)
            assertTrue(labels.toString(), expected in labels)
            assertFalse("sheet must close after Save", activity.getString(R.string.image_save) in labels.joinToString())
        } finally { recomposer.cancel(); scope.cancel(); controller.pause().stop().destroy() }
    }
}
