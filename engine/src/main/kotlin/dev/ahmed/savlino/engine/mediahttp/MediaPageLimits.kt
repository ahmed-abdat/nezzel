package dev.ahmed.savlino.engine.mediahttp

/** Uncompressed page limits shared by the HTTP boundary and its untrusted parsers. */
internal object MediaPageLimits {
    const val FACEBOOK = 10 * 1024 * 1024
    const val LINKEDIN = 6 * 1024 * 1024
    const val INSTAGRAM = 3 * 1024 * 1024
    const val TELEGRAM = 1024 * 1024
    const val BLUESKY = 1024 * 1024
    const val TIKTOK = 3 * 1024 * 1024
    const val X = 512 * 1024
    const val REDDIT = 3 * 1024 * 1024
    const val PINTEREST = 3 * 1024 * 1024
    /** Logged-out Threads post pages are about 750–950 KB. */
    const val THREADS = 3 * 512 * 1024
}
