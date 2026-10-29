package dev.ahmed.savlino

import android.content.Intent
import android.net.Uri
import dev.ahmed.savlino.domain.Host
import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.engine.bluesky.BlueskyPostIdentity
import dev.ahmed.savlino.engine.facebook.FacebookPostIdentity
import dev.ahmed.savlino.engine.instagram.InstagramPostIdentity
import dev.ahmed.savlino.engine.linkedin.LinkedInPostIdentity
import dev.ahmed.savlino.engine.mediahttp.AcceptedLinkHosts
import dev.ahmed.savlino.engine.mediahttp.ShortLinks
import dev.ahmed.savlino.engine.reddit.RedditPostIdentity
import dev.ahmed.savlino.engine.telegram.TelegramPostIdentity
import dev.ahmed.savlino.engine.tiktok.TikTokPostIdentity
import dev.ahmed.savlino.engine.x.XPostIdentity
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
    @Test fun manifestHostsEqualAcceptedHostsMinusShareOnlyAndStaticShortcutsTargetForegroundActions() {
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
        assertEquals(".ShareSheetActivity", alias.getAttribute("android:targetActivity"))
        val filters = alias.getElementsByTagName("intent-filter")
        assertEquals(1, filters.length)
        assertNotEquals("true", (filters.item(0) as org.w3c.dom.Element).getAttribute("android:autoVerify"))
        val nodes = alias.getElementsByTagName("data")
        val data = (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        assertEquals(AcceptedLinkHosts.exactHosts - AcceptedLinkHosts.SHARE_ONLY, data.map { it.getAttribute("android:host") }.filter { it.isNotEmpty() }.toSet())
        assertEquals(setOf("https"), data.map { it.getAttribute("android:scheme") }.filter { it.isNotEmpty() }.toSet())
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

    @Test fun everyAcceptedAliasHasAnEngineOrUrlLayerRoute() {
        val youtube = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be")
        val paths = listOf("/token", "/p/Abc12", "/user/status/123", "/comments/abc", "/reel/123",
            "/@user/video/123", "/posts/user-activity-123", "/channel/123", "/profile/user.example/post/abc",
            "/url_shortener/token/redirect/", "/l.php?u=https%3A%2F%2Fyoutu.be%2Fone")
        assertEquals(AcceptedLinkHosts.exactHosts, ShareInput.VIEW_HOSTS)
        for (host in ShareInput.VIEW_HOSTS - youtube) {
            val url = paths.map { "https://$host$it" }.firstOrNull { raw ->
                val link = LinkInput.parse(raw)
                XPostIdentity.parse(raw) != null || InstagramPostIdentity.parse(raw) != null ||
                    FacebookPostIdentity.parse(raw) != null || RedditPostIdentity.parse(raw) != null ||
                    TikTokPostIdentity.parse(raw) != null || TelegramPostIdentity.parse(raw) != null ||
                    BlueskyPostIdentity.parse(raw) != null || LinkedInPostIdentity.parse(raw) != null ||
                    (link != null && (ShortLinks.isShortLink(link) ||
                        (link.rawUrl != raw && (link.host == Host.YOUTUBE || FacebookPostIdentity.parse(link.rawUrl) != null))))
            }
            assertNotNull("Engine/URL route for $host", url)
            val send = ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url))
            assertNotNull(send)
            assertEquals(send?.text, ShareInput.from(Intent(Intent.ACTION_VIEW, Uri.parse(url!!)))?.text)
        }
    }

    @Test fun knownMissingAliasesAreIncluded() {
        assertTrue(ShareInput.VIEW_HOSTS.containsAll(setOf("t.co", "lnkd.in", "mbasic.facebook.com",
            "touch.facebook.com", "mobile.facebook.com", "www.instagr.am", "m.x.com", "m.twitter.com",
            "fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com", "reddit.com", "redd.it", "v.redd.it",
            "telegram.me", "linkedin.com", "fb.com", "www.fb.com", "l.facebook.com", "lm.facebook.com")))
    }

    @Test fun localeAliasesAcceptedWithoutBroadeningToUnknownSubdomains() {
        for (url in listOf("https://ar-ar.facebook.com/reel/123", "https://FR-FR.FACEBOOK.COM/reel/123",
            "https://fr.linkedin.com/posts/user-activity-123")) {
            assertNotNull(ShareInput.from(Intent(Intent.ACTION_VIEW, Uri.parse(url))))
        }
        for (host in listOf("unknown.facebook.com", "a.linkedin.com", "unknown.linkedin.com", "ar-ar.facebook.com.evil.example",
            "www.t.co", "t.co.evil.example", "notlnkd.in", "cdn.syndication.twimg.com", "public.api.bsky.app")) {
            assertNull(host, ShareInput.from(Intent(Intent.ACTION_VIEW, Uri.parse("https://$host/token"))))
        }
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

    @Test @Config(sdk = [33]) fun openWithOffersOnlyLinksThatCanFillTheDraft() {
        val app = RuntimeEnvironment.getApplication()
        dev.ahmed.savlino.locale.AppPreferences(app).apply { automaticOptions = false; suggestionsEnabled = false }
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            for (host in ShareInput.VIEW_HOSTS - AcceptedLinkHosts.SHARE_ONLY) {
                fun handlers(scheme: String) = app.packageManager.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW, Uri.parse("$scheme://$host/watch?v=clip"))
                        .addCategory(Intent.CATEGORY_BROWSABLE), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    .filter { it.activityInfo.packageName == app.packageName && it.activityInfo.name.endsWith(".OpenWithLinks") }
                assertTrue("HTTPS handler for $host", handlers("https").isNotEmpty())
                assertTrue("Unsupported HTTP must not be offered for $host", handlers("http").isEmpty())
                c.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://$host/watch?v=clip")))
                // fb.com keeps its query only as www.facebook.com: its own redirect drops it.
                val expected = if (host in dev.ahmed.savlino.domain.LinkInput.FACEBOOK_ALIAS_HOSTS) "www.facebook.com" else host
                assertEquals("https://$expected/watch?v=clip", c.get().links.text)
                assertFalse(c.get().links.invalidShare)
                assertNull(c.get().links.takeConfirmedCommand())
            }
        } finally { c.pause().stop().destroy() }
    }

    @Test @Config(sdk = [33]) fun shareOnlyHostsStayAcceptedWithoutOpenWithHandlers() {
        val app = RuntimeEnvironment.getApplication()
        assertTrue(AcceptedLinkHosts.exactHosts.containsAll(AcceptedLinkHosts.SHARE_ONLY))
        for (host in AcceptedLinkHosts.SHARE_ONLY) {
            val url = "https://$host/token"
            val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
            val handlers = app.packageManager.queryIntentActivities(view, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                .filter { it.activityInfo.packageName == app.packageName }
            assertTrue("No generic-link handler for $host", handlers.isEmpty())
            assertEquals(url, ShareInput.from(view)?.text)
            assertEquals(url, ShareInput.from(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url))?.text)
        }
    }

    @Test @Config(sdk = [29]) fun aliasDisabledBeforeAndroid12() = assertFalse(RuntimeEnvironment.getApplication().resources.getBoolean(R.bool.open_with_links))
    @Test @Config(sdk = [33]) fun aliasEnabledOnAndroid12AndLater() = assertTrue(RuntimeEnvironment.getApplication().resources.getBoolean(R.bool.open_with_links))
}
