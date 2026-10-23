package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ahmed.savlino.domain.DownloadCommand
import dev.ahmed.savlino.domain.Inspection
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Signed URLs: memory until confirmation, then a job-owned file outside backup. Never log failures. */
class YtdlpInfoCache(noBackupFilesDir: File, private val now: () -> Long = System::currentTimeMillis) {
    private val root = File(noBackupFilesDir, "ytdlp-info")
    private class Entry(val inspection: Inspection, val json: String, val createdAt: Long, val trimToChosen: Boolean)
    private val inspections = linkedMapOf<String, Entry>()

    @Synchronized internal fun remember(inspection: Inspection, json: String, trimToChosen: Boolean = false) {
        inspections.entries.removeAll { !fresh(it.value.createdAt) }
        // Only the recent options sheets can confirm. Bound abandoned inspections as well as their age.
        while (inspections.size >= 4) inspections.remove(inspections.keys.first())
        inspections[inspection.inspectionId] = Entry(inspection, json, now(), trimToChosen)
    }

    /** Consume once; a later explicit Retry must never resurrect this inspection. Best-effort IO. */
    @Synchronized internal fun prepare(command: DownloadCommand) {
        val entry = inspections.remove(command.inspectionId) ?: return
        if (!fresh(entry.createdAt) || !entry.inspection.source.matches(command.source) ||
            entry.inspection.engineVersion != command.engineVersion || command.format !in entry.inspection.formats) return
        try {
            val json = if (entry.trimToChosen) trim(entry.json, command) ?: return else entry.json
            val target = file(command.jobId, entry.trimToChosen) ?: return
            if (files(command.jobId).any { Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) }) return
            write(target, json, entry.createdAt)
        } catch (_: Exception) { /* Cache failure never prevents a confirmed URL download. */ }
    }

    /** Rebuilt NPE JSON is already trimmed; origin and content become durable in one atomic move. */
    @Synchronized internal fun store(jobId: String, json: String): File? = try {
        val target = file(jobId, npe = true)
        if (target == null || !remove(jobId)) null else write(target, json, now())
    } catch (_: Exception) { null }

    private fun write(target: File, json: String, createdAt: Long): File? {
        var temporary: File? = null
        return try {
            check(root.mkdirs() || root.isDirectory)
            temporary = File(root, "${UUID.randomUUID()}.tmp")
            temporary.writeText(json)
            check(temporary.setLastModified(createdAt))
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            target
        } catch (_: Exception) { null }
        finally { temporary?.delete() }
    }

    internal fun trim(json: String, command: DownloadCommand): String? {
        val mapper = ObjectMapper()
        val root = mapper.readTree(json) as? ObjectNode ?: return null
        val formats = root.get("formats")?.takeIf { it.isArray } ?: return null
        val chosen = setOfNotNull(command.format.sourceFormatId, command.format.audioFormatId)
        val selected = formats.filter { it.path("format_id").asText() in chosen }
        if (selected.map { it.path("format_id").asText() }.toSet() != chosen) return null
        root.set<JsonNode>("formats", mapper.valueToTree(selected))
        return mapper.writeValueAsString(root)
    }

    @Synchronized internal fun freshFile(jobId: String): File? = try {
        files(jobId).firstOrNull { file ->
            if (!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() > 0 && fresh(file.lastModified())) true
            else { file.delete(); false }
        }
    } catch (_: Exception) { null }

    /** False keeps explicit Retry from reusing a surviving private file. */
    @Synchronized fun remove(jobId: String): Boolean = try {
        val files = files(jobId)
        // Try both even if one removal fails. Never recursively remove an unexpected directory.
        files.isNotEmpty() && files.map { !Files.exists(it.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) || it.delete() }.all { it }
    } catch (_: Exception) { false }

    internal fun isNpe(file: File): Boolean = file.name.endsWith(".npe.json")

    @Synchronized fun sweep() {
        inspections.entries.removeAll { !fresh(it.value.createdAt) }
        try {
            root.listFiles()?.forEach { file ->
                if (!fresh(file.lastModified())) file.delete()
            }
        } catch (_: Exception) { /* Best effort; freshFile also enforces TTL before use. */ }
    }

    private fun fresh(createdAt: Long) = now() - createdAt in 0 until TTL_MS
    private fun files(jobId: String) = listOfNotNull(file(jobId, npe = true), file(jobId))
    private fun file(jobId: String, npe: Boolean = false): File? = if (jobId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}")))
        File(root, if (npe) "$jobId.npe.json" else "$jobId.json") else null // Opaque job IDs only.

    companion object { const val TTL_MS = 60L * 60 * 1000 }
}
