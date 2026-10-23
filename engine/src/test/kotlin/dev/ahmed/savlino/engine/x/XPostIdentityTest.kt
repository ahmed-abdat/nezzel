package dev.ahmed.savlino.engine.x

import org.junit.Test
import kotlin.test.*

class XPostIdentityTest {
    private fun id(url: String) = XPostIdentity.parse(url)?.postId

    @Test fun mirrorsUseOnlyThePostIdForSyndication() {
        for (host in listOf("fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com")) {
            for (prefix in listOf("", "www.")) {
                val id = assertNotNull(XPostIdentity.parse("https://$prefix$host/synthetic/status/1790000000000000001"))
                assertTrue(id.syndicationUrl.startsWith("https://cdn.syndication.twimg.com/"))
                assertEquals("https://x.com/i/status/1790000000000000001", id.legacyUrl)
            }
            assertNull(XPostIdentity.parse("https://$host.evil.com/synthetic/status/1790000000000000001"))
        }
    }

    @Test fun acceptedPostShapesKeepOnlyTheNumericId() {
        val id = "1790000000000000001"
        for (url in listOf("https://x.com/someone/status/$id", "https://twitter.com/someone/status/$id/",
            "https://mobile.twitter.com/some_one/status/$id?s=20&t=Synthetic", "https://www.x.com/someone/status/$id",
            "https://x.com/i/status/$id", "https://x.com/i/web/status/$id", "https://x.com/someone/status/$id/photo/1",
            "https://x.com/someone/status/$id/photo/4", "https://twitter.com/someone/status/$id/video/1",
            "https://X.com/someone/status/$id"))
            assertEquals(id, id(url), url)
    }

    /** The `/photo|video/<n>` yt-dlp will read: only from the user's own link; a mirror's x.com link drops it. */
    @Test fun mediaIndexIsTheOneYtDlpSees() {
        val id = "1790000000000000001"
        fun index(url: String) = assertNotNull(XPostIdentity.parse(url)).mediaIndex
        assertEquals(2, index("https://x.com/someone/status/$id/video/2"))
        assertEquals(1, index("https://twitter.com/someone/status/$id/photo/1?s=20"))
        assertNull(index("https://x.com/someone/status/$id"))
        assertNull(index("https://fxtwitter.com/someone/status/$id/video/2"))
    }

    @Test fun otherXPathsHostsAndSchemesAreRejected() {
        for (url in listOf("https://x.com/someone", "https://x.com/home", "https://x.com/someone/status/",
            "https://x.com/someone/status/abc", "https://x.com/someone/status/0123", "https://x.com/someone/status/12/likes",
            "https://x.com/someone/status/12/photo/5", "https://x.com/i/spaces/1AbC", "https://x.com/someone/status/12/status/13",
            "https://x.com/a.b/status/12", "https://x.com/abcdefghijklmnop/status/12", "https://x.com.evil.example/a/status/12",
            "https://evilx.com/a/status/12", "http://x.com/a/status/12", "https://x.com:8443/a/status/12",
            "https://user@x.com/a/status/12", "https://pbs.twimg.com/media/Abc.jpg",
            "https://x.com/a/status/99999999999999999999", "https://t.co/AbCdEf"))
            assertNull(id(url), url)
    }

    @Test fun syndicationUrlCarriesOnlyIdAndToken() {
        val identity = assertNotNull(XPostIdentity.parse("https://x.com/someone/status/1234567890123456789?s=46"))
        assertEquals("https://cdn.syndication.twimg.com/tweet-result?id=1234567890123456789&token=2zqic77uqyk",
            identity.syndicationUrl)
        assertEquals("XPostIdentity(redacted)", identity.toString())
    }

    /** Vectors from Node's `((Number(id)/1e15)*Math.PI).toString(36).replace(/(0+|\.)/g,"")` (synthetic ids). */
    @Test fun tokenMatchesTheJavaScriptReferenceForSyntheticIds() {
        val vectors = listOf("1221368500568277588" to "2yl1itydzdl", "1395494634720187923" to "3ds2q3ifpkb",
            "1136879138292273062" to "2r7m1rfnds", "1116634109187857277" to "2pgcbw5mbp",
            "1606832857977215304" to "3w8ija5eh5", "1438887797624662775" to "3hkedjcdjv3",
            "1244287851621603258" to "3l1n2a5ncd", "1021787319773377281" to "2h61f8nj89q",
            "1637404968822372220" to "3yw25cgvfl", "1771096822299788827" to "4ak2bxpk83q",
            "1262186848977647886" to "3259ywjrhx", "1134202821395069637" to "2qz7bew9g3q",
            "1281784049007202544" to "33uuczf9d9a", "1839042189418924927" to "4ghirrxe9a5",
            "1716671114685843362" to "45t2xg1sv92", "1737916015043425993" to "47nto57g6a",
            "1490838124491037092" to "3m3lti6kn38", "1609801517831757603" to "3whc9g2fco6",
            "1729343074500958070" to "46ww3dqygva", "1388224477889129229" to "3d58hmh6fr",
            "1536629793265927576" to "3q3gqgyru3", "1750065588853515527" to "48pzr6lz9mx",
            "1490583644128083082" to "3m2t1dxuw6o", "1106589591382678417" to "2okgc19kz5",
            "1" to "bhi2ay3f28n", "20" to "6dq1a2xwd93", "123456789" to "ng9m4sqe3h",
            "1234567890123456789" to "2zqic77uqyk", "1790000000000000001" to "4c7g8auqyik")
        for ((id, token) in vectors) assertEquals(token, XPostIdentity.token(id), id)
    }

    @Test fun radixConversionMatchesV8IncludingCarryAndLargeIntegers() {
        val vectors = listOf(0.5 to "0.i", 1.0 / 3 to "0.c", 123.456 to "3f.gez4w97ry", 1e20 to "l3r41ifs0p800",
            1152921504606859300.0 to "8rc4kbdvt1k0", 0.1 to "0.3lllllllllm", 0.0 to "0", 35.0 to "z", 36.0 to "10")
        for ((value, expected) in vectors) assertEquals(expected, XPostIdentity.jsRadixString(value, 36), value.toString())
    }

    @Test fun syndicationRequestShapeIsExact() {
        val ok = "id=1234567890123456789&token=2zqic77uqyk"
        assertTrue(XPostIdentity.isSyndicationRequest("cdn.syndication.twimg.com", "/tweet-result", ok))
        assertFalse(XPostIdentity.isSyndicationRequest("cdn.syndication.twimg.com", "/tweet-result", "$ok&lang=en"))
        assertFalse(XPostIdentity.isSyndicationRequest("cdn.syndication.twimg.com", "/tweet-result",
            "id=1234567890123456789&token=wrong"))
        assertFalse(XPostIdentity.isSyndicationRequest("cdn.syndication.twimg.com", "/timeline-profile", ok))
        assertFalse(XPostIdentity.isSyndicationRequest("syndication.twitter.com", "/tweet-result", ok))
        assertFalse(XPostIdentity.isSyndicationRequest("cdn.syndication.twimg.com", "/tweet-result", null))
    }

    /** url-shapes: m. hosts, d.fxtwitter and fxtwitter's translated `/<lang>` view keep the post id. */
    @Test fun mobileHostsDirectMirrorAndTranslatedViewKeepTheId() {
        val id = "1790000000000000001"
        for (url in listOf("https://m.twitter.com/someone/status/$id", "https://m.x.com/someone/status/$id?s=20",
            "https://d.fxtwitter.com/someone/status/$id", "https://fxtwitter.com/someone/status/$id/ar",
            "https://www.fxtwitter.com/someone/status/$id/en/", "https://d.fxtwitter.com/i/status/$id/fr"))
            assertEquals(id, id(url), url)
        assertEquals("https://x.com/i/status/$id", XPostIdentity.parse("https://d.fxtwitter.com/someone/status/$id")?.legacyUrl)
        assertNull(XPostIdentity.parse("https://m.x.com/someone/status/$id")?.legacyUrl) // yt-dlp reads the user's own link
        for (url in listOf("https://x.com/someone/status/$id/ar", "https://fxtwitter.com/someone/status/$id/arabic",
            "https://fxtwitter.com/someone/status/$id/ar/extra", "https://e.fxtwitter.com/someone/status/$id",
            "https://m.x.com.evil.test/someone/status/$id"))
            assertNull(XPostIdentity.parse(url), url)
    }
}
