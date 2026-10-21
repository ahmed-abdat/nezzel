package dev.ahmed.savlino.update

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import dev.ahmed.savlino.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateScreenTest {
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private val release = Release("0.4.4", 99999, "update.apk", 1_048_576, "https://github.com/asset", "a".repeat(64), listOf("Improved"), listOf("تحسين"))
    private fun nodes(a: ComponentActivity): List<SemanticsNode> { compose.waitForIdle(); return semanticsNodes(a) }
    private fun renderState(stage: UpdateStage, canInstall: Boolean = true, failure: UpdateFailure? = null) =
        renderThemed({ nodes(it) }, {
            UpdateScreen(AppUpdateState(release = release, transfer = release, stage = stage, failure = failure, bytes = 524_288),
                context.resources.configuration.locales[0].language, canInstall, {}, {}, {}, {}, {}, {})
        }) { a ->
            val all = nodes(a)
            val shown = texts(all)
            val expected = when (stage) {
                UpdateStage.IDLE -> R.string.update_skip
                UpdateStage.DOWNLOADING -> R.string.cancel
                UpdateStage.VERIFYING -> R.string.update_verifying
                UpdateStage.READY -> if (canInstall) R.string.update_install else R.string.update_permission_action
                UpdateStage.ERROR -> R.string.try_again
                UpdateStage.INSTALLING -> R.string.update_installing
            }
            assertTrue(shown.contains(context.getString(expected)))
            val buttons = all.filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
            assertTrue(buttons.isNotEmpty())
            buttons.forEach { node -> assertTrue("48dp touch target", node.layoutInfo.height / context.resources.displayMetrics.density >= 47.9f) }
            assertTrue(all.any { context.getString(R.string.back) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
            assertFalse(shown.any { text -> text.any { it in '٠'..'٩' } })
            if (stage == UpdateStage.IDLE) {
                assertTrue(shown.contains(context.getString(R.string.update_not_now)))
                assertTrue(shown.contains(context.getString(R.string.update_trust)))
                assertTrue(shown.contains(context.getString(R.string.update_full_notes)))
            }
            if (stage == UpdateStage.DOWNLOADING) {
                val progress = all.single { it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null }
                assertEquals(0.5f, progress.config[SemanticsProperties.ProgressBarRangeInfo].current)
                val progressLabel = progress.config[SemanticsProperties.ContentDescription].single()
                assertTrue(progressLabel.contains("50%"))
                assertEquals(LiveRegionMode.Polite, progress.config[SemanticsProperties.LiveRegion])
                assertFalse(shown.any { it.startsWith(if (context.resources.configuration.locales[0].language == "ar") "تنزيل التحديث" else "Download update") })
            }
            val label = context.getString(R.string.update_open_release)
            if (stage == UpdateStage.ERROR && failure in listOf(UpdateFailure.INSTALL, UpdateFailure.VERIFY)) {
                assertTrue(shown.contains(label))
                val releaseButton = buttons.single { label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
                assertTrue(releaseButton.layoutInfo.width / context.resources.displayMetrics.density >= 48f)
                assertEquals(true, releaseButton.config[SemanticsActions.OnClick].action?.invoke())
                val intent = org.robolectric.Shadows.shadowOf(a).nextStartedActivity!!
                assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
                assertEquals("https://github.com/ahmed-abdat/nezzel/releases/latest", intent.dataString)
                val error = all.first { it.config.getOrNull(SemanticsProperties.LiveRegion) == LiveRegionMode.Polite &&
                    it.config.getOrNull(SemanticsProperties.Text).orEmpty().isNotEmpty() }
                val retry = all.first { context.getString(R.string.try_again) in texts(listOf(it)) }
                assertTrue(error.boundsInRoot.top < retry.boundsInRoot.top)
            } else assertFalse(shown.contains(label))
            if (stage == UpdateStage.ERROR && failure == UpdateFailure.SPACE) {
                val language = context.resources.configuration.locales[0].language
                val needed = dev.ahmed.savlino.locale.NezzelFormat.isolate(dev.ahmed.savlino.locale.NezzelFormat.size(69_206_016, language))
                assertTrue(shown.contains(context.getString(R.string.update_failed_space, needed)))
            }
        }
    @Test fun idleEnglish() = renderState(UpdateStage.IDLE)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun idleArabic() = renderState(UpdateStage.IDLE)
    @Test fun downloadingEnglish() = renderState(UpdateStage.DOWNLOADING)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun downloadingArabic() = renderState(UpdateStage.DOWNLOADING)
    @Test fun verifyingEnglish() = renderState(UpdateStage.VERIFYING)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun verifyingArabic() = renderState(UpdateStage.VERIFYING)
    @Test fun permissionEnglish() = renderState(UpdateStage.READY, false)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun permissionArabic() = renderState(UpdateStage.READY, false)
    @Test fun installEnglish() = renderState(UpdateStage.READY)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun installArabic() = renderState(UpdateStage.READY)
    @Test fun installingEnglish() = renderState(UpdateStage.INSTALLING)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun installingArabic() = renderState(UpdateStage.INSTALLING)
    @Test fun errorsEnglish() { UpdateFailure.entries.forEach { renderState(UpdateStage.ERROR, failure = it) } }
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun errorsArabic() { UpdateFailure.entries.forEach { renderState(UpdateStage.ERROR, failure = it) } }
    @Test fun cardIsCompactAndDismissButtonHasAccessibleName() = renderThemed({ nodes(it) }, {
        UpdateCard(release, "en", {}, {})
    }) { a ->
        val all = nodes(a)
        assertTrue(all.any { context.getString(R.string.update_not_now) in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() })
        assertTrue(texts(all).contains("Improved"))
        assertTrue(texts(all).contains(context.getString(R.string.update_notification_title, dev.ahmed.savlino.locale.NezzelFormat.isolate("0.4.4"))))
        val bounds = all.first { it.config.getOrNull(SemanticsProperties.TestTag) == "app-update-card" }.boundsInRoot
        assertTrue(bounds.height / context.resources.displayMetrics.density <= 88f)
    }

    private fun cappedNotes() {
        val language = context.resources.configuration.locales[0].language
        val many = Release("0.4.5", 99999, "update.apk", 1_048_576, "https://github.com/asset", "a".repeat(64),
            (1..8).map { "Improvement $it" }, (1..8).map { "تحسين $it" })
        var skipped: Long? = null
        var backed = false
        renderThemed({ nodes(it) }, {
            UpdateScreen(AppUpdateState(release = many), language, true, { backed = true }, {}, {}, { skipped = it }, {}, {})
        }) { a ->
            val all = nodes(a)
            val prefix = if (language == "ar") "تحسين" else "Improvement"
            assertTrue(texts(all).contains("$prefix 6"))
            assertFalse(texts(all).contains("$prefix 7"))
            fun click(label: String) {
                val button = nodes(a).last { it.config.getOrNull(SemanticsActions.OnClick) != null && label in texts(descendants(it)) }
                assertEquals(true, button.config[SemanticsActions.OnClick].action?.invoke())
            }
            click(context.getString(R.string.update_full_notes))
            assertEquals("https://github.com/ahmed-abdat/nezzel/releases/tag/v0.4.5",
                org.robolectric.Shadows.shadowOf(a).nextStartedActivity?.dataString)
            click(context.getString(R.string.update_not_now)); assertTrue(backed)
            click(context.getString(R.string.update_skip)); assertEquals(99999L, skipped)
        }
    }
    @Test fun notesCappedAndSecondaryActionsWorkEnglish() = cappedNotes()
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun notesCappedAndSecondaryActionsWorkArabic() = cappedNotes()

    private fun cardFallback() = renderThemed({ nodes(it) }, {
        UpdateCard(Release("0.4.5", 99999, "update.apk", 100, "https://github.com/asset", "a".repeat(64), emptyList(), emptyList()),
            context.resources.configuration.locales[0].language, {}, {})
    }) { a -> assertTrue(texts(nodes(a)).contains(context.getString(R.string.update_available_title))) }
    @Test fun cardWithoutNotesUsesFallbackEnglish() = cardFallback()
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun cardWithoutNotesUsesFallbackArabic() = cardFallback()
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun cardUsesArabicFirstNote() = renderThemed({ nodes(it) }, {
        UpdateCard(release, "ar", {}, {})
    }) { a ->
        assertTrue(texts(nodes(a)).contains("تحسين"))
        assertFalse(texts(nodes(a)).contains("Improved"))
    }

    private fun largeText() = renderThemed({ nodes(it) }, {
        val density = androidx.compose.ui.platform.LocalDensity.current
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides
            androidx.compose.ui.unit.Density(density.density, 2f)) {
            dev.ahmed.savlino.ui.theme.NezzelTheme(dev.ahmed.savlino.locale.ThemePref.Dark, false) {
                UpdateScreen(AppUpdateState(release = release), context.resources.configuration.locales[0].language,
                    true, {}, {}, {}, {}, {}, {})
            }
        }
    }) { a ->
        nodes(a).filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }.forEach { node ->
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layout)
            // Same rule as ActualFormatsTest: wrap-content text reports width overflow in Robolectric; real clipping is
            // height overflow, a hit line limit, or a box narrower than the longest unbreakable run.
            assertFalse("Text clipped at 200%: ${texts(listOf(node))}", layout.any { it.didOverflowHeight ||
                it.multiParagraph.didExceedMaxLines || it.multiParagraph.minIntrinsicWidth > it.size.width + 1f })
        }
    }
    @Test @Config(qualifiers = "en-w640dp-h360dp-mdpi") fun darkLandscapeAt200PercentEnglish() = largeText()
    @Test @Config(qualifiers = "ar-w640dp-h360dp-mdpi") fun darkLandscapeAt200PercentArabic() = largeText()

    @Test fun releasePageWithoutBrowserDoesNotCrash() {
        val noBrowser = object : android.content.ContextWrapper(context) {
            override fun startActivity(intent: android.content.Intent) { throw android.content.ActivityNotFoundException() }
        }
        openUpdateReleasePage(noBrowser)
        openUpdateReleasePage(noBrowser, "0.4.5")
    }

    @Test fun confirmationIsHeldUntilForegroundRouteConsumesItOnce() {
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val production = object : android.content.ContextWrapper(context) {
            override fun getPackageName() = UpdatePolicy.PACKAGE
        }
        val updates = AppUpdates(production, owner)
        val confirmation = android.content.Intent("dev.ahmed.savlino.TEST_INSTALL_CONFIRMATION")
        try {
            updates.holdConfirmation(confirmation)
            assertSame(confirmation, updates.state.value.confirmation)
            renderThemed({ nodes(it) }, { AppUpdateRoute(updates, "en") {} }) { activity ->
                assertEquals(confirmation.action, org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.action)
                assertNull(updates.state.value.confirmation)
                nodes(activity)
                assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
                val lifecycle = activity.lifecycle as androidx.lifecycle.LifecycleRegistry
                lifecycle.currentState = androidx.lifecycle.Lifecycle.State.STARTED
                nodes(activity)
                updates.holdConfirmation(confirmation)
                nodes(activity)
                assertSame(confirmation, updates.state.value.confirmation)
                assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
                lifecycle.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
                nodes(activity)
                assertEquals(confirmation.action, org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.action)
                assertNull(updates.state.value.confirmation)
            }
        } finally { owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel() }
    }
    private fun lastChecked(shown: Boolean) {
        val prefs = context.getSharedPreferences("app-updates", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        if (shown) prefs.edit().putLong("success", 1_729_425_600_000).commit()
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val production = object : android.content.ContextWrapper(context) { override fun getPackageName() = "dev.ahmed.savlino" }
        val updates = AppUpdates(production, owner)
        try {
            renderThemed({ nodes(it) }, { UpdateSettings(updates) {} }) { a ->
                val all = texts(nodes(a))
                val prefix = if (context.resources.configuration.locales[0].language == "ar") "آخر تحقق" else "Last checked"
                assertEquals(shown, all.any { it.startsWith(prefix) })
                // Up to date only after a successful check with nothing newer known
                assertEquals(shown, all.contains(context.getString(R.string.update_latest)))
                if (shown) {
                    // The date and time never break apart.
                    val value = all.single { it.startsWith(prefix) }.removePrefix(prefix).trim()
                    assertTrue(Regex("[0-9]{1,2}:[0-9]{2}").containsMatchIn(value))
                    assertFalse(value.contains(' '))
                    assertFalse(value.any { it in '٠'..'٩' || it in '۰'..'۹' })
                }
            }
        } finally { owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); prefs.edit().clear().commit() }
    }
    @Test fun lastCheckedHiddenBeforeFirstCheckEnglish() = lastChecked(false)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun lastCheckedHiddenBeforeFirstCheckArabic() = lastChecked(false)
    @Test fun lastCheckedShownEnglish() = lastChecked(true)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun lastCheckedShownArabic() = lastChecked(true)
    private fun settingsBadge() {
        var offered by androidx.compose.runtime.mutableStateOf(true)
        renderThemed({ nodes(it) }, {
            dev.ahmed.savlino.downloads.DownloadNavigation("Home", emptyList(), "en", offered) {}
        }) { a ->
            fun badges() = nodes(a).count { context.getString(R.string.update_available_title) in
                it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() }
            // TalkBack reads the merged item: Material3 drops the badge's own description there
            fun announced() = compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(
                context.getString(R.string.settings_update_accessibility))).fetchSemanticsNodes().size
            assertEquals(1, badges())
            assertEquals(1, announced())
            offered = false
            assertEquals(0, badges())
            assertEquals(0, announced())
        }
    }
    private fun cachedOffer(failedCheck: Boolean) = kotlinx.coroutines.runBlocking {
        val prefs = context.getSharedPreferences("app-updates", android.content.Context.MODE_PRIVATE)
        val json = org.json.JSONObject().put("tag_name", "v0.4.4").put("body", "<!-- nezzel:versionCode=99999 -->")
            .put("assets", org.json.JSONArray().put(org.json.JSONObject().put("name", "update.apk").put("size", 100)
                .put("browser_download_url", "https://github.com/asset").put("digest", "sha256:" + "a".repeat(64))))
        prefs.edit().clear().putString("release", json.toString()).commit()
        val owner = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val production = object : android.content.ContextWrapper(context) { override fun getPackageName() = "dev.ahmed.savlino" }
        val updates = AppUpdates(production, owner) { object : UpdateHttp("test") {
            override suspend fun manifest(): ByteArray = throw java.io.IOException()
            override suspend fun latest(): ByteArray = throw java.io.IOException()
        } }
        try {
            if (failedCheck) updates.check(manual = true)
            assertEquals(if (failedCheck) UpdateCheck.FAILED else UpdateCheck.NONE, updates.state.value.check)
            assertTrue(updates.state.value.badge(dev.ahmed.savlino.BuildConfig.VERSION_CODE.toLong()))
            var opened = 0
            renderThemed({ nodes(it) }, { UpdateSettings(updates) { opened++ } }) { a ->
                val offer = nodes(a).single { it.config.getOrNull(SemanticsProperties.TestTag) == "settings_update_offer" }
                offer.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!.invoke()
                assertEquals(1, opened)
            }
            updates.skip(99999)
            renderThemed({ nodes(it) }, { UpdateSettings(updates) {} }) { a ->
                assertTrue(nodes(a).none { it.config.getOrNull(SemanticsProperties.TestTag) == "settings_update_offer" })
            }
        } finally { owner.coroutineContext[kotlinx.coroutines.Job]!!.cancel(); prefs.edit().clear().commit() }
    }
    @Test fun settingsExposesCachedOfferBeforeAnyCheck() = cachedOffer(false)
    @Test @Config(qualifiers = "ar-w360dp-h640dp-mdpi") fun settingsExposesCachedOfferAfterFailedCheckArabic() = cachedOffer(true)
    @Test fun settingsBarDotTracksOffer() = settingsBadge()
    @Test @Config(qualifiers = "ar-w640dp-h360dp-mdpi") fun settingsRailDotTracksOfferArabic() = settingsBadge()

}
