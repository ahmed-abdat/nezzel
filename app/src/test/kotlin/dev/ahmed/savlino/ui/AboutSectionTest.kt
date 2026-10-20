package dev.ahmed.savlino.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.*
import dev.ahmed.savlino.R
import dev.ahmed.savlino.descendants
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.renderThemed
import dev.ahmed.savlino.semanticsNodes
import dev.ahmed.savlino.texts
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w360dp-h1600dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutSectionTest {
    @Test fun facebookIntentDeliversPageWithFreshTaskFlags() {
        val intent = hululsoftFacebookIntent()
        assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.facebook.com/hululsoft", intent.data.toString())
        assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
            intent.flags)
    }
    @get:org.junit.Rule val compose = androidx.compose.ui.test.junit4.createEmptyComposeRule()
    private val context get() = RuntimeEnvironment.getApplication()
    private fun nodes(a: ComponentActivity): List<SemanticsNode> { compose.waitForIdle(); return semanticsNodes(a) }
    private fun render(check: (ComponentActivity) -> Unit) =
        renderThemed({ nodes(it) }, { AboutSection("1.2.3", 42) }, check)
    private fun clickable(a: ComponentActivity, label: String): SemanticsNode = nodes(a).last { node ->
        node.config.getOrNull(SemanticsActions.OnClick) != null && descendants(node).any {
            label in texts(listOf(it)) || it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label)
        }
    }
    private fun click(node: SemanticsNode) { assertEquals(true, node.config[SemanticsActions.OnClick].action?.invoke()) }

    private fun assertVersions(all: List<String>) {
        assertTrue(all.contains(context.getString(R.string.about_version, NezzelFormat.isolate("1.2.3"))))
        assertTrue(all.contains(context.getString(R.string.about_build, 42)))
        // The engine version lives in Settings' engine row only.
        assertFalse(all.any { it.startsWith(context.getString(R.string.engine_version, "").trim()) })
        assertTrue(all.contains(context.getString(R.string.about_body)))
        assertTrue(all.contains(context.getString(R.string.about_warranty)))
    }

    @Test fun showsAppEngineVersionsAndNoticeInEnglish() = render { a ->
        val all = texts(nodes(a))
        assertTrue(all.contains("Nezzel"))
        assertVersions(all)
        assertTrue(context.getString(R.string.about_body).contains("github.com/ahmed-abdat/nezzel"))
        assertTrue(all.contains("Provided as is, without warranty."))
    }

    @Config(qualifiers = "ar-w360dp-h1600dp-mdpi")
    @Test fun showsAppEngineVersionsAndNoticeInArabic() = render { a ->
        val all = texts(nodes(a))
        assertTrue(all.contains("Nezzel"))
        assertVersions(all)
        assertTrue(context.getString(R.string.about_body).contains("yt-dlp"))
        assertTrue(context.getString(R.string.about_body).contains("\u2066github.com/ahmed-abdat/nezzel\u2069"))
    }

    @Test fun aboutIsAHeadingAndLicencesRowIsALabelledButton() = render { a ->
        val all = nodes(a)
        assertTrue(all.any { it.config.contains(SemanticsProperties.Heading) && context.getString(R.string.about) in texts(listOf(it)) })
        val row = clickable(a, context.getString(R.string.about_licences))
        assertEquals(Role.Button, row.config.getOrNull(SemanticsProperties.Role))
        assertTrue(row.layoutInfo.height / context.resources.displayMetrics.density >= 47.9f)
    }

    @Test fun licencesSheetListsEveryComponentWithBundledTexts() = render { a ->
        click(clickable(a, context.getString(R.string.about_licences)))
        val all = nodes(a)
        val shown = texts(all)
        val names = OpenSourceNotices.components.map { it.name }
        listOf("yt-dlp", "youtubedl-android", "FFmpeg", "Python", "Native libraries (FFmpeg and Python)", "QuickJS").forEach { assertTrue(it in names) }
        OpenSourceNotices.components.forEach { c ->
            assertTrue("Missing ${c.name}", shown.contains(NezzelFormat.isolate(c.name)))
            assertTrue("Missing ${c.spdx}", shown.any { c.spdx in it })
            assertTrue("Missing ${c.url}", shown.contains(NezzelFormat.isolate(c.url)))
            c.asset?.let { path -> assertTrue("Missing asset $path", context.assets.list("licenses").orEmpty().contains(path.removePrefix("licenses/"))) }
        }
        assertTrue(all.any { it.config.contains(SemanticsProperties.Heading) && context.getString(R.string.about_licences) in texts(listOf(it)) })
        val gpl = OpenSourceNotices.components.first { it.name == "youtubedl-android" }
        val show = clickable(a, context.getString(R.string.about_show_licence, gpl.name))
        // Short visible label; the spoken name says which component.
        assertTrue(texts(descendants(show)).contains(context.getString(R.string.about_view_licence)))
        assertFalse(texts(descendants(show)).any { gpl.name in it })
        click(show)
        compose.waitUntil(5_000) { texts(nodes(a)).any { "GNU GENERAL PUBLIC LICENSE" in it } }
        assertTrue(texts(nodes(a)).any { "GNU GENERAL PUBLIC LICENSE" in it })
        val hide = clickable(a, context.getString(R.string.about_hide_licence, gpl.name))
        assertTrue(texts(descendants(hide)).contains(context.getString(R.string.about_hide_licence_short)))
        assertNotNull(clickable(a, context.getString(R.string.nezzel_close)))
    }

    @Test fun componentsWithoutBundledTextOnlyNameTheirLicence() {
        val none = OpenSourceNotices.components.filter { it.asset == null }.map { it.name }.toSet()
        assertEquals(setOf("yt-dlp"), none)
    }

    @Test fun nativeRowsOpenTheBundledNoticeInventory() {
        val native = OpenSourceNotices.components.first { it.spdx == "SEE-NOTICES" }
        val quickjs = OpenSourceNotices.components.first { it.name == "QuickJS" }
        assertEquals("MIT", quickjs.spdx)
        listOf(native, quickjs).forEach { component ->
            assertEquals("licenses/NOTICES-native.txt", component.asset)
            val text = context.assets.open(requireNotNull(component.asset)).bufferedReader().use { it.readText() }
            assertTrue(text.contains("quickjs 1:20250426 — MIT"))
            assertTrue(text.contains("ffmpeg 7.1.1 — GPL-3.0-or-later"))
            assertTrue(text.contains("python 3.12.11 — PSF-2.0"))
        }
    }

    private fun madeBy() = context.getString(R.string.company_made_by, NezzelFormat.isolate("Hululsoft"))

    @Test fun namesHululsoftAsTheMakerWithoutATagline() = render { a ->
        val all = texts(nodes(a))
        assertTrue(all.any { madeBy() in it })
        assertFalse(all.any { "Software, done right" in it })
        assertTrue(all.contains("hululsoft.mr"))
    }

    @Config(qualifiers = "ar-w360dp-h1600dp-mdpi")
    @Test fun arabicKeepsTheCompanyNameInLatinScript() = render { a ->
        val all = texts(nodes(a))
        assertTrue(all.any { madeBy() in it && "\u2066Hululsoft\u2069" in it })
        assertFalse(all.any { "برمجيات بإتقان" in it })
        assertFalse(all.any { "هلولسوفت" in it })
    }

    @Test fun siteLinkOpensOnlyTheStudioSiteOnTap() {
        val opened = mutableListOf<String>()
        val handler = object : androidx.compose.ui.platform.UriHandler { override fun openUri(uri: String) { opened += uri } }
        renderThemed({ nodes(it) }, {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides handler) {
                AboutSection("1.2.3", 42)
            }
        }) { a ->
            assertTrue(opened.isEmpty())
            val link = clickable(a, context.getString(R.string.company_open_site, "Hululsoft"))
            assertTrue(link.layoutInfo.height / context.resources.displayMetrics.density >= 47.9f)
            click(link)
            assertEquals(listOf(HULULSOFT_SITE), opened)
            val facebook = clickable(a, context.getString(R.string.company_open_facebook, "Hululsoft"))
            assertTrue(facebook.layoutInfo.height / context.resources.displayMetrics.density >= 47.9f)
            click(facebook)
            val intent = org.robolectric.Shadows.shadowOf(a).nextStartedActivity
            assertEquals(HULULSOFT_FACEBOOK, intent.data.toString())
            assertEquals(android.content.Intent.ACTION_VIEW, intent.action)
            assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP,
                intent.flags)
            assertEquals(listOf(HULULSOFT_SITE), opened)
        }
    }

    @Test fun homeSignatureOpensTheStudioSiteOnTap() {
        val opened = mutableListOf<String>()
        val handler = object : androidx.compose.ui.platform.UriHandler { override fun openUri(uri: String) { opened += uri } }
        renderThemed({ nodes(it) }, {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides handler) {
                MadeByHululsoft()
            }
        }) { a ->
            assertTrue(opened.isEmpty())
            val signature = clickable(a, madeBy())
            assertTrue(signature.layoutInfo.height / context.resources.displayMetrics.density >= 47.9f)
            click(signature)
            assertEquals(listOf(HULULSOFT_SITE), opened)
        }
    }

    private fun failedCompanyLink() {
        val handler = object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) { throw IllegalArgumentException("No browser") }
        }
        renderThemed({ nodes(it) }, {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides handler) {
                MadeByHululsoft()
            }
        }) { a ->
            click(clickable(a, madeBy()))
            assertEquals(context.getString(R.string.company_link_failed), org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        }
    }
    @Test fun companyLinkFailureGivesFeedbackEnglish() = failedCompanyLink()
    @Config(qualifiers = "ar-w360dp-h1600dp-mdpi")
    @Test fun companyLinkFailureGivesFeedbackArabic() = failedCompanyLink()
}
