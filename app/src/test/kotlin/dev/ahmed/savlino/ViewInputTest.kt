package dev.ahmed.savlino

import android.content.Intent
import android.net.Uri
import dev.ahmed.savlino.entry.ShareInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ViewInputTest {
    @Test fun manifestHostsEqualTheIntakeAllowlistAndStaticShortcutsTargetForegroundActions() {
        val main = java.io.File("src/main").takeIf { it.isDirectory } ?: java.io.File("app/src/main")
        val xml = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File(main, "AndroidManifest.xml"))
        val aliases = xml.getElementsByTagName("activity-alias")
        val aliasEntries = (0 until aliases.length).map { aliases.item(it) as org.w3c.dom.Element }
        val pasteAlias = aliasEntries.single { it.getAttribute("android:name") == ".PasteEntry" }
        assertEquals("false", pasteAlias.getAttribute("android:exported"))
        assertEquals(".MainActivity", pasteAlias.getAttribute("android:targetActivity"))
        val alias = aliasEntries.single { it.getAttribute("android:name") == ".OpenWithLinks" }
        assertEquals("@bool/open_with_links", alias.getAttribute("android:enabled"))
        assertEquals(".MainActivity", alias.getAttribute("android:targetActivity"))
        val filters = alias.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        assertNotEquals("true", (filters.item(0) as org.w3c.dom.Element).getAttribute("android:autoVerify"))
        val nodes = alias.getElementsByTagName("data")
        val data = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        assertEquals(ShareInput.VIEW_HOSTS, data.map { it.getAttribute("android:host") }.filter { it.isNotEmpty() }.toSet())
        assertEquals(setOf("http", "https"), data.map { it.getAttribute("android:scheme") }.filter { it.isNotEmpty() }.toSet())
        val shortcuts = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File(main, "res/xml/shortcuts.xml")).getElementsByTagName("intent")
        val intents = (0 until shortcuts.length).map { shortcuts.item(it) as org.w3c.dom.Element }
        assertEquals(setOf("dev.ahmed.savlino.action.PASTE_LINK", "dev.ahmed.savlino.action.OPEN_DOWNLOADS"),
            intents.map { it.getAttribute("android:action") }.toSet())
        for (intent in intents) assertEquals(
            if (intent.getAttribute("android:action") == "dev.ahmed.savlino.action.PASTE_LINK") "dev.ahmed.savlino.PasteEntry"
            else "dev.ahmed.savlino.MainActivity", intent.getAttribute("android:targetClass"))
    }

    @Test @Config(sdk = [29, 33]) fun downloadsShortcutHasLocalizedShortLabelAndFullLongLabel() {
        val main = java.io.File("src/main").takeIf { it.isDirectory } ?: java.io.File("app/src/main")
        val shortcuts = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(java.io.File(main, "res/xml/shortcuts.xml")).getElementsByTagName("shortcut")
        val downloads = (0 until shortcuts.length).map { shortcuts.item(it) as org.w3c.dom.Element }
            .single { it.getAttribute("android:shortcutId") == "downloads" }
        assertEquals("@string/shortcut_downloads_short", downloads.getAttribute("android:shortcutShortLabel"))
        assertEquals("@string/downloads", downloads.getAttribute("android:shortcutLongLabel"))
        for ((language, short, long) in listOf(Triple("en", "Downloads", "Downloads"),
            Triple("ar", "التنزيلات", "التنزيلات"), Triple("fr", "Fichiers", "Téléchargements"))) {
            val context = dev.ahmed.savlino.locale.AppLocale.localized(RuntimeEnvironment.getApplication(), language)
            assertEquals(short, context.getString(R.string.shortcut_downloads_short))
            assertEquals(long, context.getString(R.string.downloads))
        }
    }

    @Test fun socialViewMatchesSendAndNeverUsesExtras() {
        for (host in ShareInput.VIEW_HOSTS) for (scheme in listOf("http", "https")) {
            val url = "$scheme://$host/watch?v=clip"
            val send = ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url))
            assertEquals(send?.text, ShareInput.from(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .putExtra(Intent.EXTRA_TEXT, "https://unrelated.example/"))?.text)
        }
        assertNotNull(ShareInput.from(Intent(Intent.ACTION_VIEW, Uri.parse("https://YOUTUBE.COM/watch"))))
    }

    @Test fun rejectsOtherHostsSchemesMissingDataAndOversizedInput() {
        for (url in listOf("https://other.example/v", "https://youtube.com.evil.example/v", "https://notyoutube.com/v",
            "file://youtube.com/v", "content://youtube.com/v", "ftp://youtube.com/v", "https://youtube.com/" + "a".repeat(8192))) {
            assertNull(url, ShareInput.from(Intent(Intent.ACTION_VIEW, Uri.parse(url))))
        }
        assertNull(ShareInput.from(Intent(Intent.ACTION_VIEW)))
        val prefix = "https://youtube.com/"
        assertNotNull(ShareInput.from(Intent(Intent.ACTION_VIEW, Uri.parse(prefix + "a".repeat(8192 - prefix.length)))))
    }

    @Test fun viewSkipsOnboardingLikeShareAndAcceptsWarmIntents() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("nezzel", 0).edit().clear().commit()
        val prefs = dev.ahmed.savlino.locale.AppPreferences(app).apply { automaticOptions = false; suggestionsEnabled = false }
        val c = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/one"))).setup()
        try {
            assertTrue(prefs.onboardingSeen)
            assertEquals("https://youtu.be/one", c.get().links.text)
            c.get().links.edit("")
            c.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/two")))
            assertEquals("https://youtu.be/two", c.get().links.text)
            assertNull(c.get().links.takeConfirmedCommand())
        } finally { c.pause().stop().destroy() }
    }

    @Test @Config(sdk = [29]) fun aliasDisabledBeforeAndroid12() = assertFalse(RuntimeEnvironment.getApplication().resources.getBoolean(R.bool.open_with_links))
    @Test @Config(sdk = [33]) fun aliasEnabledOnAndroid12AndLater() = assertTrue(RuntimeEnvironment.getApplication().resources.getBoolean(R.bool.open_with_links))
}
