package dev.ahmed.savlino.engine.mediahttp

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MediaHttpPolicyTest {
    @Test fun gifPhotosUseTheSameCdnHostAndPathLimits() {
        assertTrue(MediaHttpPolicy.isXImage(assertNotNull(MediaHttpPolicy.parse("https://pbs.twimg.com/media/Synthetic.gif"))))
        assertFalse(MediaHttpPolicy.isXImage(assertNotNull(MediaHttpPolicy.parse("https://video.twimg.com/tweet_video/Synthetic.gif"))))
        assertTrue(MediaHttpPolicy.isInstagramImage(assertNotNull(MediaHttpPolicy.parse(
            "https://instagram.fxyz1-1.fna.fbcdn.net/v/t51.2885-15/Synthetic.gif"))))
        assertFalse(MediaHttpPolicy.isInstagramImage(assertNotNull(MediaHttpPolicy.parse(
            "https://instagram.fxyz1-1.fna.fbcdn.net/other/Synthetic.gif"))))
    }

    @Test fun fragmentsAreStrippedWithoutChangingPathOrQueryBytes() {
        val raw = "https://media.test/a%2Fb.jpg?q=%2f&x=1"
        assertEquals(raw, MediaHttpPolicy.parse("$raw#fragment")?.toString())
        val base = assertNotNull(MediaHttpPolicy.parse("https://media.test/base"))
        assertEquals(raw, MediaHttpPolicy.resolve(base, "/a%2Fb.jpg?q=%2f&x=1#fragment")?.toString())
    }

    @Test fun linkedinEntryHostsAreBroaderThanRedirectHops() {
        for (host in listOf("linkedin.com", "ae.linkedin.com", "www.linkedin.com"))
            assertTrue(MediaHttpPolicy.isLinkedInPageEntry(assertNotNull(MediaHttpPolicy.parse("https://$host/posts/synthetic-activity-123/"))))
        assertFalse(MediaHttpPolicy.isLinkedInPageEntry(assertNotNull(MediaHttpPolicy.parse("https://ae.linkedin.com.evil.com/posts/synthetic-activity-123/"))))
        val www = assertNotNull(MediaHttpPolicy.parse("https://www.linkedin.com/posts/synthetic-activity-123/"))
        assertFalse(MediaHttpPolicy.allowsLinkedInPageHop(www, assertNotNull(MediaHttpPolicy.parse("https://ae.linkedin.com/posts/synthetic-activity-123/"))))
    }

    @Test fun rejectsEveryIpv4SpecialPurposeBoundaryAndAllowsAdjacentPublicAddresses() {
        // Each row is independently specified: first, last, preceding address, following address.
        val rows = listOf(
            arrayOf("0.0.0.0", "0.255.255.255", "255.255.255.255", "1.0.0.0", "false", "true"),
            arrayOf("10.0.0.0", "10.255.255.255", "9.255.255.255", "11.0.0.0", "true", "true"),
            arrayOf("100.64.0.0", "100.127.255.255", "100.63.255.255", "100.128.0.0", "true", "true"),
            arrayOf("127.0.0.0", "127.255.255.255", "126.255.255.255", "128.0.0.0", "true", "true"),
            arrayOf("169.254.0.0", "169.254.255.255", "169.253.255.255", "169.255.0.0", "true", "true"),
            arrayOf("172.16.0.0", "172.31.255.255", "172.15.255.255", "172.32.0.0", "true", "true"),
            arrayOf("192.0.0.0", "192.0.0.255", "191.255.255.255", "192.0.1.0", "true", "true"),
            arrayOf("192.0.2.0", "192.0.2.255", "192.0.1.255", "192.0.3.0", "true", "true"),
            arrayOf("192.88.99.0", "192.88.99.255", "192.88.98.255", "192.88.100.0", "true", "true"),
            arrayOf("192.168.0.0", "192.168.255.255", "192.167.255.255", "192.169.0.0", "true", "true"),
            arrayOf("198.18.0.0", "198.19.255.255", "198.17.255.255", "198.20.0.0", "true", "true"),
            arrayOf("198.51.100.0", "198.51.100.255", "198.51.99.255", "198.51.101.0", "true", "true"),
            arrayOf("203.0.113.0", "203.0.113.255", "203.0.112.255", "203.0.114.0", "true", "true"),
            arrayOf("224.0.0.0", "239.255.255.255", "223.255.255.255", "240.0.0.0", "true", "false"),
            arrayOf("240.0.0.0", "255.255.255.255", "239.255.255.255", "0.0.0.0", "false", "false")
        )
        for ((index, row) in rows.withIndex()) {
            assertTrue(MediaHttpPolicy.allows(PUBLIC_V4), "public control for prefix $index")
            assertFalse(MediaHttpPolicy.allows(address(row[0])), "prefix $index first denied")
            assertFalse(MediaHttpPolicy.allows(address(row[1])), "prefix $index last denied")
            assertEquals(row[4].toBoolean(), MediaHttpPolicy.allows(address(row[2])), "prefix $index preceding")
            assertEquals(row[5].toBoolean(), MediaHttpPolicy.allows(address(row[3])), "prefix $index following")
        }
    }

    @Test fun ipv6GlobalAllowlistExcludesProtocolDocumentationAndTransitionBoundaries() {
        val cases = listOf(
            "1fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to false,
            "2000::" to true, "3fff:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to true, "4000::" to false,
            "2000:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to true,
            "2001::" to false, "2001:1ff:ffff:ffff:ffff:ffff:ffff:ffff" to false, "2001:200::" to true,
            "2001:db7:ffff:ffff:ffff:ffff:ffff:ffff" to true,
            "2001:db8::" to false, "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff" to false, "2001:db9::" to true,
            "2001:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to true,
            "2002::" to false, "2002:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to false, "2003::" to true,
            "3ffe:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to true,
            "3fff::" to false, "3fff:fff:ffff:ffff:ffff:ffff:ffff:ffff" to false, "3fff:1000::" to true,
            "5eff:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to false,
            "5f00::" to false, "5f00:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to false, "5f01::" to false,
            "::" to false, "::1" to false, "::8.8.8.8" to false,
            "100::" to false, "100::ffff:ffff:ffff:ffff" to false,
            "fc00::" to false, "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to false,
            "fe80::" to false, "febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to false,
            "fec0::" to false, "feff:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to false,
            "ff00::" to false, "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff" to false)
        for ((index, pair) in cases.withIndex()) {
            assertTrue(MediaHttpPolicy.allows(address("2606:4700:4700::1111")), "IPv6 public control $index")
            assertEquals(pair.second, MediaHttpPolicy.allows(address(pair.first)), "IPv6 boundary $index")
        }
    }

    @Test fun nat64WellKnownPrefixUsesEmbeddedIpv4PolicyButLocalPrefixIsDenied() {
        val cases = listOf("64:ff9b::808:808" to true, "64:ff9b::101:101" to true,
            "64:ff9b::a00:1" to false, "64:ff9b::7f00:1" to false,
            "64:ff9b::c000:201" to false, "64:ff9b::e000:1" to false,
            "64:ff9b::" to false, "64:ff9b::ffff:ffff" to false,
            "64:ff9a:ffff:ffff:ffff:ffff:ffff:ffff" to false, "64:ff9b:0:1::" to false,
            "64:ff9b:1::" to false, "64:ff9b:1:ffff:ffff:ffff:ffff:ffff" to false,
            "64:ff9b:2::" to false, "::ffff:8.8.8.8" to true,
            "::ffff:127.0.0.1" to false, "::ffff:10.0.0.1" to false)
        for ((index, pair) in cases.withIndex()) {
            assertTrue(MediaHttpPolicy.allows(PUBLIC_V4), "NAT64 public control $index")
            assertEquals(pair.second, MediaHttpPolicy.allows(address(pair.first)), "NAT64 case $index")
        }
    }

    @Test fun urlPolicyRejectsLiteralNumericCredentialsPortsAndParserRewrites() {
        val rejected = listOf("http://media.test/image", "https://media.test:444/image",
            "https://user@media.test/image", "https://user:pass@media.test/image",
            "https://@media.test/image", "https://127.1/", "https://2130706433/", "https://10.1/",
            "https://[::ffff:7f00:1]/", "https://[2606:4700:4700::1111]/", "https://8.8.8.8/",
            "https://0x7f000001/", "https://0177.1/", "https://media.123/", "https://media.0x7f/",
            "https://2130706433./", "https://media.123./", " https://media.test/image",
            "https://media.test/im\nage", "https://media.test/\u202Eimage", "https://media.test\\image",
            "https://media.test/a/../image", "https://media.test/%2e%2e/image",
            "https://media.test/" + "a".repeat(8192))
        for ((index, raw) in rejected.withIndex()) {
            assertNotNull(MediaHttpPolicy.parse("https://media.test/image"), "URL positive control $index")
            assertNull(MediaHttpPolicy.parse(raw), "unsafe URL case $index")
        }
    }

    @Test fun acceptedSignedPathAndQueryRemainByteExact() {
        val raw = "https://media.test/a%2Fb/image?sig=Ab%2fCD%2B%3d&x=1+2&empty=&repeat=1&repeat=2"
        val parsed = assertNotNull(MediaHttpPolicy.parse(raw), "signed URL accepted")
        assertTrue(parsed.encodedPath == "/a%2Fb/image", "encoded path preserved")
        assertTrue(parsed.encodedQuery == "sig=Ab%2fCD%2B%3d&x=1+2&empty=&repeat=1&repeat=2", "encoded query preserved")
        assertNotNull(MediaHttpPolicy.parse("https://media.test:443/image"), "explicit default TLS port accepted")
    }

    @Test fun publicContractsNeverRenderRawSourceOrSignedTarget() {
        val raw = "https://media.test/private?token=synthetic-secret"
        val request = PublicMediaRequest(raw, MediaHttpPurpose.IMAGE)
        val client = MediaHttpClient.create()
        client.use {
            client.newOperation(MediaHttpOperation.DIRECT_IMAGE).use { token ->
                for (value in listOf(request.toString(), token.toString(), token.id.toString(), token.budget.toString())) {
                    assertFalse(value.contains("media.test") || value.contains("synthetic-secret"), "opaque contract rendering")
                }
            }
        }
    }
}
