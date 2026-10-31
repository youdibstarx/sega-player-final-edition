package com.segaiptv.player

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class TranslationsTest {
    private fun strings(directory: String): Map<String, String> {
        val file = File("src/main/res/$directory/strings.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }
    @Test fun supportedLanguagesHaveCompleteKeysAndCompatibleFormatArguments() {
        val base = strings("values")
        val arguments = Regex("""%\d+\$[ds]""")
        for (locale in listOf("ar", "fr", "es")) {
            val translated = strings("values-$locale")
            assertEquals("Missing translations in $locale", base.keys, translated.keys)
            for ((key, value) in base) {
                assertTrue("$locale/$key is empty", translated.getValue(key).isNotBlank())
                assertEquals("$locale/$key has incompatible placeholders",
                    arguments.findAll(value).map { it.value }.toList().sorted(),
                    arguments.findAll(translated.getValue(key)).map { it.value }.toList().sorted())
            }
        }
    }
}
