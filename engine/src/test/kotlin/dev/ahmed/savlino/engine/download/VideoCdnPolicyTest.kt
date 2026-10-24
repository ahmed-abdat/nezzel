package dev.ahmed.savlino.engine.download

import dev.ahmed.savlino.domain.Host
import dev.ahmed.savlino.engine.mediahttp.VideoCdnPolicy
import java.net.InetAddress
import org.junit.Test
import kotlin.test.*

class VideoCdnPolicyTest {
    @Test fun restrictsHostsAndPathsToVideoProviders() {
        assertTrue(VideoCdnPolicy.hostPath(Host.X, "video.twimg.com", "/ext_tw_video/example/pu/vid/avc1/video.mp4"))
        assertFalse(VideoCdnPolicy.hostPath(Host.X, "video.twimg.com.example.com", "/ext_tw_video/video.mp4"))
        assertFalse(VideoCdnPolicy.hostPath(Host.X, "video.twimg.com", "/profile_images/video.mp4"))
        assertTrue(VideoCdnPolicy.hostPath(Host.FACEBOOK, "video.example.fbcdn.net", "/o1/v/t2/f2/video.mp4"))
        assertTrue(VideoCdnPolicy.hostPath(Host.INSTAGRAM, "scontent-example.cdninstagram.com", "/o1/v/t2/f2/video.mp4"))
        assertTrue(VideoCdnPolicy.hostPath(Host.TIKTOK, "v-example.tiktokcdn.com", "/video/example"))
        assertFalse(VideoCdnPolicy.hostPath(Host.YOUTUBE, "video.twimg.com", "/ext_tw_video/video.mp4"))
    }
    @Test fun privateAndSpecialAddressesFailClosed() {
        for (bytes in listOf(byteArrayOf(127,0,0,1), byteArrayOf(10,0,0,1), byteArrayOf(169.toByte(),254.toByte(),0,1),
            ByteArray(16).apply { this[15] = 1 }, ByteArray(16).apply { this[0] = 0xfc.toByte() })) {
            assertFalse(VideoCdnPolicy.publicAddress(InetAddress.getByAddress(bytes)))
        }
    }
}
