package dev.ahmed.savlino.engine

import dev.ahmed.savlino.domain.DownloadError
import kotlin.test.*

class DevicePassErrorMappingTest {
    @Test fun site403IsRefusalWhileAnExpiredMediaUrlStillNeedsFormatRefresh() {
        // Sanitized site failure: no real post identifiers, URLs or titles.
        for (message in listOf("ERROR: [tumblr] sample: Unable to download webpage: HTTP Error 403: Forbidden",
            "ERROR: [tumblr] sample: HTTP Error 403: Forbidden", "ERROR: HTTP Error 403: Forbidden")) {
            assertEquals(DownloadError.SourceRefused, YtdlpErrorMapper.map(message))
        }
        assertEquals(DownloadError.FormatExpired,
            YtdlpErrorMapper.map("ERROR: unable to download video data: HTTP Error 403: Forbidden"))
        assertEquals(DownloadError.FormatExpired,
            YtdlpErrorMapper.map("ERROR: unable to download HLS fragment 2: HTTP Error 403: Forbidden"))
        assertEquals(DownloadError.Network, YtdlpErrorMapper.map("ERROR: [tumblr] sample: Connection reset by peer"))
    }

    @Test fun vimeoOAuth401AndLoginRequiredMapToAuthenticationWithoutGuessingPrivacy() {
        for (message in listOf("ERROR: [vimeo] sample: Login required", "ERROR: [vimeo] sample: Please log in",
            "ERROR: [vimeo] sample: Unable to download JSON metadata: HTTP Error 401: Unauthorized",
            "ERROR: [vimeo] sample: Unable to obtain OAuth token: HTTP Error 401: Unauthorized")) {
            assertEquals(DownloadError.LoginRequired, YtdlpErrorMapper.map(message))
        }
        // A 401 on the selected media URL is a stale selection, not a sign-in wall.
        assertEquals(DownloadError.FormatExpired,
            YtdlpErrorMapper.map("ERROR: unable to download video data: HTTP Error 401: Unauthorized"))
    }

    @Test fun blueskyAndTelegramPhotoOnlyMessagesUseNoVideoWithoutHidingNetworkOrLoginFailures() {
        for (extractor in listOf("Bluesky", "telegram")) {
            for (message in listOf("No video formats found!", "There is no video in this post", "no video in this post",
                "No video could be found in this post")) {
                assertEquals(DownloadError.NoVideo, YtdlpErrorMapper.map("ERROR: [$extractor] sample: $message"))
            }
            assertEquals(DownloadError.Network, YtdlpErrorMapper.map("ERROR: [$extractor] sample: No video formats found; Connection reset by peer"))
            assertEquals(DownloadError.LoginRequired, YtdlpErrorMapper.map("ERROR: [$extractor] sample: No video formats found; Login required"))
        }
        assertEquals(DownloadError.Failed, YtdlpErrorMapper.map("ERROR: [generic] sample: No video formats found!"))
        assertEquals(DownloadError.Failed, YtdlpErrorMapper.map("ERROR: [generic] sample: No video could be found in this post"))
        assertEquals(DownloadError.Failed, YtdlpErrorMapper.map("WARNING: [telegram] No video formats found!"))
        assertEquals(DownloadError.Failed, YtdlpErrorMapper.map("ERROR: Unknown failure https://media.example/HTTP-Error-403"))
    }
}
