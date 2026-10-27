package dev.ahmed.savlino.engine.mediahttp

import dev.ahmed.savlino.domain.Host
import java.net.InetAddress
import okhttp3.HttpUrl

internal object VideoCdnPolicy {
    fun allows(site: Host, raw: String): HttpUrl? = HttpsUrl.parse(raw)?.takeIf { hostPath(site, it.host, it.encodedPath) }
    fun hostPath(site: Host, host: String, path: String): Boolean {
        if (!path.startsWith("/") || path.contains("..") || path.contains('\\')) return false
        return when (site) {
            Host.X -> host == "video.twimg.com" &&
                Regex("/(?:ext_tw_video|amplify_video|tweet_video)/[A-Za-z0-9_./-]+\\.mp4").matches(path)
            Host.FACEBOOK -> facebook(host) && videoPath(path)
            Host.INSTAGRAM -> (facebook(host) || Regex("scontent[a-z0-9.-]*\\.cdninstagram\\.com").matches(host)) && videoPath(path)
            Host.TIKTOK -> Regex("[a-z0-9][a-z0-9.-]*\\.(?:tiktokcdn(?:-us)?\\.com|tiktokcdn-eu\\.com|tiktokv\\.com|byteoversea\\.com|ibytedtos\\.com|ibyteimg\\.com|muscdn\\.com)").matches(host) &&
                Regex("/[A-Za-z0-9_./%=-]+").matches(path)
            else -> false
        }
    }
    private fun facebook(host: String) = Regex("(?:video|scontent|instagram)[a-z0-9.-]*\\.fbcdn\\.net").matches(host)
    private fun videoPath(path: String) = Regex("/(?:o[0-9]+/)?v/[A-Za-z0-9_./%-]+\\.mp4").matches(path)
    fun publicAddress(address: InetAddress): Boolean = MediaHttpPolicy.allows(address)
}
