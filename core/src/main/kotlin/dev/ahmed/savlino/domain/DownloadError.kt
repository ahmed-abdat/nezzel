package dev.ahmed.savlino.domain

/** Stable resource-mappable codes; never expose raw engine stderr or URLs. */
enum class DownloadError {
    InvalidInput, Unsupported, LoginRequired, DrmProtected, EngineUnavailable, NoFormats,
    Network, Offline, FormatExpired, InsufficientSpace, MergeFailed, SaveFailed, FolderUnavailable,
    ExecutionRestricted, Interrupted, Failed, Cancelled,
    /** The post exists but holds no video (photos only); not a stale selection. */
    NoVideo,
    /** The video was removed, made unavailable or blocked where the phone is. */
    Unavailable,
    /** The link now opens a site's home page (dead short link), not a post. */
    NotAPost,
    /** The site refused the request (HTTP 403); neither lost connectivity nor an expired media selection. */
    SourceRefused;

    /** Default for adapters that already return concrete connectivity errors. */
    val cause: DownloadFailureCause get() = when (this) {
        Network, Offline -> DownloadFailureCause.TRANSPORT
        else -> DownloadFailureCause.OTHER
    }
}

/** Retry policy input, separate from resource-mappable display errors; contains no diagnostics. */
enum class DownloadFailureCause { OTHER, TRANSPORT, HTTP, STALL }
