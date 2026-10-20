package dev.ahmed.savlino.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

internal object UpdateVerifier {
    private fun identity(info: PackageInfo): ArchiveIdentity = ArchiveIdentity(info.packageName, info.longVersionCode,
        info.signingInfo?.apkContentsSigners.orEmpty().map { sha256(it.toByteArray()) }.toSet())
    fun verify(context: Context, file: File, release: Release): Boolean = try {
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val installed = manager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        @Suppress("DEPRECATION")
        val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        file.length() == release.size && archive != null && UpdatePolicy.verified(hex(digest.digest()), release.digest,
            identity(archive), identity(installed), release.code)
    } catch (_: Exception) { false }
    private fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
