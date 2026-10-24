package dev.ahmed.savlino.statuses

import org.junit.Assert.*
import org.junit.Test

class StatusFoldersTest {
    private fun probes(tree: String, sdk: Int = 36) = StatusFolders.probes(tree, sdk).map { it.packageName to it.segments.joinToString("/") }

    @Test fun initialFolderIsMediaRootFromApi30AndVolumeRootOn29() {
        assertEquals("primary:Android/media", StatusFolders.initialDocumentId(30))
        assertEquals("primary:Android/media", StatusFolders.initialDocumentId(36))
        assertEquals("primary:", StatusFolders.initialDocumentId(29))
    }

    @Test fun patternsCoverBothAppsAccountsAndLegacyWithApiOrder() {
        val scoped = StatusFolders.patterns(36).map { it.packageName to it.segments.joinToString("/") }
        assertEquals(listOf(
            "com.whatsapp" to "Android/media/com.whatsapp/WhatsApp/accounts/*/Media/.Statuses",
            "com.whatsapp" to "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
            "com.whatsapp.w4b" to "Android/media/com.whatsapp.w4b/WhatsApp Business/accounts/*/Media/.Statuses",
            "com.whatsapp.w4b" to "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses",
            "com.whatsapp" to "WhatsApp/Media/.Statuses",
            "com.whatsapp.w4b" to "WhatsApp Business/Media/.Statuses"), scoped)
        assertEquals("WhatsApp/Media/.Statuses", StatusFolders.patterns(29).first().segments.joinToString("/"))
        assertEquals(StatusFolders.patterns(36).toSet(), StatusFolders.patterns(29).toSet())
    }

    @Test fun mediaRootProbesBothAppsBelowIt() {
        assertEquals(listOf(
            "com.whatsapp" to "com.whatsapp/WhatsApp/accounts/*/Media/.Statuses",
            "com.whatsapp" to "com.whatsapp/WhatsApp/Media/.Statuses",
            "com.whatsapp.w4b" to "com.whatsapp.w4b/WhatsApp Business/accounts/*/Media/.Statuses",
            "com.whatsapp.w4b" to "com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"), probes("primary:Android/media"))
        assertEquals(6, probes("primary:").size)
    }

    @Test fun narrowerTreesKeepOnlyTheirApp() {
        assertEquals(setOf("com.whatsapp"), probes("primary:Android/media/com.whatsapp").map { it.first }.toSet())
        assertEquals(listOf("com.whatsapp.w4b" to "accounts/*/Media/.Statuses", "com.whatsapp.w4b" to "Media/.Statuses"),
            probes("primary:Android/media/com.whatsapp.w4b/WhatsApp Business"))
        assertEquals(listOf("com.whatsapp" to "*/Media/.Statuses"), probes("primary:Android/media/com.whatsapp/WhatsApp/accounts"))
        assertEquals(listOf("com.whatsapp" to "Media/.Statuses"), probes("primary:Android/media/com.whatsapp/WhatsApp/accounts/1001"))
        assertEquals(listOf("com.whatsapp" to ""), probes("primary:Android/media/com.whatsapp/WhatsApp/accounts/1001/Media/.Statuses"))
        assertEquals(listOf("com.whatsapp" to ""), probes("primary:WhatsApp/Media/.Statuses", 29))
        assertEquals(listOf("com.whatsapp.w4b" to ".Statuses"), probes("primary:WhatsApp Business/Media", 29))
    }

    @Test fun appFolderDepthMarksTheAppsOwnFolder() {
        val media = StatusFolders.probes("primary:Android/media", 36)
        assertTrue(media.all { it.appFolderDepth == 2 })
        assertTrue(StatusFolders.probes("primary:Android/media/com.whatsapp/WhatsApp/accounts", 36).all { it.appFolderDepth == 0 })
        assertTrue(StatusFolders.probes("primary:", 29).filter { it.segments.size == 3 }.all { it.appFolderDepth == 1 })
    }

    @Test fun rejectsFoldersThatCannotHoldStatuses() {
        for (tree in listOf("primary:Download", "primary:Android/media/org.telegram.messenger", "primary:DCIM",
            "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images",
            "primary:Android/media/com.whatsapp/WhatsApp/accounts/1001/Media/.Statuses/x",
            "primary:Android/media/com.whatsapp/WhatsApp Business", "primary:WhatsApp/Media/.Links", "", "no-colon"))
            assertTrue(tree, probes(tree).isEmpty())
    }

    @Test fun accountComesFromTheFoundPath() {
        assertEquals("1001", StatusFolders.accountOf("Android/media/com.whatsapp/WhatsApp/accounts/1001/Media/.Statuses".split('/')))
        assertNull(StatusFolders.accountOf("Android/media/com.whatsapp/WhatsApp/Media/.Statuses".split('/')))
        assertEquals(listOf(null, "2", "10", "abc"), listOf("10", "abc", null, "2").sortedWith(StatusFolders.accountOrder))
    }
}
