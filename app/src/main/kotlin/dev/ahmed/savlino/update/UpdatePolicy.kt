package dev.ahmed.savlino.update

import java.net.URI

data class ArchiveIdentity(val packageName: String, val versionCode: Long, val signers: Set<String>)

/** Pure, fail-closed rules shared by automatic surfaces and the installer. */
object UpdatePolicy {
    const val PACKAGE = "dev.ahmed.savlino"
    const val DAY = 86_400_000L
    const val CHECK_AGE = 18_000_000L
    const val REMINDER_AGE = 259_200_000L
    val hosts = setOf("github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    fun enabled(packageName: String) = packageName == PACKAGE
    private val sitePath = Regex("/update/[1-9][0-9]{0,9}")
    /** The site is allowed only for its version-count path, which may only redirect to the manifest asset. */
    fun allowed(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.port in setOf(-1, 443) && uri.rawFragment == null &&
            (uri.host in hosts || uri.host == Release.SITE && uri.rawQuery == null && sitePath.matches(uri.rawPath.orEmpty()))
    } catch (_: Exception) { false }
    fun redirect(from: String, location: String): String? = try {
        val next = URI(from).resolve(location).toString().takeIf(::allowed)
        when {
            next == null -> null
            URI(from).host == Release.SITE -> next.takeIf { it == Release.MANIFEST_ASSET }
            URI(next).host == Release.SITE -> null
            else -> next
        }
    } catch (_: Exception) { null }
    fun offer(remote: Long, current: Long, skipped: Long?) = remote > current && remote != skipped
    fun notify(packageName: String, automatic: Boolean, remote: Long, current: Long, skipped: Long?, notified: Long?,
               important: Boolean = false, notifiedAt: Long? = null, now: Long = System.currentTimeMillis()) =
        enabled(packageName) && automatic && offer(remote, current, skipped) &&
            (notified == null || remote > notified || important && (notifiedAt == null || now - notifiedAt >= DAY))
    fun verified(actualDigest: String, expectedDigest: String, archive: ArchiveIdentity,
                 installed: ArchiveIdentity, expectedCode: Long): Boolean =
        actualDigest.equals(expectedDigest, ignoreCase = true) && archive.packageName == installed.packageName &&
            installed.signers.isNotEmpty() && archive.signers == installed.signers &&
            archive.versionCode == expectedCode && archive.versionCode > installed.versionCode
}
