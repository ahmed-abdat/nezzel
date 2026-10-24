package dev.ahmed.savlino.domain

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** URL-free transfer ownership and If-Range validator. Never stores request headers. */
object DirectResume {
    const val NAME = "native-resume"
    const val PART = "output.mp4.part"
    data class Record(val kind: String?, val validator: String?, val total: Long?, val syncedBytes: Long? = null) {
        val native get() = kind != null
    }
    fun read(directory: File): Record? = try {
        val file = File(directory, NAME)
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || file.length() > 2048) null
        else {
            val lines = file.readLines()
            when {
                lines == listOf("ytdlp-v1") -> Record(null, null, null)
                lines.size in 4..5 && ((lines.size == 4 && lines[0] == "native-v1") ||
                    (lines.size == 5 && lines[0] == "native-v2")) && lines[1] in setOf("ETag", "Last-Modified", "none") &&
                    safeValidator(lines[2]) && (lines[1] != "ETag" || !lines[2].startsWith("W/")) &&
                    (lines[3] == "?" || lines[3].toLongOrNull()?.let { it >= 0 } == true) &&
                    (lines.size == 4 || lines[4].toLongOrNull()?.let { offset ->
                        offset >= 0 && (lines[3].toLongOrNull()?.let { offset <= it } != false)
                    } == true) ->
                    Record(lines[1], lines[2].takeUnless { lines[1] == "none" }, lines[3].toLongOrNull(),
                        if (lines.size == 5) lines[4].toLongOrNull() else null)
                else -> null
            }
        }
    } catch (_: Exception) { null }
    fun safeValidator(value: String) = value.length in 1..1024 && !value.contains("://") &&
        value.none { it < ' ' || it == '\u007f' }
    fun write(directory: File, record: Record) {
        val target = File(directory, NAME)
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
            !Files.isSymbolicLink(target.toPath())) { "Invalid transfer record" }
        val text = if (!record.native) "ytdlp-v1\n" else {
            require(record.kind in setOf("ETag", "Last-Modified", "none") && safeValidator(record.validator ?: "-"))
            val offset = record.syncedBytes ?: 0
            require(offset >= 0 && (record.total?.let { offset <= it } != false))
            "native-v2\n${record.kind}\n${record.validator ?: "-"}\n${record.total ?: "?"}\n$offset\n"
        }
        val temporary = File(directory, "$NAME.tmp")
        require(!Files.exists(temporary.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Invalid transfer record" }
        try {
            FileOutputStream(temporary).use { it.write(text.toByteArray()); it.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
