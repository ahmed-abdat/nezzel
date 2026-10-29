package dev.ahmed.savlino.engine.mediahttp

import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.engine.bluesky.BlueskyPostIdentity
import dev.ahmed.savlino.engine.facebook.FacebookPostIdentity
import dev.ahmed.savlino.engine.instagram.InstagramPostIdentity
import dev.ahmed.savlino.engine.linkedin.LinkedInPostIdentity
import dev.ahmed.savlino.engine.reddit.RedditPostIdentity
import dev.ahmed.savlino.engine.telegram.TelegramPostIdentity
import dev.ahmed.savlino.engine.tiktok.TikTokPostIdentity
import dev.ahmed.savlino.engine.x.XPostIdentity
import java.util.Locale

/** Explicit input hosts only: fetching an API/CDN host does not make it an Open-with handler. No network or path inspection. */
object AcceptedLinkHosts {
    /** All exact input hosts accepted by Share/Paste or an explicit VIEW intent. */
    val exactHosts: Set<String> = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be",
        BlueskyPostIdentity.ENTRY_HOST, FacebookPostIdentity.SHORT_HOST, ShortLinks.PINTEREST_HOST,
    ) + XPostIdentity.hosts + InstagramPostIdentity.hosts + FacebookPostIdentity.inputHosts +
        RedditPostIdentity.inputHosts + TikTokPostIdentity.hosts + TelegramPostIdentity.hosts +
        LinkedInPostIdentity.entryHosts + ShortLinks.hosts + ShortLinks.instagramShareHosts +
        LinkInput.FACEBOOK_WRAPPER_HOSTS + LinkInput.FACEBOOK_ALIAS_HOSTS

    /**
     * Generic shorteners and outbound wrappers may redirect to unrelated pages. Android matches every path on a
     * manifest host, so these remain accepted through Share/Paste without making Nezzel a generic-link handler.
     * The manifest must equal [exactHosts] minus this set; ViewInputTest guards that policy.
     */
    val SHARE_ONLY: Set<String> = setOf(
        "bit.ly", "buff.ly", "cutt.ly", "goo.gl", "is.gd", "ow.ly", "rb.gy", "tinyurl.com",
        "t.co", "lnkd.in", "l.facebook.com", "lm.facebook.com", "api.pinterest.com",
    )

    /** Android authority wildcards cannot express these locale regexes without admitting unrelated subdomains. */
    fun accepts(host: String): Boolean = host.lowercase(Locale.ROOT).let {
        it in exactHosts || FacebookPostIdentity.localeHost.matches(it) || LinkedInPostIdentity.isLinkedInEntryHost(it)
    }
}
