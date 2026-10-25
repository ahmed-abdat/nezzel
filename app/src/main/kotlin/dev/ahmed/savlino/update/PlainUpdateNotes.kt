package dev.ahmed.savlino.update

/** Older release notes may start with their version heading; the title already identifies Nezzel. */
internal fun plainUpdateNotes(notes: List<String>): List<String> = notes.dropWhile {
    val heading = it.trim().trimStart('#').trim()
    heading.isBlank() || Regex("(?i)(?:Nezzel\\s+)?v?\\d+\\.\\d+(?:\\.\\d+)?(?:[-+][a-z0-9.-]+)?").matches(heading)
}
