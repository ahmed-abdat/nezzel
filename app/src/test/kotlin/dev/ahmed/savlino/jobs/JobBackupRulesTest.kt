package dev.ahmed.savlino.jobs

import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
// SDK coverage: AndroidManifest.xml and res/xml/{backup_rules,data_extraction_rules}.xml are read explicitly; no API-selected rule path.
@Config(sdk = [29])
class JobBackupRulesTest {
    @Test fun cloudBackupIsDisabled() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals(0, app.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test fun manifestBindsBothRuleFilesWithBackupOff() {
        // The rules only protect the journal while the manifest still points at them.
        val app = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(java.io.File("src/main/AndroidManifest.xml").takeIf { it.isFile } ?: java.io.File("app/src/main/AndroidManifest.xml"))
            .getElementsByTagName("application").item(0) as org.w3c.dom.Element
        val android = "http://schemas.android.com/apk/res/android"
        assertEquals("false", app.getAttributeNS(android, "allowBackup"))
        assertEquals("@xml/backup_rules", app.getAttributeNS(android, "fullBackupContent"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(android, "dataExtractionRules"))
    }

    @Test fun onlyPortableCosmeticsAreEligibleForEveryTransport() {
        for ((name, prefixes) in listOf("backup_rules" to listOf(""),
            "data_extraction_rules" to listOf("cloud-backup/", "device-transfer/"))) {
            val included = rules(name, "include")
            for (prefix in prefixes) {
                // An include allowlist excludes every other preference, journal and staging file.
                assertEquals(setOf(prefix + "sharedpref:nezzel-portable.xml"), included.filter { it.startsWith(prefix) }.toSet())
            }
            // Excludes outside the included paths are invalid FullBackupContent rules.
            assertTrue("$name must use its include-only allowlist", rules(name, "exclude").isEmpty())
        }
    }

    private fun rules(name: String, rule: String): Set<String> {
        val app = RuntimeEnvironment.getApplication()
        val id = app.resources.getIdentifier(name, "xml", app.packageName)
        assertTrue("missing backup resource: $name", id != 0)
        return app.resources.getXml(id).use { parser ->
            val result = mutableSetOf<String>()
            var transport = ""
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    if (parser.name in listOf("cloud-backup", "device-transfer")) transport = parser.name + "/"
                    if (parser.name == rule) result += transport + parser.getAttributeValue(null, "domain") +
                        ":" + parser.getAttributeValue(null, "path")
                }
                if (parser.eventType == XmlPullParser.END_TAG &&
                    parser.name in listOf("cloud-backup", "device-transfer")) transport = ""
            }
            result
        }
    }
}
