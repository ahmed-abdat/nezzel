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
    SourceRefused,
    /** Android stopped the transfer for battery/background limits (API 31+ stop reason only, never guessed). */
    StoppedByBattery,
    /** Android refused the foreground service a background rerun needs (dataSync after its timeout); the partial is kept. */
    ForegroundDenied,
    /** A link batch met a photos-only post; photos are saved from the single-link sheet. */
    PhotoPost;

    /** Default for adapters that already return concrete connectivity errors. */
    val cause: DownloadFailureCause get() = when (this) {
        Network, Offline -> DownloadFailureCause.TRANSPORT
        else -> DownloadFailureCause.OTHER
    }
}

/** Retry policy input, separate from resource-mappable display errors; contains no diagnostics. */
enum class DownloadFailureCause { OTHER, TRANSPORT, HTTP, STALL }
