package dev.ahmed.savlino.storage

import dev.ahmed.savlino.domain.FilenameSanitizer
import dev.ahmed.savlino.domain.PartialFileNames
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

interface JobDirectories {
    fun create(jobId: String): JobDirectory.Job
    fun createOrAdopt(jobId: String): JobDirectory.Job = create(jobId)
}

/** Job IDs are opaque identifiers, never remote titles. New jobs always create exclusively. */
class JobDirectory(filesDir: File) : JobDirectories {
    private val root = File(filesDir.canonicalFile, "downloads")

    @Synchronized override fun create(jobId: String): Job {
        if (!jobId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) invalidPath()
        if (root.canonicalFile != root || Files.isSymbolicLink(root.toPath())) invalidPath()
        if (!root.isDirectory && !root.mkdir()) throw StorageException(StorageError.SaveFailed)
        val directory = File(root, jobId)
        // mkdir is exclusive: never clean up a preexisting job, even if its name is valid.
        if (!directory.mkdir()) invalidPath()
        return Job(root, directory)
    }

    /** The caller must own this ID in the journal and have stopped its previous writer. */
    @Synchronized override fun createOrAdopt(jobId: String): Job {
        if (!jobId.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,79}"))) invalidPath()
        if (root.canonicalFile != root || Files.isSymbolicLink(root.toPath())) invalidPath()
        val directory = File(root, jobId)
        if (!Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return create(jobId)
        if (Files.isSymbolicLink(directory.toPath()) || !directory.isDirectory) {
            Files.delete(directory.toPath())
            return create(jobId)
        }
        if (directory.canonicalFile != directory || directory.parentFile != root) invalidPath()
        val job = Job(root, directory)
        val files = directory.listFiles() ?: throw StorageException(StorageError.SaveFailed)
        for (file in files) {
            if (file.name == "result-path.txt" ||
                (file.name.matches(Regex("output\\.[^.]+")) && !PartialFileNames.matches(file.name))) {
                if (Files.isSymbolicLink(file.toPath()) || file.isFile) Files.delete(file.toPath())
            }
        }
        if (directory.listFiles()?.all {
                !Files.isSymbolicLink(it.toPath()) && it.isFile && PartialFileNames.matches(it.name)
            } != true) {
            job.cleanup()
            return create(jobId)
        }
        return job
    }

    class Job internal constructor(private val root: File, val directory: File) {
        private var cleaned = false

        @Synchronized fun file(title: String, extension: String): File {
            if (cleaned) invalidPath()
            validateDirectory()
            val name = try { FilenameSanitizer.sanitize(title, extension) } catch (failure: IllegalArgumentException) {
                throw StorageException(StorageError.InvalidPath, failure)
            }
            return File(directory, name).also { validateFile(it) }
        }

        @Synchronized internal fun requireActive() {
            if (cleaned) invalidPath()
            validateDirectory()
        }

        @Synchronized fun requireOutput(file: File): File {
            if (cleaned) invalidPath()
            validateDirectory()
            validateFile(file)
            if (!file.isFile || file.length() <= 0) throw StorageException(StorageError.SaveFailed)
            return file
        }

        /** No URI/path-taking delete API. This capability owns only the directory it created. */
        @Synchronized fun cleanup() {
            if (cleaned) return
            validateDirectory()
            if (!directory.exists()) { cleaned = true; return }
            // Default walkFileTree does NOT follow links, including links to directories.
            Files.walkFileTree(directory.toPath(), object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            cleaned = true
        }

        private fun validateDirectory() {
            if (root.canonicalFile != root || directory.canonicalFile != directory ||
                Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(directory.toPath()) ||
                directory.parentFile != root) invalidPath()
        }

        private fun validateFile(file: File) {
            if (file.absoluteFile.parentFile != directory || file.canonicalFile != file.absoluteFile ||
                Files.isSymbolicLink(file.toPath())) invalidPath()
        }
    }
}

private fun invalidPath(): Nothing = throw StorageException(StorageError.InvalidPath)
