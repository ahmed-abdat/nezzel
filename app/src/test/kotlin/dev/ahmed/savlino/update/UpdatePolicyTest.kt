package dev.ahmed.savlino.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    private val installed = ArchiveIdentity("dev.ahmed.savlino", 10, setOf("signer"))
    @Test fun onlyHigherCodeOffersAndSkipSuppressesAutomaticSurfaces() {
        assertTrue(UpdatePolicy.offer(11, 10, null))
        assertFalse(UpdatePolicy.offer(10, 10, null))
        assertFalse(UpdatePolicy.offer(9, 10, null))
        assertFalse(UpdatePolicy.offer(11, 10, 11))
        assertTrue(UpdatePolicy.offer(12, 10, 11))
    }
    @Test fun rejectsForeignRedirectsAndAmbiguousAuthorities() {
        UpdatePolicy.hosts.forEach { assertTrue(UpdatePolicy.allowed("https://$it/asset")) }
        listOf("http://github.com/a", "https://github.com.evil/a", "https://github.com@foreign.invalid/a",
            "https://github.com:444/a", "https://user@github.com/a", "file:///asset", "https://foreign.invalid/a")
            .forEach { assertFalse(UpdatePolicy.allowed(it)) }
        assertFalse(UpdatePolicy.redirect("https://github.com/a", "//foreign.invalid/a") != null)
        assertEquals("https://github.com/b", UpdatePolicy.redirect("https://github.com/a", "/b"))
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
    @Test fun notificationRequiresOptInUnskippedUnnotifiedVersionAndRealPackage() {
        assertTrue(UpdatePolicy.notify("dev.ahmed.savlino", true, 11, 10, null, 10))
        assertFalse(UpdatePolicy.notify("dev.ahmed.savlino.acceptance", true, 11, 10, null, 10))
        assertFalse(UpdatePolicy.notify("dev.ahmed.savlino", false, 11, 10, null, 10))
        assertFalse(UpdatePolicy.notify("dev.ahmed.savlino", true, 11, 10, 11, 10))
        assertFalse(UpdatePolicy.notify("dev.ahmed.savlino", true, 11, 10, null, 11))
    }
}
