package dev.ahmed.savlino.update

import java.net.URI

data class ArchiveIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Pure, fail-closed rules shared by automatic surfaces and the installer. */
object UpdatePolicy {
    const val PACKAGE = "dev.ahmed.savlino"
    val hosts = setOf("github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    fun enabled(packageName: String) = packageName == PACKAGE
    fun allowed(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.host in hosts && uri.rawUserInfo == null &&
            uri.port in setOf(-1, 443) && uri.rawFragment == null
    } catch (_: Exception) { false }
    fun redirect(from: String, location: String): String? = try {
        URI(from).resolve(location).toString().takeIf(::allowed)
    } catch (_: Exception) { null }
    fun offer(remote: Long, current: Long, skipped: Long?) = remote > current && remote != skipped
    fun notify(packageName: String, automatic: Boolean, remote: Long, current: Long, skipped: Long?, notified: Long?) =
        enabled(packageName) && automatic && offer(remote, current, skipped) && (notified == null || remote > notified)
    fun verified(actualDigest: String, expectedDigest: String, archive: ArchiveIdentity,
                 installed: ArchiveIdentity, expectedCode: Long): Boolean =
        actualDigest.equals(expectedDigest, ignoreCase = true) && archive.packageName == installed.packageName &&
            installed.signers.isNotEmpty() && archive.signers == installed.signers &&
            archive.versionCode == expectedCode && archive.versionCode > installed.versionCode
}
