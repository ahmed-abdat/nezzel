package dev.ahmed.savlino.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class ManifestReleaseTest {
    private fun manifest() = JSONObject().put("schema", 1).put("version", "0.4.4").put("versionCode", 400)
        .put("apk", JSONObject().put("name", "nezzel-0.4.4.apk").put("size", 123).put("sha256", "a".repeat(64)))
        .put("notes", JSONObject().put("en", JSONArray().put("Fixed")).put("ar", JSONArray().put("إصلاح")))
    private fun parse(json: JSONObject) = Release.parseManifest(json.toString().toByteArray())

    @Test fun validManifestDerivesUrlAndIgnoresUntrustedUrlKeys() {
        val json = manifest().put("url", "https://foreign.invalid/asset").put("unknown", true)
        json.getJSONObject("apk").put("url", "https://foreign.invalid/asset")
        val release = parse(json)!!
        assertEquals("0.4.4", release.version); assertEquals(400L, release.code)
        assertEquals(123L, release.size); assertEquals("a".repeat(64), release.digest)
        assertEquals("https://github.com/ahmed-abdat/nezzel/releases/download/v0.4.4/nezzel-0.4.4.apk", release.url)
        assertEquals(listOf("Fixed"), release.notes("en")); assertEquals(listOf("إصلاح"), release.notes("ar"))
        assertEquals(listOf("Fixed"), release.notes("fr"))
    }
    @Test fun importantRequiresAnActualBooleanTrue() {
        assertFalse(parse(manifest())!!.important)
        assertTrue(parse(manifest().put("important", true))!!.important)
        for (value in listOf(false, "true", "false", 1, JSONObject.NULL, JSONObject())) {
            assertFalse(parse(manifest().put("important", value))!!.important)
        }
    }
    @Test fun everyRequiredFieldMustBePresent() {
        listOf("schema", "version", "versionCode", "apk", "notes").forEach { field ->
            assertNull(field, parse(manifest().apply { remove(field) }))
        }
        listOf("name", "size", "sha256").forEach { field ->
            assertNull(field, parse(manifest().apply { getJSONObject("apk").remove(field) }))
        }
    }
    @Test fun optionalFrenchNotesUseSharedCleaningAndEnglishFallback() {
        val json = manifest()
        assertEquals(listOf("Fixed"), parse(json)!!.notes("fr"))
        json.getJSONObject("notes").put("fr", JSONArray().put("**Corrige** `vidéo`\u2066"))
        val translated = parse(json)!!
        assertEquals(listOf("Corrige vidéo"), translated.notes("fr"))
        assertEquals(listOf("Fixed"), translated.notes("en"))
        assertEquals(listOf("إصلاح"), translated.notes("ar"))
        json.getJSONObject("notes").put("fr", JSONArray().put(" "))
        assertEquals(listOf("Fixed"), parse(json)!!.notes("fr"))
    }
    @Test fun rejectsMalformedRequiredFieldsWithoutCoercion() {
        val bad = mapOf("schema" to listOf<Any>(2, "1", 1.5, true, JSONObject.NULL),
            "version" to listOf<Any>(1, "v0.4.4", "01.4.4", "0.4.4-01", "0.4.4/path", JSONObject.NULL),
            "versionCode" to listOf<Any>(0, -1, "400", 400.5, true, JSONObject.NULL),
            "apk" to listOf<Any>("apk", JSONArray(), JSONObject.NULL),
            "notes" to listOf<Any>("notes", JSONArray(), JSONObject.NULL))
        bad.forEach { (field, values) -> values.forEach { value -> assertNull("$field=$value", parse(manifest().put(field, value))) } }
        assertNull(Release.parseManifest(manifest().toString().replace("\"schema\":1", "\"schema\":1.0").toByteArray()))
        val badApk = mapOf("name" to listOf<Any>(12, "../evil.apk", "a/b.apk", "x.zip", JSONObject.NULL),
            "size" to listOf<Any>(0, -1, "123", 123.5, true, Long.MAX_VALUE, JSONObject.NULL),
            "sha256" to listOf<Any>(12, "abc", "g".repeat(64), JSONObject.NULL))
        badApk.forEach { (field, values) -> values.forEach { value ->
            assertNull("apk.$field=$value", parse(manifest().apply { getJSONObject("apk").put(field, value) }))
        } }
    }
    @Test fun rejectsOversizeMalformedJsonAndNonStringNotes() {
        assertNull(Release.parseManifest(ByteArray(65537) { 32 }))
        listOf("garbage", "{} trailing", "{'schema':1}", "{\"schema\":1,}").forEach { assertNull(Release.parseManifest(it.toByteArray())) }
        val valid = manifest().toString()
        assertNull(Release.parseManifest(valid.replace('"', '\'').toByteArray()))
        assertNull(Release.parseManifest((valid.dropLast(1) + ",}").toByteArray()))
        listOf<Any>("not an array", JSONObject.NULL, JSONArray().put(1), JSONArray().put(true)).forEach { value ->
            assertNull(parse(manifest().apply { getJSONObject("notes").put("en", value) }))
        }
    }
    @Test fun notesUseSharedCleaningBoundsAndEnglishFallback() {
        val json = manifest()
        val english = JSONArray().put("**Fix** `video` [link](discard) <b>text</b>\u202e\u2066\u0001")
        repeat(10) { english.put("x".repeat(1005)) }
        json.getJSONObject("notes").put("en", english).put("ar", JSONArray())
        val release = parse(json)!!
        assertEquals(8, release.notes("en").size)
        assertEquals("Fix video link text", release.notes("en")[0])
        assertEquals(1000, release.notes("en")[1].length)
        assertEquals(release.notes("en"), release.notes("ar"))
        json.getJSONObject("notes").remove("ar")
        assertEquals(release.notes("en"), parse(json)!!.notes("ar"))
    }
    @Test fun rejectsSizesWhoseTwoCopiesAndMarginWouldOverflow() {
        val json = manifest()
        json.getJSONObject("apk").put("size", 4_611_686_018_393_833_471L)
        assertNotNull(parse(json))
        json.getJSONObject("apk").put("size", 4_611_686_018_393_833_472L)
        assertNull(parse(json))
    }
}
