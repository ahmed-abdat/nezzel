package dev.ahmed.savlino

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class LocaleResourcesTest {
    private val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
    private fun entries(directory: String, translatedOnly: Boolean = true): Map<String, Element> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(res, "$directory/strings.xml")).documentElement.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
            .filter { !translatedOnly || it.getAttribute("translatable") != "false" }
            .associateBy { it.getAttribute("name") }
    }
    private fun items(element: Element): List<Element> = element.getElementsByTagName("item").let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as Element }
    }
    private fun arguments(text: String): List<String> =
        Regex("%(?:[0-9]+\\$)?[sd]").findAll(text).map { it.value }.sorted().toList()

    @Test fun multiMediaPostCopyKeepsArabicAndFrenchArgumentsInParity() {
        val english = entries("values")
        for (locale in listOf("values-ar", "values-fr")) {
            val translated = entries(locale)
            for (name in listOf("saved_post_position", "tiktok_sound", "image_expired", "image_expired_body")) {
                val entry = translated.getValue(name)
                assertTrue("$locale/$name", entry.textContent.isNotBlank())
                assertEquals("$locale/$name", arguments(english.getValue(name).textContent), arguments(entry.textContent))
            }
        }
    }

    @Test fun libraryPolishCopyHasArabicAndFrenchParity() {
        val english = entries("values")
        val names = listOf("filter_videos", "filter_images", "saved_sort_accessibility", "saved_sort_newest",
            "saved_sort_largest", "saved_sort_name", "saved_rename", "saved_rename_name", "saved_rename_extension",
            "saved_rename_invalid", "saved_rename_failed", "saved_renamed")
        for (locale in listOf("values-ar", "values-fr")) for (name in names) {
            val translated = entries(locale).getValue(name)
            assertTrue("$locale/$name", translated.textContent.isNotBlank())
            assertEquals("$locale/$name", arguments(english.getValue(name).textContent), arguments(translated.textContent))
        }
    }

    @Test fun playlistCopyKeepsAllThreeLocalesAndArgumentsInParity() {
        val english = entries("values").filterKeys { it.startsWith("playlist_") }
        for (locale in listOf("values-ar", "values-fr")) {
            val translated = entries(locale).filterKeys { it.startsWith("playlist_") }
            assertEquals(english.keys, translated.keys)
            english.forEach { (name, original) ->
                assertTrue(translated.getValue(name).textContent.isNotBlank())
                // Plurals differ in item count per language (Arabic has six), so compare the arguments used.
                assertEquals(name, arguments(original.textContent).toSet(), arguments(translated.getValue(name).textContent).toSet())
            }
        }
    }

    @Test fun frenchResourcesCoverEveryTranslatableEntryAndKeepFormatArguments() {
        val english = entries("values")
        val french = entries("values-fr", translatedOnly = false)
        assertEquals(english.keys, french.keys)
        for ((name, original) in english) {
            val translated = french.getValue(name)
            assertEquals(name, original.tagName, translated.tagName)
            when (original.tagName) {
                "plurals" -> {
                    val quantities = items(translated).associateBy { it.getAttribute("quantity") }
                    assertTrue(name, quantities.keys.containsAll(listOf("one", "other")))
                    for ((quantity, item) in quantities) {
                        val source = items(original).firstOrNull { it.getAttribute("quantity") == quantity }
                            ?: items(original).single { it.getAttribute("quantity") == "other" }
                        assertTrue(name, item.textContent.isNotBlank())
                        assertEquals(name, arguments(source.textContent), arguments(item.textContent))
                    }
                }
                "string-array" -> {
                    assertEquals(name, items(original).size, items(translated).size)
                    items(original).zip(items(translated)).forEach { (source, item) ->
                        assertTrue(name, item.textContent.isNotBlank())
                        assertEquals(name, arguments(source.textContent), arguments(item.textContent))
                    }
                }
                else -> {
                    assertTrue(name, translated.textContent.isNotBlank())
                    assertEquals(name, arguments(original.textContent), arguments(translated.textContent))
                }
            }
        }
        assertFalse(french.containsKey("company_name"))
        assertFalse(french.containsKey("company_site"))
    }
}
