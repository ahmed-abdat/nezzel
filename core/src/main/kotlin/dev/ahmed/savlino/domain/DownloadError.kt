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
    PhotoPost,
    /** The audio was downloaded but its conversion (MP3 encode, audio extraction) failed; not an audio+video merge. */
    ConvertFailed,
    /** Transfer finished, but preparing or publishing the file failed; Retry keeps the completed bytes. */
    PostTransferFailed;

    /** Default for adapters that already return concrete connectivity errors. */
    val cause: DownloadFailureCause get() = when (this) {
        Network, Offline -> DownloadFailureCause.TRANSPORT
        else -> DownloadFailureCause.OTHER
    }

    /*
     * The failure policy, in one place: every caller asks these instead of keeping its own set. Each `when` lists
     * every value, so a new error must pick its policy here (DownloadErrorPolicyTest pins the table).
     */

    /** Which step failed: getting the bytes, turning them into the chosen file, or saving it. */
    val stage: FailureStage get() = when (this) {
        MergeFailed, ConvertFailed -> FailureStage.CONVERT
        PostTransferFailed, SaveFailed, FolderUnavailable -> FailureStage.SAVE
        InvalidInput, Unsupported, LoginRequired, DrmProtected, EngineUnavailable, NoFormats, Network, Offline,
        FormatExpired, InsufficientSpace, ExecutionRestricted, Interrupted, Failed, Cancelled, NoVideo, Unavailable,
        NotAPost, SourceRefused, StoppedByBattery, ForegroundDenied, PhotoPost -> FailureStage.TRANSFER
    }

    /** The person may Retry the same confirmed download. A row's stored retryable flag never exceeds this. */
    val retryable: Boolean get() = when (this) {
        Network, Offline, InsufficientSpace, MergeFailed, ConvertFailed, PostTransferFailed, SaveFailed, FolderUnavailable,
        ExecutionRestricted, Interrupted, StoppedByBattery, ForegroundDenied, Failed, EngineUnavailable -> true
        InvalidInput, Unsupported, LoginRequired, DrmProtected, NoFormats, FormatExpired, Cancelled, NoVideo,
        Unavailable, NotAPost, SourceRefused, PhotoPost -> false
    }

    /**
     * The failed job keeps what it downloaded so Retry continues from it: a battery stop or a refused foreground
     * (not the download's fault, and they recur on a background rerun) resumes the partial; a failed conversion
     * converts the finished source again; a later local failure keeps the prepared output too.
     */
    val keepsSource: Boolean get() = when (this) {
        StoppedByBattery, ForegroundDenied, ConvertFailed, PostTransferFailed -> true
        InvalidInput, Unsupported, LoginRequired, DrmProtected, EngineUnavailable, NoFormats, Network, Offline,
        FormatExpired, InsufficientSpace, MergeFailed, SaveFailed, FolderUnavailable, ExecutionRestricted, Interrupted,
        Failed, Cancelled, NoVideo, Unavailable, NotAPost, SourceRefused, PhotoPost -> false
    }

    /**
     * Lost or broken connectivity: the transfer resumes on its own and spends the bounded automatic-resume budget
     * (an offline wait spends none), and a playlist counts it toward waiting for the network.
     */
    val spendsResumeBudget: Boolean get() = when (this) {
        Network, Offline -> true
        InvalidInput, Unsupported, LoginRequired, DrmProtected, EngineUnavailable, NoFormats, FormatExpired,
        InsufficientSpace, MergeFailed, ConvertFailed, PostTransferFailed, SaveFailed, FolderUnavailable, ExecutionRestricted,
        Interrupted, Failed, Cancelled, NoVideo, Unavailable, NotAPost, SourceRefused, StoppedByBattery,
        ForegroundDenied, PhotoPost -> false
    }

    /** A stop from outside the save (system, battery, network, space, folder grant): an unfinished save keeps it as its reason. */
    val stopsSave: Boolean get() = when (this) {
        Interrupted, StoppedByBattery, Network, InsufficientSpace, FolderUnavailable -> true
        InvalidInput, Unsupported, LoginRequired, DrmProtected, EngineUnavailable, NoFormats, Offline, FormatExpired,
        MergeFailed, ConvertFailed, PostTransferFailed, SaveFailed, ExecutionRestricted, Failed, Cancelled, NoVideo, Unavailable,
        NotAPost, SourceRefused, ForegroundDenied, PhotoPost -> false
    }

    /** The device or a later step ended it, not the link: reading the link again (re-extraction) would not help. */
    val blocksReextraction: Boolean get() = when (this) {
        Network, Offline, InsufficientSpace, MergeFailed, ConvertFailed, PostTransferFailed, SaveFailed, FolderUnavailable,
        ExecutionRestricted, Interrupted, Cancelled -> true
        InvalidInput, Unsupported, LoginRequired, DrmProtected, EngineUnavailable, NoFormats, FormatExpired, Failed,
        NoVideo, Unavailable, NotAPost, SourceRefused, StoppedByBattery, ForegroundDenied, PhotoPost -> false
    }

    companion object {
        /** A stored name an older or newer build wrote: unknown values read as [Failed] instead of crashing the reader. */
        fun fromName(name: String): DownloadError = entries.firstOrNull { it.name == name } ?: Failed
    }
}

enum class FailureStage { TRANSFER, CONVERT, SAVE }

/** Retry policy input, separate from resource-mappable display errors; contains no diagnostics. */
enum class DownloadFailureCause { OTHER, TRANSPORT, HTTP, STALL }
