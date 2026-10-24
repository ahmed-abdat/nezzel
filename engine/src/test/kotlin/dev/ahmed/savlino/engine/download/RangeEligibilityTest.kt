package dev.ahmed.savlino.engine.download

import dev.ahmed.savlino.domain.*
import org.junit.Test
import kotlin.test.*

class RangeEligibilityTest {
    private val row = Format("progressive", "mp4", true, true)
    private val json = """{"http_headers":{"User-Agent":"base","Cookie":"secret"},"formats":[{"format_id":"progressive","url":"https://example.com/video.mp4","protocol":"https","ext":"mp4","vcodec":"h264","acodec":"aac","http_headers":{"User-Agent":"row"}}]}"""
    @Test fun acceptsOnlyConfirmedProgressiveRowsWithoutSizeThreshold() {
        for (site in listOf(Host.TIKTOK, Host.FACEBOOK, Host.INSTAGRAM, Host.X)) {
            val request = assertNotNull(RangeEligibility.select(site, row, json) { true })
            assertEquals("row", request.headers["User-Agent"])
            assertEquals("secret", request.headers["Cookie"])
        }
        assertNull(RangeEligibility.select(Host.YOUTUBE, row, json) { true })
        assertNull(RangeEligibility.select(Host.X, row, json) { false })
        assertNull(RangeEligibility.select(Host.X, row.copy(container = "webm"), json) { true })
        assertNull(RangeEligibility.select(Host.X, row.copy(audioFormatId = "audio", requiresMerge = true), json) { true })
        assertNull(RangeEligibility.select(Host.X, row, json.replace("\"protocol\":\"https\"", "\"protocol\":\"http_dash_segments\"")) { true })
        assertNull(RangeEligibility.select(Host.X, row, json.replace("https://", "http://")) { true })
        assertNull(RangeEligibility.select(Host.X, row, json.replace("progressive", "other")) { true })
    }
    @Test fun confirmedSilentProgressiveMp4IsEligibleWithoutInventingAudio() {
        val silent = row.copy(hasAudio = false)
        assertNotNull(RangeEligibility.select(Host.X, silent, json.replace("\"acodec\":\"aac\"", "\"acodec\":\"none\"")) { true })
    }
    @Test fun serializedYtdlpCookiesAreScopedToTheSelectedHostAndPath() {
        val scoped = json.replace("\"ext\":\"mp4\"", "\"ext\":\"mp4\",\"cookies\":\"session=allowed; Domain=.example.com; Path=/; Secure; wrong=blocked; Domain=.example.org; Path=/; Secure\"")
        assertEquals("session=allowed", assertNotNull(RangeEligibility.select(Host.X, row,
            scoped.replace("\"Cookie\":\"secret\",", "").replace(",\"Cookie\":\"secret\"", "")) { true }).headers["Cookie"])
    }

}
