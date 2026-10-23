package dev.ahmed.savlino.domain

/**
 * Session-owned; the UI must check eligibility BEFORE reading the clipboard. No clipboard IO here.
 * Handled links are remembered as [keyer] tags (keyed HMAC, clipboard domain); the default session keyer
 * matches only within this process, so its tags are not worth persisting.
 */
class SuggestionGate(dismissed: Set<String> = emptySet(), private val keyer: LinkKeyer = LinkKeyer.session) {
    private val dismissed = dismissed.toMutableSet()
    private var lastOfferedHash: String? = null
    /** The last keyed tag by its normalized link: offering then dismissing one link costs one keystore HMAC, not two. */
    private var lastTag: Pair<String, String>? = null

    fun offer(text: String?, focused: Boolean = true, resumed: Boolean = true, foreground: Boolean = true,
              fieldEmpty: Boolean = true, shareActive: Boolean = false): ValidatedLink? {
        if (!canRead(focused, resumed, foreground, fieldEmpty, shareActive)) return null
        val link = LinkInput.parse(text) ?: return null
        val hash = hash(link)
        if (hash == lastOfferedHash || hash in dismissed) return null
        lastOfferedHash = hash
        return link
    }

    fun dismiss(link: ValidatedLink) { dismissed += hash(link) }
    fun dismissedHashes(): Set<String> = dismissed.toSet()

    companion object {
        fun canRead(focused: Boolean, resumed: Boolean, foreground: Boolean,
                    fieldEmpty: Boolean, shareActive: Boolean): Boolean =
            focused && resumed && foreground && fieldEmpty && !shareActive
    }

    // A keyer that cannot tag right now falls back to the session key: dedupe still works, nothing durable matches.
    // Only keyed tags are reused, so a link tagged during a keystore hiccup is tried again next time.
    private fun hash(link: ValidatedLink): String {
        val url = link.normalizedUrl
        lastTag?.let { (tagged, tag) -> if (tagged == url) return tag }
        return keyer.key(LinkKeyer.CLIPBOARD, url)?.also { lastTag = url to it }
            ?: LinkKeyer.session.key(LinkKeyer.CLIPBOARD, url)!!
    }
}
