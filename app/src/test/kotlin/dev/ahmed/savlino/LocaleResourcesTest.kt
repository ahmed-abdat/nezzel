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
