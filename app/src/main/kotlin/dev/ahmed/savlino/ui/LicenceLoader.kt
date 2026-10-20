package dev.ahmed.savlino.ui

import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Asset IO is owned by the dialog's coroutine; recomposition never reads the APK. */
internal suspend fun loadLicenceText(open: () -> InputStream): String? = withContext(Dispatchers.IO) {
    try { open().bufferedReader().use { it.readText() } }
    catch (_: IOException) { null }
}

/** Splits a long notice into blocks of at most [lines] lines for lazy display; joining them with newlines restores it. */
internal fun licenceChunks(text: String, lines: Int = 80): List<String> = text.lines().chunked(lines) { it.joinToString("\n") }
