package dev.ahmed.savlino.engine

import kotlin.test.*

class YtdlpDebugDiagnosticsTest {
    @Test fun audioTaggingNeverLogsMetadataEvenWithoutCachedInfo() {
        val diagnostics = YtdlpDebugDiagnostics(enabled = true, args = listOf("--embed-metadata")) { _, _ ->
            fail("Tagging diagnostics may echo private titles")
        }
        diagnostics.accept("[MetadataParser] Changed title to: private title")
        diagnostics.finished(1, "ERROR: private title")
    }

    @Test fun debugLogsExitCodeAndOnlyLastTwentyRedactedLines() {
        val logs = mutableListOf<Pair<String, String>>()
        val diagnostics = YtdlpDebugDiagnostics(enabled = true) { tag, message -> logs += tag to message }
        diagnostics.accept("discarded first line")
        repeat(20) { diagnostics.accept("stderr $it https://host.example/$it?token=secret HTTP://other.example/private") }
        diagnostics.finished(1)
        assertEquals(21, logs.size)
        assertTrue(logs.all { it.first == "NezzelEngine" })
        assertEquals("yt-dlp exitCode=1", logs.first().second)
        assertEquals("stderr 0 <url> <url>", logs[1].second)
        assertEquals("stderr 19 <url> <url>", logs.last().second)
        assertFalse(logs.any { it.second.contains("secret") || it.second.contains("discarded") })
    }
    @Test fun releaseGateSuppressesAllLogsIncludingCompletionAndExceptionStderr() {
        val diagnostics = YtdlpDebugDiagnostics(enabled = false) { _, _ -> fail("Release must never log") }
        diagnostics.accept("ERROR: https://example.org/secret")
        diagnostics.finished(0, "ERROR: https://example.org/secret")
        diagnostics.finished(null, "ERROR: http://example.org/private")
    }
    @Test fun exceptionDiagnosticsUseRedactedStderrAndNeverInventExitCode() {
        val logs = mutableListOf<String>()
        val diagnostics = YtdlpDebugDiagnostics(enabled = true) { _, message -> logs += message }
        diagnostics.finished(null, "ERROR: unable to download https://example.org/secret\nHTTP Error 403: Forbidden\n")
        assertEquals(listOf("yt-dlp exitCode=unavailable (wrapper did not return a response)",
            "ERROR: unable to download <url>", "HTTP Error 403: Forbidden"), logs)
    }

    @Test fun everyUriSchemeAndBareHostOrPathIsRedactedBeforeLogging() {
        val cases = listOf(
            "rtmp://media.example/private" to "<url>",
            "ftp://media.example/private" to "<url>",
            "file:///storage/private.mp4" to "<url>",
            "content://media/external/123" to "<url>",
            "custom+engine.v1://media/private" to "<url>",
            "youtu.be/private-id" to "<path>",
            "media.example:443/private?token=secret" to "<path>",
            "media.example" to "<path>",
            "/storage/emulated/0/private.mp4" to "<path>",
            "relative/private.mp4" to "<path>",
            "C:\\Users\\private.mp4" to "<path>")
        for ((privateToken, redacted) in cases) {
            val logs = mutableListOf<String>()
            val diagnostics = YtdlpDebugDiagnostics(enabled = true) { _, message -> logs += message }
            diagnostics.accept("ERROR: cannot read $privateToken")
            diagnostics.finished(1, "ERROR: cannot read $privateToken")
            assertEquals(listOf("yt-dlp exitCode=1", "ERROR: cannot read $redacted",
                "ERROR: cannot read $redacted"), logs, privateToken)
        }
    }

    @Test fun destinationAndPostprocessorLinesNeverExposePathsOrTitlesWithSpaces() {
        val cases = listOf(
            "[download] Destination: /storage/My private title.mp4",
            "[download] Destination: My private title.mp4",
            "[Merger] Merging formats into \"/storage/My private title.mp4\"",
            "[ExtractAudio] Destination: My private title.m4a")
        for (line in cases) {
            val logs = mutableListOf<String>()
            val diagnostics = YtdlpDebugDiagnostics(enabled = true) { _, message -> logs += message }
            diagnostics.accept(line)
            diagnostics.finished(null, line)
            assertEquals(listOf("yt-dlp exitCode=unavailable (wrapper did not return a response)",
                "<private output>", "<private output>"), logs, line)
        }
    }

    @Test fun extractorIdsAreMaskedInCallbackAndExceptionOutput() {
        for (extractor in listOf("youtube", "youtube:tab", "SBS")) {
            val logs = mutableListOf<String>()
            val diagnostics = YtdlpDebugDiagnostics(enabled = true) { _, message -> logs += message }
            diagnostics.accept("[$extractor] private-id: Downloading webpage")
            diagnostics.finished(1, "ERROR: [$extractor] private-id: HTTP Error 403: Forbidden")
            assertEquals(listOf("yt-dlp exitCode=1", "[$extractor] <id>: Downloading webpage",
                "ERROR: [$extractor] <id>: HTTP Error 403: Forbidden"), logs)
        }
    }
}
