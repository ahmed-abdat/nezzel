package dev.ahmed.savlino.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ReleaseTest {
    private fun release(body: String = "<!-- nezzel:versionCode=362 -->"): JSONObject = JSONObject().apply {
        put("tag_name", "v0.4.4"); put("body", body)
        put("assets", org.json.JSONArray().put(JSONObject().apply {
            put("name", "nezzel.apk"); put("size", 100); put("browser_download_url", "https://github.com/asset")
            put("digest", "sha256:" + "a".repeat(64))
        }))
    }
    private fun parse(value: JSONObject) = Release.parse(value.toString().toByteArray())
    @Test fun parsesRequiredIdentityAndBothNotesWithLocaleFallback() {
        val r = parse(release("""<!-- nezzel:versionCode=362 -->
<!-- nezzel:notes:en -->
- **Fix** `video`
not a bullet
<!-- /nezzel:notes -->
<!-- nezzel:notes:ar -->
- إصلاح
<!-- /nezzel:notes -->"""))!!
        assertEquals(362L, r.code); assertEquals("0.4.4", r.version)
        assertEquals(listOf("Fix video"), r.notes("en")); assertEquals(listOf("إصلاح"), r.notes("ar"))
        assertEquals(listOf("Fix video"), r.notes("fr"))
        assertEquals(r.notes("en"), parse(release("<!-- nezzel:versionCode=362 --><!-- nezzel:notes:en -->\n- **Fix** `video`\n<!-- /nezzel:notes -->"))!!.notes("ar"))
    }
    @Test fun missingMalformedAndDuplicateMarkersFailClosed() {
        listOf("", "<!-- nezzel:versionCode=no -->", "<!-- nezzel:versionCode=0 -->", "<!-- nezzel:versionCode=362 --><!-- nezzel:versionCode=363 -->")
            .forEach { assertNull(parse(release(it))) }
    }
    @Test fun digestAndExactlyOneSafeApkAreRequired() {
        val json = release(); val assets = json.getJSONArray("assets"); val asset = assets.getJSONObject(0)
        asset.remove("digest"); assertNull(parse(json))
        asset.put("digest", "sha256:abc"); assertNull(parse(json))
        asset.put("digest", "sha256:" + "a".repeat(64)); assets.put(JSONObject(asset.toString())); assertNull(parse(json))
        assertNull(parse(release().put("assets", org.json.JSONArray())))
        listOf("../update.apk", "a/b.apk", "other.zip").forEach { name ->
            val r = release(); r.getJSONArray("assets").getJSONObject(0).put("name", name); assertNull(parse(r))
        }
    }
    @Test fun capsNotesAndRejectsMalformedOrOversizeResponses() {
        val r = parse(release("<!-- nezzel:versionCode=362 --><!-- nezzel:notes:en -->\n" + (1..12).joinToString("\n") { "- item $it" } + "\n<!-- /nezzel:notes -->"))!!
        assertEquals(8, r.notes("en").size)
        assertNull(Release.parse("broken".toByteArray()))
        assertNull(Release.parse(ByteArray(262145) { 32 }))
        assertNull(parse(release().put("tag_name", "vwrong")))
        assertNull(parse(release().put("draft", true)))
    }
    @Test fun rejectsSizesWhoseTwoCopiesAndMarginWouldOverflow() {
        val json = release()
        val apk = json.getJSONArray("assets").getJSONObject(0)
        apk.put("size", 4_611_686_018_393_833_471L)
        assertNotNull(parse(json))
        apk.put("size", 4_611_686_018_393_833_472L)
        assertNull(parse(json))
    }
}
