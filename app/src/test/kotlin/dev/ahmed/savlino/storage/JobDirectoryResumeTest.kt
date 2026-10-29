package dev.ahmed.savlino.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class JobDirectoryResumeTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun adoptionDeletesControlAndFinalFilesBeforeKeepingAllPartialPatterns() {
        val jobs = JobDirectory(temp.root)
        val dir = jobs.create("owned").directory
        val partials = listOf("output.mp4.part", "output.mp4.part-Frag14.part", "output.mp4.ytdl",
            "output.f137.mp4", "output.f140.m4a.part", "output.temp", "output.temp.mp4")
        (partials + listOf("result-path.txt", "output.mp4")).forEach { dir.resolve(it).writeText(it) }
        assertEquals(dir, jobs.createOrAdopt("owned").directory)
        assertEquals(partials.toSet(), dir.list()!!.toSet())
        partials.forEach { assertEquals(it, dir.resolve(it).readText()) }
        try { jobs.create("owned"); fail("new jobs must stay exclusive") } catch (_: StorageException) { }
    }
    @Test fun adoptionKeepsOnlyTheNamedFinishedSourceAndStillDeletesTheConversionTarget() {
        val jobs = JobDirectory(temp.root)
        val dir = jobs.create("owned").directory
        listOf("output.m4a", "output.mp3", "result-path.txt", "output.f140.m4a.part").forEach { dir.resolve(it).writeText(it) }
        jobs.createOrAdopt("owned", keep = "output.m4a")
        assertEquals(setOf("output.m4a", "output.f140.m4a.part"), dir.list()!!.toSet())
        assertEquals("output.m4a", dir.resolve("output.m4a").readText())
        jobs.createOrAdopt("owned") // Without a compatible recipe the source goes too.
        assertEquals(setOf("output.f140.m4a.part"), dir.list()!!.toSet())
    }
    @Test fun compatibleConversionKeepsTheFinishedMp3InsteadOfItsSource() {
        val jobs = JobDirectory(temp.root)
        val dir = jobs.create("converted").directory
        listOf("output.m4a", "output.mp3", "result-path.txt", "output.mp4.part-Frag14.part",
            "output.temp.tags", "output.temp.jpg", "output.temp.enc", "output.temp.mp4")
            .forEach { dir.resolve(it).writeText(it) }
        dir.resolve("result-path.txt").writeText(dir.resolve("output.mp3").canonicalPath + "\n")
        jobs.createOrAdopt("converted", keep = "output.m4a", keepConverted = true)
        assertEquals(setOf("output.mp3", "result-path.txt", "output.mp4.part-Frag14.part"), dir.list()!!.toSet())
        assertEquals("output.mp3", dir.resolve("output.mp3").readText())
        jobs.createOrAdopt("converted") // A different recipe must discard the old result.
        assertEquals(setOf("output.mp4.part-Frag14.part"), dir.list()!!.toSet())
    }
    @Test fun unconfirmedMp3IsDeletedAndTheSourceIsKeptForEncoding() {
        for (manifest in listOf(null, "output.m4a", "output.mp3\noutput.m4a")) {
            val jobs = JobDirectory(temp.newFolder())
            val dir = jobs.create("unconfirmed").directory
            dir.resolve("output.m4a").writeText("source")
            dir.resolve("output.mp3").writeText("unconfirmed")
            if (manifest != null) dir.resolve("result-path.txt").writeText(
                manifest.lines().joinToString("\n") { dir.resolve(it).canonicalPath })
            jobs.createOrAdopt("unconfirmed", keep = "output.m4a", keepConverted = true)
            assertEquals(setOf("output.m4a"), dir.list()!!.toSet())
            assertEquals("source", dir.resolve("output.m4a").readText())
        }
    }
    @Test fun emptyOrSymlinkMp3IsNeverAdoptedAsFinished() {
        for (symlink in listOf(false, true)) {
            val jobs = JobDirectory(temp.newFolder())
            val dir = jobs.create("invalid").directory
            dir.resolve("output.m4a").writeText("source")
            val outside = temp.newFile().apply { writeText("private") }
            if (symlink) Files.createSymbolicLink(dir.resolve("output.mp3").toPath(), outside.toPath())
            else dir.resolve("output.mp3").writeText("")
            jobs.createOrAdopt("invalid", keep = "output.m4a", keepConverted = true)
            assertEquals("source", dir.resolve("output.m4a").readText())
            assertFalse(dir.resolve("output.mp3").exists())
            assertEquals("private", outside.readText())
        }
    }
    @Test fun nativeSidecarAdoptsOnlyItsOwnPartialAndRejectsStrayOrMalformedRecords() {
        for (record in listOf("native-v1\nETag\n\"a\"\n6\n", "ytdlp-v1\n", "broken", "native-v1\nETag\nhttps://example.com/private\n6\n")) {
            val jobs = JobDirectory(temp.newFolder())
            val dir = jobs.create("owned").directory
            dir.resolve("output.mp4.part").writeText("abc")
            dir.resolve("native-resume").writeText(record)
            val adopted = jobs.createOrAdopt("owned").directory
            if (record == "broken" || record.contains("https://")) assertTrue(adopted.list()!!.isEmpty())
            else {
                assertEquals("abc", adopted.resolve("output.mp4.part").readText())
                assertEquals(record, adopted.resolve("native-resume").readText())
            }
        }
        val jobs = JobDirectory(temp.newFolder()); val dir = jobs.create("stray").directory
        dir.resolve("native-resume").writeText("native-v1\nETag\n\"a\"\n6\n")
        assertTrue(jobs.createOrAdopt("stray").directory.list()!!.isEmpty())
    }
    @Test fun foreignNameOrNestedDirectoryWipesAllIncludingValidPartial() {
        for (nested in listOf(false, true)) {
            val jobs = JobDirectory(temp.newFolder())
            val dir = jobs.create("owned").directory
            dir.resolve("output.mp4.part").writeText("bytes")
            if (nested) dir.resolve("output.temp").mkdir() else dir.resolve("foreign.txt").writeText("unknown")
            assertTrue(jobs.createOrAdopt("owned").directory.list()!!.isEmpty())
        }
    }
    @Test fun symlinkJobIsReplacedWithoutFollowingOrDeletingItsTarget() {
        val jobs = JobDirectory(temp.newFolder())
        val dir = jobs.create("owned").directory
        dir.delete()
        val outside = temp.newFolder()
        outside.resolve("precious").writeText("keep")
        Files.createSymbolicLink(dir.toPath(), outside.toPath())
        val adopted = jobs.createOrAdopt("owned")
        assertFalse(Files.isSymbolicLink(adopted.directory.toPath()))
        assertTrue(adopted.directory.list()!!.isEmpty())
        assertEquals("keep", outside.resolve("precious").readText())
    }
}
