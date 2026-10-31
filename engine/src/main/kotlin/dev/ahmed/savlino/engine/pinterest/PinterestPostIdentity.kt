package dev.ahmed.savlino.engine.pinterest

import dev.ahmed.savlino.engine.mediahttp.HttpsUrl
import okhttp3.HttpUrl

/**
 * A public Pinterest pin. Country hosts and a slug (`/pin/<words>--<id>/`) or invite landing (`/sent/`) are the same
 * pin; the only page this reader requests is `https://www.pinterest.com/pin/<id>/` with no query. `pin.it` is a
 * short link, resolved before routing, and is not a pin id.
 */
class PinterestPostIdentity private constructor(val pinId: String) {
    val fetchUrl: String get() = "https://$PAGE_HOST/pin/$pinId/"
    internal val pidgetsUrl: String get() = "https://$WIDGET_HOST/v3/pidgets/pins/info/?pin_ids=$pinId"
    override fun toString() = "PinterestPostIdentity(redacted)"

    companion object {
        const val PAGE_HOST = "www.pinterest.com"
        internal const val WIDGET_HOST = "widgets.pinterest.com"
        private val host = Regex("(?:(?:www|[a-z]{2})\\.)?pinterest\\.(?:com|fr|de|ch|jp|cl|ca|it|co\\.uk|nz|ru|" +
            "com\\.au|at|pt|co\\.kr|es|com\\.mx|dk|ph|th|com\\.uy|co|nl|info|kr|ie|vn|com\\.vn|ec|mx|in|pe|co\\.at|" +
            "hu|co\\.in|co\\.nz|id|com\\.ec|com\\.py|tw|be|uk|com\\.bo|com\\.pe)")
        private val path = Regex("/pin/(?:[A-Za-z0-9_-]{1,200}--)?([0-9]{1,30})(?:/sent)?/?")
        private val login = Regex("/login(?:/.*)?")

        fun parse(raw: String): PinterestPostIdentity? = HttpsUrl.parse(raw)?.let(::parse)

        internal fun parse(url: HttpUrl): PinterestPostIdentity? {
            if (!url.isHttps || url.port != 443 || !host.matches(url.host)) return null
            val id = path.matchEntire(url.encodedPath)?.groupValues?.get(1) ?: return null
            return PinterestPostIdentity(id)
        }

        /** The canonical page read: `https://www.pinterest.com/pin/<id>/`, no query. */
        internal fun isPageRequest(url: HttpUrl): Boolean = url.isHttps && url.port == 443 && url.host == PAGE_HOST &&
            url.encodedQuery == null && parse(url)?.fetchUrl == url.toString()

        internal fun isPidgetsRequest(url: HttpUrl): Boolean = url.isHttps && url.port == 443 &&
            url.host == WIDGET_HOST && url.encodedPath == "/v3/pidgets/pins/info/" && url.fragment == null &&
            Regex("pin_ids=[0-9]{1,30}").matches(url.encodedQuery.orEmpty())

        internal fun isPinterestHost(host: String): Boolean = this.host.matches(host.lowercase())

        internal fun isLoginWall(url: HttpUrl): Boolean = isPinterestHost(url.host) && login.matches(url.encodedPath)
    }
}
