package dev.ahmed.savlino.engine.linkedin

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import okhttp3.HttpUrl

/**
 * Public post identity from the exact accepted URL shapes. A shortlink has no post identity until the
 * transport resolves it. Post slugs carry one typed LinkedIn ID (`activity-`, `share-` or `ugcPost-`);
 * two URLs are the same post when they agree on a shared ID kind (vanity slug text is not identity).
 * A share/ugcPost slug and an activity URL share no ID kind; only the fetched page's own post
 * container can bridge them ([withBridgedActivity]), never a redirect.
 */
class LinkedInPostIdentity private constructor(internal val shortlink: Boolean, internal val slug: String?,
    private val ids: Map<String, String>) {
    internal val activityId: String? get() = ids[ACTIVITY]

    companion object {
        internal val entryHosts = setOf("linkedin.com", "www.linkedin.com")
        /**
         * A post slug exactly as LinkedIn serves it: `[A-Za-z0-9_-]` plus percent-escapes (non-Latin names arrive
         * encoded), never an escaped `#`, `%`, `.`, `/`, `?`, `\` or control character.
         */
        internal const val LINKEDIN_SLUG = "(?:[A-Za-z0-9_-]|%(?![01][0-9A-Fa-f]|2[35EeFf]|3[Ff]|5[Cc]|7[Ff])[0-9A-Fa-f]{2})+"

        internal fun isLinkedInPost(url: HttpUrl): Boolean = url.host == "www.linkedin.com" &&
            (Regex("/posts/$LINKEDIN_SLUG/?").matches(url.encodedPath) ||
                Regex("/feed/update/urn:li:(?:activity|share|ugcPost):[0-9]+/?").matches(url.encodedPath))

        internal fun isLinkedInEntryHost(host: String): Boolean = host in entryHosts ||
            Regex("[a-z]{2}\\.linkedin\\.com").matches(host)

        /** www host; an `/embed/` or `%3A`-encoded update link becomes its plain `/feed/update/urn:li:…/` page. */
        internal fun normalizeLinkedInEntry(url: HttpUrl): HttpUrl {
            if (!isLinkedInEntryHost(url.host)) return url
            val path = LinkedInPostIdentity.updatePath(url.encodedPath).removePrefix("/embed")
                .takeIf { it.startsWith("/feed/update/") } ?: url.encodedPath
            return url.newBuilder().host("www.linkedin.com").encodedPath(path).build()
        }

        internal fun isLinkedInPageEntry(url: HttpUrl): Boolean = isLinkedInEntryHost(url.host) && isLinkedInPost(normalizeLinkedInEntry(url)) ||
            (url.host == "lnkd.in" && Regex("/p/[A-Za-z0-9_-]+/?").matches(url.encodedPath))

        /** LinkedIn's own sign-up or login wall; never followed, only named. */
        internal fun isLinkedInLoginWall(url: HttpUrl): Boolean = isLinkedInEntryHost(url.host) &&
            Regex("/(?:signup(?:/[a-z-]+)?|authwall|login|uas/login|checkpoint/[a-z/-]+)/?").matches(url.encodedPath)

        /** A www redirect target in its plain `/feed/update/urn:li:…/` form; other hosts stay as sent (and are refused). */
        internal fun linkedInHopTarget(to: HttpUrl): HttpUrl = if (to.host == "www.linkedin.com") normalizeLinkedInEntry(to) else to

        private const val ACTIVITY = "activity"
        private val post = Regex("/posts/(${LINKEDIN_SLUG})/?")
        /** `/feed/update/<urn>` or its `/embed/` form, for an activity, share or ugcPost URN. */
        private val update = Regex("(?:/embed)?/feed/update/urn:li:(activity|share|ugcPost):([0-9]+)/?")
        /** Apps percent-encode the URN's colons (`urn%3Ali%3Ashare%3A…`); only that escape is decoded. */
        private val encodedColon = Regex("%3[Aa]")
        private val slugIds = Regex("(?:^|[_-])(activity|share|ugcPost)-([0-9]+)(?:-|$)")
        private val urn = Regex("urn:li:activity:([0-9]+)")
        private val attributed = Regex("urn:li:(share|ugcPost):([0-9]+)")

        fun parse(url: String): LinkedInPostIdentity? {
            val parsed = HttpsUrl.parse(url) ?: return null
            if (!isLinkedInPageEntry(parsed)) return null
            if (parsed.host == "lnkd.in") return LinkedInPostIdentity(true, null, emptyMap())
            post.matchEntire(parsed.encodedPath)?.let { match ->
                val slug = match.groupValues[1]
                // Exactly one typed ID in the slug; several are ambiguous and carry no typed identity.
                val typed = slugIds.findAll(slug).toList().singleOrNull()
                return LinkedInPostIdentity(false, slug,
                    typed?.let { mapOf(it.groupValues[1] to it.groupValues[2]) } ?: emptyMap())
            }
            update.matchEntire(updatePath(parsed.encodedPath))?.let {
                return LinkedInPostIdentity(false, null, mapOf(it.groupValues[1] to it.groupValues[2]))
            }
            return null
        }

        /** The update path with only `%3A` decoded; any other escape stays and fails the match. */
        internal fun updatePath(encodedPath: String): String =
            if (encodedPath.contains("/feed/update/")) encodedPath.replace(encodedColon, ":") else encodedPath

        internal fun activityUrn(value: String): String? = urn.matchEntire(value.trim())?.groupValues?.get(1)
    }

    /** Never true for a shortlink. Shared typed IDs must all agree; without one, slugs must be equal. */
    internal fun sameAs(other: LinkedInPostIdentity): Boolean {
        if (shortlink || other.shortlink) return false
        val shared = ids.keys intersect other.ids.keys
        if (shared.isNotEmpty()) return shared.all { ids[it] == other.ids[it] }
        return slug != null && slug == other.slug
    }

    /**
     * The page the user's link landed on as a clean public post URL (no query or fragment, trailing slash), the
     * same shapes the app hands to yt-dlp; null for a shortlink.
     */
    val cleanUrl: String? get() = when {
        shortlink -> null
        slug != null -> "https://www.linkedin.com/posts/$slug/"
        // The activity URN when known (also after a bridge), else the one share/ugcPost URN the link named.
        else -> (activityId?.let { ACTIVITY to it } ?: ids.entries.firstOrNull()?.toPair())
            ?.let { (kind, id) -> "https://www.linkedin.com/feed/update/urn:li:$kind:$id/" }
    }

    /**
     * True when [other] names a post by a different typed ID kind only (e.g. this landing's ugcPost/share slug and
     * the page's own activity canonical), so [sameAs] can neither prove nor refute that they are one post.
     */
    internal fun sharesNoIdKind(other: LinkedInPostIdentity): Boolean = !shortlink && !other.shortlink &&
        ids.isNotEmpty() && other.ids.isNotEmpty() && (ids.keys intersect other.ids.keys).isEmpty()

    /** The slug's author/title text before its one typed ID (`name_title-words-`), else null. */
    internal val vanity: String? get() = slug?.let { text ->
        slugIds.findAll(text).toList().singleOrNull()?.range?.first?.let { text.substring(0, it) }?.takeIf(String::isNotEmpty)
    }

    internal fun matchesActivity(id: String): Boolean = !shortlink && activityId != null && activityId == id

    /** True when [attributedUrn] (urn:li:share:N / urn:li:ugcPost:N) names this post's own typed ID. */
    internal fun matchesAttributed(attributedUrn: String): Boolean {
        val match = attributed.matchEntire(attributedUrn.trim()) ?: return false
        return !shortlink && ids[match.groupValues[1]] == match.groupValues[2]
    }

    /** Adds the activity ID evidenced by the fetched page's own post container; never replaces one. */
    internal fun withBridgedActivity(id: String): LinkedInPostIdentity =
        if (activityId != null) this else LinkedInPostIdentity(shortlink, slug, ids + (ACTIVITY to id))

    override fun toString() = "LinkedInPostIdentity(redacted)"
}
