package dev.ahmed.savlino.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val installed = ArchiveIdentity("dev.ahmed.savlino", 10, setOf("signer"))
    @Test fun onlyHigherCodeOffers() {
        assertTrue(UpdatePolicy.offer(11, 10))
        assertFalse(UpdatePolicy.offer(10, 10))
        assertFalse(UpdatePolicy.offer(9, 10))
    }
    @Test fun rejectsForeignRedirectsAndAmbiguousAuthorities() {
        UpdatePolicy.hosts.forEach { assertTrue(UpdatePolicy.allowed("https://$it/asset")) }
        listOf("http://github.com/a", "https://github.com.evil/a", "https://github.com@foreign.invalid/a",
            "https://github.com:444/a", "https://user@github.com/a", "file:///asset", "https://foreign.invalid/a")
            .forEach { assertFalse(UpdatePolicy.allowed(it)) }
        assertFalse(UpdatePolicy.redirect("https://github.com/a", "//foreign.invalid/a") != null)
        assertEquals("https://github.com/b", UpdatePolicy.redirect("https://github.com/a", "/b"))
    }
    @Test fun siteIsAllowedOnlyForTheVersionPathAndOnlyRedirectsToTheManifestAsset() {
        assertEquals("https://nezzel.hululsoft.mr/update/412", Release.manifest(412))
        assertTrue(UpdatePolicy.allowed(Release.manifest(412)))
        listOf("https://nezzel.hululsoft.mr/", "https://nezzel.hululsoft.mr/update/", "https://nezzel.hululsoft.mr/update/0",
            "https://nezzel.hululsoft.mr/update/12?id=1", "https://nezzel.hululsoft.mr/update/12/x", "https://nezzel.hululsoft.mr/api/report",
            "http://nezzel.hululsoft.mr/update/12", "https://nezzel.hululsoft.mr:444/update/12", "https://nezzel.hululsoft.mr.evil/update/12")
            .forEach { assertFalse(it, UpdatePolicy.allowed(it)) }
        assertEquals(Release.MANIFEST_ASSET, UpdatePolicy.redirect(Release.manifest(412), Release.MANIFEST_ASSET))
        assertNull(UpdatePolicy.redirect(Release.manifest(412), "https://github.com/ahmed-abdat/nezzel/releases/download/v1.0.0/app.apk"))
        assertNull(UpdatePolicy.redirect(Release.manifest(412), "https://release-assets.githubusercontent.com/asset"))
        assertNull(UpdatePolicy.redirect(Release.manifest(412), "/update/413"))
        assertNull(UpdatePolicy.redirect("https://github.com/a", Release.manifest(412)))
        assertEquals("https://release-assets.githubusercontent.com/a",
            UpdatePolicy.redirect(Release.MANIFEST_ASSET, "https://release-assets.githubusercontent.com/a"))
    }
    @Test fun everyArchiveCheckIsRequired() {
        val archive = installed.copy(versionCode = 11)
        assertTrue(UpdatePolicy.verified("abc", "abc", archive, installed, 11))
        assertFalse(UpdatePolicy.verified("def", "abc", archive, installed, 11))
        assertFalse(UpdatePolicy.verified("abc", "abc", archive.copy(packageName = "foreign"), installed, 11))
        assertFalse(UpdatePolicy.verified("abc", "abc", archive.copy(signers = setOf("other")), installed, 11))
        assertFalse(UpdatePolicy.verified("abc", "abc", archive.copy(signers = emptySet()), installed, 11))
        assertFalse(UpdatePolicy.verified("abc", "abc", archive, installed, 12))
        assertFalse(UpdatePolicy.verified("abc", "abc", installed, installed, 10))
    }
    @Test fun notificationRequiresOptInUnnotifiedVersionAndRealPackage() {
        assertTrue(UpdatePolicy.notify("dev.ahmed.savlino", true, 11, 10, 10))
        assertFalse(UpdatePolicy.notify("dev.ahmed.savlino.acceptance", true, 11, 10, 10))
        assertFalse(UpdatePolicy.notify("dev.ahmed.savlino", false, 11, 10, 10))
        assertFalse(UpdatePolicy.notify("dev.ahmed.savlino", true, 11, 10, 11))
    }

    @Test fun onlyVerifiedAutomaticIdleBackgroundUpdatesOnAndroidTwelveOrLater() {
        assertTrue(canSilentlyUpdate(31, true, false, false, true))
        assertTrue(canSilentlyUpdate(35, true, false, false, true))
        assertFalse(canSilentlyUpdate(30, true, false, false, true))
        assertFalse(canSilentlyUpdate(35, false, false, false, true))
        assertFalse(canSilentlyUpdate(35, true, true, false, true))
        assertFalse(canSilentlyUpdate(35, true, false, true, true))
        assertFalse(canSilentlyUpdate(35, true, false, false, false))
    }
}
