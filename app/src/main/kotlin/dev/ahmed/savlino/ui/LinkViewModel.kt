package dev.ahmed.savlino.ui

import android.graphics.Bitmap
import dev.ahmed.savlino.preview.CachedPreview
import dev.ahmed.savlino.preview.LinkPreviews
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.EngineAdapter
import dev.ahmed.savlino.report.Report
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.usage.UsageKeys
import dev.ahmed.savlino.engine.InspectResult
import dev.ahmed.savlino.engine.InspectionPhase
import dev.ahmed.savlino.engine.InspectionTiming
import dev.ahmed.savlino.engine.mediahttp.EarlyPreview
import dev.ahmed.savlino.engine.mediahttp.ShortLinkOutcome
import dev.ahmed.savlino.engine.mediahttp.ShortLinks
import dev.ahmed.savlino.engine.mediahttp.YouTubeOEmbed
import dev.ahmed.savlino.engine.tiktok.TikTokPostIdentity
import dev.ahmed.savlino.engine.MediaSourceRoute
import dev.ahmed.savlino.engine.UnavailableEngine
import dev.ahmed.savlino.engine.YtdlpInfoCache
import dev.ahmed.savlino.engine.facebook.FacebookLinkKind
import dev.ahmed.savlino.engine.facebook.FacebookPostIdentity
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.engine.instagram.InstagramPostIdentity
import dev.ahmed.savlino.engine.pinterest.PinterestPostIdentity
import dev.ahmed.savlino.engine.linkedin.LinkedInPostIdentity
import dev.ahmed.savlino.engine.x.XPostIdentity
import dev.ahmed.savlino.entry.ShareInput
import dev.ahmed.savlino.jobs.DownloadCoordinator
import dev.ahmed.savlino.jobs.EngineMaintenanceState
import dev.ahmed.savlino.jobs.EngineUpdateStatus
import dev.ahmed.savlino.media.ImageInspect
import dev.ahmed.savlino.media.ImageRoute
import dev.ahmed.savlino.media.ImageSaveState
import dev.ahmed.savlino.media.ImageSaver
import dev.ahmed.savlino.storage.StorageError
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface OptionsState {
    data object Hidden : OptionsState
    data class Checking(val source: LinkRevision, val operationId: String, val image: Boolean = false,
        val preparing: Boolean = false, val earlyPreview: EarlyPreview? = null) : OptionsState
    /** [resolvedFrom]: the user's own link when [inspection] is of the Facebook post's `watch/?v=` link. */
    data class Ready(val inspection: Inspection, val resolvedFrom: LinkRevision? = null) : OptionsState
    data class Error(val error: DownloadError) : OptionsState
    /**
     * Returned still images. [preview] and [previews] are app-owned copies; engine rasters are closed.
     * [directImage]: the link is the image itself (no post page), which picks the error copy.
     */
    class ImageReady(val inspection: MediaInspection, val rendition: MediaRendition.Image, val preview: ImageBitmap?,
        val discovery: ImageDiscovery, val availability: ImageSaveAvailability, val directImage: Boolean = false,
        val previews: Map<MediaRenditionRef, ImageBitmap> = emptyMap(), val videoSource: LinkRevision? = null,
        /** The intake link when [inspection] read its resolved destination (a short link); staleness binds to it. */
        val origin: LinkRevision? = null) : OptionsState {
        val photos = inspection.items.flatMap { it.renditions.filterIsInstance<MediaRendition.Image>() }
        var expiredPhotos by mutableStateOf<Set<MediaRenditionRef>>(emptySet())
            internal set
        val isCarousel get() = photos.size > 1 || discovery.cappedCount > 0 || discovery.unavailableCount > 0 ||
            (discovery.photoLimit != 40 && discovery.notPreviewedCount > 0)
        val image get() = rendition.image
        val selection get() = inspection.selection(rendition.ref)
        override fun toString() = "ImageReady(redacted)"
    }
    data class ImageError(val failure: ImageFailure, val statusCode: Int? = null, val storage: StorageError? = null,
        val directImage: Boolean = false, val phase: ImagePhase = ImagePhase.IMAGE) : OptionsState
}

/** Copy the engine raster into app-owned pixels; the engine recycles its preview when the reference retires. */
internal fun copyPreview(preview: OwnedImagePreview): ImageBitmap? = (preview as? AndroidImagePreview)?.bitmap
    ?.takeUnless { it.isRecycled }?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)?.asImageBitmap()

private const val IMAGE_TITLE_MAX = 80
/** A sheet older than this reads its link again on Download: the engine's info JSON for it lasts [YtdlpInfoCache.TTL_MS]. */
internal const val SHEET_FRESH_MS = YtdlpInfoCache.TTL_MS - 5 * 60 * 1000L
/** The only link a Facebook video post may hand to yt-dlp: no other host, path or query parameter. */
private val CANONICAL_WATCH = Regex("https://www\\.facebook\\.com/watch/\\?v=[0-9]{1,25}")
/** Readers that hand a proven video post back as the user's own link. */
private val HANDOFF_ROUTES = setOf(MediaSourceRoute.X, MediaSourceRoute.INSTAGRAM,
    MediaSourceRoute.TELEGRAM, MediaSourceRoute.BLUESKY, MediaSourceRoute.REDDIT, MediaSourceRoute.PINTEREST)
/** Slug as LinkedIn serves it (percent-escaped non-Latin names allowed; no escaped #, %, ., /, ?, \\ or controls). */
private val CANONICAL_LINKEDIN_POST = Regex("https://www\\.linkedin\\.com/(?:posts/" +
    "(?:[A-Za-z0-9_-]|%(?![01][0-9A-Fa-f]|2[35EeFf]|3[Ff]|5[Cc]|7[Ff])[0-9A-Fa-f]{2}){1,600}/" +
    "|feed/update/urn:li:activity:[0-9]{1,25}/)")

/** One preset row: [preset] labels it and is what a tap selects; [presets] are every preset that resolved to [format]. */
class PresetRowChoice(val preset: Preset, val format: Format, val presets: Set<Preset>)

/** Only reviewed/edited text is saved. Unaccepted clipboard contents live in memory only. */
class LinkViewModel(
    private val saved: SavedStateHandle,
    dismissed: Set<String> = emptySet(),
    private val engine: EngineAdapter = UnavailableEngine(),
    private val currentEngineVersion: (() -> String?)? = null,
    private val newOperationId: () -> String = { UUID.randomUUID().toString() },
    private val initialPreset: Preset? = null,
    private val rememberPreset: (Preset) -> Unit = {},
    val downloads: DownloadCoordinator? = null,
    private val coordinatorScope: kotlinx.coroutines.CoroutineScope? = null,
    private val rememberHandledClipboard: suspend (Set<String>) -> Boolean = { true },
    private val images: ImageInspect? = null,
    val imageSaver: ImageSaver? = null,
    private val previewImage: (OwnedImagePreview) -> ImageBitmap? = ::copyPreview,
    /** Keys handled-clipboard tags; the default session key matches only within this process. */
    linkKeyer: LinkKeyer = LinkKeyer.session,
    private val initializeClipboard: (suspend () -> SuggestionGate)? = null,
    private val previewDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    initialHeight: Int? = null,
    private val rememberHeight: (Int) -> Unit = {},
    /** The stored pick (preset, height) as it is now: another screen may have saved a newer one since. */
    private val storedPick: (() -> Pair<Preset?, Int?>)? = null,
    private val earlyPreview: suspend (ValidatedLink) -> EarlyPreview? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    /** A known shortener's destination from its redirect alone; Unresolved keeps the short link (today's path). */
    private val resolveShortLink: suspend (ValidatedLink) -> ShortLinkOutcome = { ShortLinkOutcome.Unresolved },
    /** Daily usage counts and automatic failure reports; both no-ops while Help improve Nezzel is off. */
    private val usage: UsageEvents = UsageEvents.None,
    private val previews: LinkPreviews? = null,
) : ViewModel() {
    var notificationPrompt: (() -> Unit)? = null
    private var pendingPermissionChoice: Pair<Inspection, String>? = null
    private var pendingPlaylistPermission: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    /** Paste/Share of 2..50 links: one sheet, one Download tap, one batch. Never opened from the automatic clipboard suggestion. */
    val multi = MultiLinkViewModel(viewModelScope,
        submit = { title, preset, chosen ->
            val permission = kotlinx.coroutines.CompletableDeferred<Unit>()
            pendingPlaylistPermission = permission
            try {
                notificationPrompt?.invoke() ?: finishNotificationRequest()
                permission.await()
            } finally { if (pendingPlaylistPermission === permission) pendingPlaylistPermission = null }
            val id = newOperationId()
            playlistSubmissionId = id
            (coordinatorScope ?: viewModelScope).async {
                downloads?.confirmLinks(id, title, preset, chosen) == true
            }.await().also { accepted -> if (accepted) markAllHandled(chosen) }
        },
        onAccepted = { acceptedJob = playlistSubmissionId; publishPendingLink() })
    val hasPendingNotificationChoice get() = pendingPermissionChoice != null || pendingPlaylistPermission != null
    fun requestDownload() {
        if (!downloadEnabled || pendingPermissionChoice != null) return
        pendingPermissionChoice = (inspection ?: return) to (selectedId ?: return)
        notificationPrompt?.invoke() ?: finishNotificationRequest()
    }
    /** Grant, denial and skipping all continue the same explicit, still-current choice. */
    fun finishNotificationRequest() {
        pendingPlaylistPermission?.let { pendingPlaylistPermission = null; it.complete(Unit); return }
        val pending = pendingPermissionChoice ?: return
        pendingPermissionChoice = null
        if (inspection === pending.first && selectedId == pending.second) confirmDownload()
    }
    private var maintenanceState by mutableStateOf(downloads?.maintenance?.state?.value)
    private var engineWaiters by mutableStateOf(engine.waitingOperations.value)
    val engineWaiting get() = (optionsState as? OptionsState.Checking)?.let { it.preparing || it.operationId in engineWaiters } == true
    /** One-time first extraction of the bundled engine; shown in place of the checking text. */
    var enginePreparing by mutableStateOf(engine.preparing.value); private set
    init {
        viewModelScope.launch { engine.waitingOperations.collect { engineWaiters = it } }
        viewModelScope.launch { engine.preparing.collect { enginePreparing = it } }
        downloads?.let { coordinator ->
            viewModelScope.launch { coordinator.maintenance.state.collect { state ->
                val before = maintenanceState
                maintenanceState = state
                retryAfterEngineUpdate(before, state)
            } }
        }
    }
    /**
     * A fresh install reads its first link with the bundled yt-dlp; automatic maintenance waits for that read, then
     * updates in seconds (phone: TikTok failed, then read fine). When an update lands while a read failure from the
     * older engine is on screen, read the same link once more. Metadata only: Download still needs its tap.
     */
    private fun retryAfterEngineUpdate(before: EngineMaintenanceState?, state: EngineMaintenanceState) {
        if (state.status != EngineUpdateStatus.SUCCEEDED) return
        if (before?.status == EngineUpdateStatus.SUCCEEDED && before.generation == state.generation) return
        val failed = optionsState as? OptionsState.Error ?: return
        if (failed.error !in setOf(DownloadError.Unsupported, DownloadError.Failed)) return
        // Only a read that yt-dlp itself failed, at an older generation than this update.
        if ((failedReadGeneration ?: return) >= state.generation) return
        startInspection() // Not checkLink: an automatic clipboard link stays memory-only, as before the failure.
    }
    /** Update now found only paused downloads; Settings asks before they lose their partial bytes. */
    var confirmPausedUpdate by mutableStateOf(false); private set
    fun updateEngine() {
        downloads?.let { coordinator ->
            (coordinatorScope ?: viewModelScope).launch {
                if (withContext(Dispatchers.IO) { coordinator.onlyPausedWaiting() }) confirmPausedUpdate = true
                else coordinator.maintenance.updateNow()
            }
        }
    }
    fun answerPausedUpdate(update: Boolean) {
        confirmPausedUpdate = false
        if (update) downloads?.let { coordinator ->
            (coordinatorScope ?: viewModelScope).launch { coordinator.maintenance.updateNow(includePaused = true) }
        }
    }
    val engineUpdating get() = maintenanceState?.status == EngineUpdateStatus.UPDATING
    val canUpdateAndRetry get() = downloads != null && maintenanceState?.everSucceeded != true &&
        (optionsState as? OptionsState.Error)?.error in setOf(DownloadError.Unsupported, DownloadError.Failed)
    fun updateEngineAndRetry(): Job? {
        val maintenance = downloads?.maintenance ?: return null
        if (!canUpdateAndRetry || engineUpdating) return null
        val source = currentSource() ?: return null
        val failed = optionsState
        return viewModelScope.launch {
            if (maintenance.updateNow() && currentSource()?.matches(source) == true && optionsState === failed) checkLink()
        }
    }
    private var inspectedEngineVersion by mutableStateOf<String?>(null)
    private var inspectedMaintenanceGeneration: Long? = null
    /** Maintenance generation of the engine read behind the Error on screen; null for any other error or state. */
    private var failedReadGeneration: Long? = null
    private fun engineVersion() = if (currentEngineVersion != null) currentEngineVersion.invoke()
        else maintenanceState?.version ?: inspectedEngineVersion.takeIf {
            inspectedMaintenanceGeneration == maintenanceState?.generation
        }
    private fun reportCode(state: OptionsState): String? = when (state) {
        is OptionsState.Error -> state.error.takeIf { it in ReportLink.downloadErrors }?.name
        is OptionsState.ImageError -> state.failure.takeIf { it in ReportLink.imageFailures }?.name
        else -> null
    }
    private var gate = SuggestionGate(dismissed, linkKeyer)
    private var clipboardReady by mutableStateOf(initializeClipboard == null)
    internal val clipboardDecisionReady get() = clipboardReady
    private val pendingClipboardDismissals = mutableListOf<ValidatedLink>()
    private val clipboardInit = kotlinx.coroutines.CompletableDeferred<Unit>().apply { if (initializeClipboard == null) complete(Unit) }
    var clipboardForeground by mutableStateOf(true)
    private val clipboardWrites = Mutex()
    private var automaticOffer: ValidatedLink? = null
    private var draftText by mutableStateOf(saved.get<String>("text") ?: "")
    var text: String
        get() = draftText
        private set(value) { draftText = value; publishPendingLink() }
    var invalid by mutableStateOf(saved.get<Boolean>("invalid") ?: false); private set
    var shareActive by mutableStateOf(saved.get<Boolean>("shareActive") ?: false); private set
    private var pendingShareState by mutableStateOf(saved.get<String>("pendingShare"))
    private var pendingShare: String?
        get() = pendingShareState
        set(value) { pendingShareState = value; publishPendingLink() }
    private var suggestionState by mutableStateOf<ValidatedLink?>(null)
    private var suggestion: ValidatedLink?
        get() = suggestionState
        set(value) { suggestionState = value; publishPendingLink() }
    private var automaticClipboardDraft = false
    /** The field holds a link the person typed by hand (not shared, pasted or suggested): a share asks before replacing it. */
    private var typedDraft by mutableStateOf(saved.get<Boolean>("typedDraft") ?: false)
    val hasPendingShare get() = pendingShare != null
    val pendingShareHost get() = pendingShare?.let { runCatching { URI(it).host }.getOrNull() }
    val hasSuggestion get() = suggestion != null
    val suggestionHost get() = suggestion?.let { URI(it.rawUrl).host }
    var invalidShare by mutableStateOf(false); private set
    private val multipleLinkChannel = Channel<Int>(Channel.CONFLATED)
    /** Intake notices carry no link data and wait for the foreground UI. */
    val multipleLinkEvents: Flow<Int> = multipleLinkChannel.receiveAsFlow()
    /** "Look for better quality": the saved picture tier these options answer (note + preselect); null otherwise. */
    var betterThan by mutableStateOf<Int?>(null); private set
    private var betterFrom: Format? = null
    /** The row Nezzel picked when the options opened; usage compares the confirmed row with it. */
    private var preselected: Format? = null
    private val betterNoneChannel = Channel<Unit>(Channel.CONFLATED)
    /** The re-read returned nothing above the saved tier; the options stay open. */
    val betterNoneEvents: Flow<Unit> = betterNoneChannel.receiveAsFlow()
    private val emptyPasteChannel = Channel<Unit>(Channel.CONFLATED)
    /** Paste found no text on the clipboard: a short notice, nothing else changes. */
    val emptyPasteEvents: Flow<Unit> = emptyPasteChannel.receiveAsFlow()
    // Never put the remainder (including pending replacement shares) in SavedStateHandle.
    private var linkQueue by mutableStateOf<LinkQueue?>(null)
    private var nextLinkReady by mutableStateOf(false)
    private val offersNextLink get() = nextLinkReady && optionsState == OptionsState.Hidden && !hasPendingShare && !hasSuggestion
    val nextLinkHost get() = linkQueue?.takeIf { offersNextLink }?.next?.rawUrl?.let { URI(it).host }
    val nextLinkNumber get() = linkQueue?.nextNumber ?: 0
    val linkQueueTotal get() = linkQueue?.total ?: 0
    fun skipRemainingLinks() {
        linkQueue = null; nextLinkReady = false
        publishPendingLink()
    }
    private fun replaceLinkQueue(candidates: List<ValidatedLink>) {
        linkQueue = LinkQueue.afterFirst(candidates)
        nextLinkReady = false
        if (candidates.size > 1) multipleLinkChannel.trySend(candidates.size)
        publishPendingLink()
    }
    fun openNextLink() {
        val queue = linkQueue?.takeIf { offersNextLink } ?: return
        val next = queue.next
        linkQueue = queue.advance()
        nextLinkReady = false
        // Same acceptance as Paste; only this explicit Open consumes the queued link.
        updateText(next.rawUrl)
        // Queued links can come from Share, Paste or the clipboard: no source title (updateText cleared it).
        shareActive = false; saved["shareActive"] = false
        if (automaticOptions) checkLink()
    }
    var copiedLink by mutableStateOf(false); private set
    val engineUnavailable get() = !engine.isAvailable
    private var options by mutableStateOf<OptionsState>(OptionsState.Hidden)
    var optionsState: OptionsState
        get() = options
        private set(value) {
            val fresh = value !== options
            options = value; publishPendingLink()
            // A copied link read automatically stays memory-only: it is reported only once the person checks it.
            if (fresh && !automaticClipboardDraft) reportCode(value)?.let {
                usage.failed(Report.OPTIONS, it, currentSource()?.link?.rawUrl, engineVersion())
            }
        }
    private fun hasPendingLink() = pendingShare != null || suggestion != null || linkQueue != null || multi.visible ||
        optionsState is OptionsState.Checking || optionsState is OptionsState.Ready || (optionsState == OptionsState.Hidden && LinkInput.parse(text) != null)
    private fun publishPendingLink() {
        val maintenance = downloads?.maintenance ?: return
        if (hasPendingLink()) maintenance.setPendingLink(true)
        else viewModelScope.launch {
            // Intake replaces suggestion/share/draft in several steps. Do not admit an updater between them.
            kotlinx.coroutines.yield()
            maintenance.setPendingLink(hasPendingLink())
        }
    }
    var photosUnavailable by mutableStateOf(false); private set
    var moreFormats by mutableStateOf(false); private set
    private var selectedId by mutableStateOf<String?>(null)
    var selectedPreset by mutableStateOf<Preset?>(null); private set
    private var submitted by mutableStateOf(false)
    private var command: DownloadCommand? = null
    private var submissionId: String? = null
    /** Header metadata only: never converted to Inspection, selectable formats or a download command. */
    internal var cachedPreview by mutableStateOf<CachedPreview?>(null); private set
    private var previewSession: Deferred<LinkPreviews.Session>? = null
    private var previewRequest: Any? = null
    private var cachedPreviewJob: Job? = null
    internal fun previewArtworkShown(bitmap: Bitmap) {
        val live = (optionsState as? OptionsState.Ready)?.inspection ?: return
        val session = previewSession ?: return
        viewModelScope.launch { session.await().write(live, bitmap) }
    }
    private var inspectionJob: Job? = null
    private var earlyPreviewJob: Job? = null
    private var inspectionTiming: InspectionTiming? = null
    private var optionsProvenance: UsageKeys.Path? = null
    private var inspectionStartedAt = 0L
    /** The link the timing above belongs to; only its registrable name is ever counted. */
    private var inspectionLink: String? = null
    /** The options sheet drew the preview image (timing journal only). */
    fun thumbnailShown() { inspectionTiming?.record(InspectionPhase.THUMBNAIL_SHOWN) }
    internal fun optionsShown() {
        val timing = inspectionTiming
        if (optionsState !is OptionsState.Ready && optionsState !is OptionsState.ImageReady || timing == null) return
        if (timing.has(InspectionPhase.OPTIONS_SHOWN)) return
        timing.record(InspectionPhase.OPTIONS_SHOWN)
        // Winner metadata is adopted only after staleness/ownership checks, never from a speculative loser.
        val path = optionsProvenance ?: when {
            timing.has(InspectionPhase.YTDLP_SPAWNED) -> UsageKeys.Path.YTDLP
            optionsState is OptionsState.ImageReady || timing.has(InspectionPhase.NATIVE_READER_DONE) -> UsageKeys.Path.OWN
            else -> UsageKeys.Path.NPE
        }
        usage.count(UsageKeys.FUNNEL_OPTIONS)
        val millis = now() - inspectionStartedAt
        usage.count(UsageKeys.options(path, millis))
        UsageKeys.optionsSite(inspectionLink, millis)?.let(usage::count)
    }
    private var operationId: String? = null
    /** Speculative yt-dlp read beside a handoff reader; owned here, so a photo verdict or dismissal ends it with its id. */
    private var race: Deferred<LegacyRead>? = null
    private var raceOperationId: String? = null
    private var raceWaiting = false
    private var imageOperation = false
    private var revision = 0L
    private val inspection get() = (optionsState as? OptionsState.Ready)?.inspection
    /** The link the field must still hold for this inspection to be confirmed. */
    private val confirmAgainst get() = (optionsState as? OptionsState.Ready)?.let { it.resolvedFrom ?: it.inspection.source }
    private val eligibleFormats get() = inspection?.let(::selectable).orEmpty()
    val formatOptions get() = inspection?.let(::displayFormats).orEmpty()
    /** One plain row per quality (owner 2026-10-18); a preset's rendition wins its quality. */
    val primaryFormats by derivedStateOf { FormatChoices.simple(formatOptions, preferred = presetOptions.mapTo(HashSet()) { it.second.id }) }
    /** Every other actual rendition, one tap away under "Other versions"; a row that would look exactly like one already shown is left out. */
    val otherFormats by derivedStateOf {
        val shown = primaryFormats.mapTo(HashSet()) { it.id }
        val looks = primaryFormats.mapTo(HashSet()) { FormatChoices.shownKey(it, roughSizeBytes(it)) }
        formatOptions.filter { it.id !in shown && looks.add(FormatChoices.shownKey(it, roughSizeBytes(it))) }
    }
    fun roughSizeBytes(format: Format): Long? = inspection?.roughSizeBytes?.get(format.id)
    /**
     * Main-list presets. MP3 stays under "All qualities" unless it is the only choice (TikTok photo slideshow).
     * Presets whose rows would read the same ("Very low · 240p" twice) collapse onto the earlier, better
     * preset's rendition (BEST, BALANCED, SMALL order), so a tap and the confirmed format always match the row.
     */
    val presetOptions: List<Pair<Preset, Format>> get() {
        val resolved = inspection?.presets.orEmpty()
            .mapNotNull { choice -> eligibleFormats.singleOrNull { it.id == choice.formatId }?.takeIf {
                choice.preset != Preset.BALANCED || (FormatChoices.quality(it) ?: Int.MAX_VALUE) <= 720
            }?.let { choice.preset to it } }
        val shown = resolved.filter { it.first != Preset.AUDIO_MP3 }.ifEmpty { resolved }.ifEmpty {
            // No engine presets and no video (audio-only result): one main audio row from the returned audio
            // formats, M4A before MP3, exactly as the full list picks them. Never for a link with video.
            if (formatOptions.any { it.hasVideo }) emptyList()
            else FormatChoices.simple(formatOptions).mapNotNull { row -> when {
                row.conversion != null -> Preset.AUDIO_MP3
                row.container == "m4a" -> Preset.AUDIO_M4A
                else -> null
            }?.let { it to row } }.distinctBy { it.first }.take(1)
        }
        val byLabel = HashMap<String, Format>()
        return shown.map { (preset, format) -> preset to byLabel.getOrPut(FormatChoices.labelKey(format)) { format } }
    }
    /** Presets resolving to the same format id share ONE row (first preset's label); [PresetRowChoice.presets] keeps them all. */
    val presetRows get() = presetOptions.groupBy { it.second.id }.values
        .map { group -> PresetRowChoice(group.first().first, group.first().second, group.mapTo(LinkedHashSet()) { it.first }) }
    val selectedFormat get() = eligibleFormats.singleOrNull { it.id == selectedId }
    private var playlistSubmissionId: String? = null
    val playlist = PlaylistViewModel(engine, viewModelScope,
        wasSaved = { item -> LinkInput.parse(item.sourceUrl)?.let { downloads?.savedBefore(it.normalizedUrl)?.isNotEmpty() } == true },
        submit = { title, preset, items ->
            // The same first-download permission choice; grant, denial and skipping all keep this one confirmation.
            val permission = kotlinx.coroutines.CompletableDeferred<Unit>()
            pendingPlaylistPermission = permission
            try {
                notificationPrompt?.invoke() ?: finishNotificationRequest()
                permission.await()
            } finally { if (pendingPlaylistPermission === permission) pendingPlaylistPermission = null }
            val id = newOperationId()
            playlistSubmissionId = id
            // Confirmation survives closing the Activity, as the single-video handoff does.
            (coordinatorScope ?: viewModelScope).async {
                downloads?.confirmBatch(id, title, preset, items) == true
            }.await()
        },
        onAccepted = { acceptedJob = playlistSubmissionId; dismissOptions(); clearField() })
    /** Handled-link marks for the clipboard after the confirm, like a single Download. */
    private fun markAllHandled(chosen: List<ValidatedLink>) {
        if (!clipboardReady) return
        chosen.forEach(gate::dismiss)
        val hashes = gate.dismissedHashes()
        viewModelScope.launch { persistClipboard(hashes) }
    }
    /** True when [text] opened the multi-link sheet; the single-link intake below is then skipped entirely. */
    private fun openMulti(text: String?): Boolean {
        val found = MultiLinks.from(text) ?: return false
        if (optionsState != OptionsState.Hidden) dismissOptions()
        dismissShare()
        skipRemainingLinks()
        suggestion = null
        multi.open(found)
        publishPendingLink()
        return true
    }
    fun dismissMulti() { multi.close(); publishPendingLink() }
    /** Video-in-list stays single-video until this explicit action. */
    fun showPlaylist() {
        val source = currentSource() ?: return
        if (YouTubeLinkKind.classify(source.link) in setOf(YouTubeLinkKind.Playlist, YouTubeLinkKind.VideoInList)) {
            invalidateOptions()
            optionsState = OptionsState.Checking(source, newOperationId())
            playlist.open(source)
        }
    }

    /** Earlier completed downloads of this link, by format id (looked up once per inspection). */
    private var savedFormats by mutableStateOf<Map<String, dev.ahmed.savlino.jobs.SavedBefore>>(emptyMap())
    /** The chosen format of this link was saved before (file still present): a notice, never a block. */
    val savedBefore get() = selectedFormat?.let { savedFormats[it.id] }
    /** Newest earlier save of the shared link (any format, file still present), looked up at intake before the engine answers. */
    var savedLink by mutableStateOf<dev.ahmed.savlino.jobs.SavedBefore?>(null); private set
    private var savedLinkJob: Job? = null
    private fun lookUpSavedLink(link: ValidatedLink) {
        val coordinator = downloads ?: return
        val revision = revision
        savedLinkJob = viewModelScope.launch {
            val newest = coordinator.savedBefore(link.normalizedUrl).values.maxByOrNull { it.savedAt }
            if (this@LinkViewModel.revision == revision) savedLink = newest
        }
    }
    /** The sender's words around a shared link; shown only until the real title arrives (never stored or logged). */
    var shareHint by mutableStateOf<String?>(null); private set
    // Restore the whole pick together: a cleared member must not revive an older initial preference.
    private val restoredPick = saved.contains("lastPreset") || saved.contains("lastHeight")
    private var lastUsedHeight by mutableStateOf(
        (if (restoredPick) saved.get<Int>("lastHeight") else initialHeight)?.takeIf { it > 0 })
    /** Audio picks never replace the remembered video pick (2026-10-19). */
    var lastUsedPreset by mutableStateOf((if (lastUsedHeight != null) null else if (restoredPick) {
        saved.get<String>("lastPreset")?.let { name -> Preset.entries.find { it.name == name } }
    } else initialPreset)?.takeUnless { it.audio }); private set
    /** The actual returned row the remembered pick resolves to; unrelated fallbacks have no badge. */
    val lastUsedFormat: Format? get() {
        val height = lastUsedHeight ?: return presetOptions.firstOrNull { it.first == lastUsedPreset && it.second.hasVideo }?.second
        return presetOptions.firstOrNull { it.second.hasVideo && FormatChoices.quality(it.second) == height }?.second
            // The canonical row per quality (same pick as the list), so a height tie never badges a minor codec.
            ?: primaryFormats.filter { it.hasVideo && FormatChoices.quality(it)?.let { quality -> quality <= height } == true }
                .maxByOrNull { FormatChoices.quality(it)!! }
    }
    /** The badge only helps tell rows apart: with one video row there is nothing to tell apart. */
    private var seenPick = storedPick?.invoke()
    val lastUsedBadge: Format? get() = lastUsedFormat?.takeIf { primaryFormats.count { it.hasVideo } > 1 }
    val confirmed get() = submitted
    /** The last job the coordinator accepted from this view model; the share sheet closes on it. */
    var acceptedJob by mutableStateOf<String?>(null); private set
    /** A stale sheet stays tappable: Download reads the link again first (see [confirmDownload]). An unknown engine version can't confirm anything. */
    val downloadEnabled get() = !submitted && !refreshing && !optionsChanged && !engineUpdating && engine.isAvailable && selectedFormat != null &&
        inspection != null && engineVersion() != null && confirmAgainst?.let { currentSource()?.matches(it) } == true
    /** Download was tapped on a stale sheet; the same link is being read again before anything is queued. */
    var refreshing by mutableStateOf(false); private set
    /** That fresh read no longer offered the same choice: the sheet shows the fresh options and Download waits for a pick. */
    var optionsChanged by mutableStateOf(false); private set
    /** When the sheet's options were read; the engine keeps their info JSON for [YtdlpInfoCache.TTL_MS]. */
    private var readAt: Long? = null
    var suggestionsEnabled by mutableStateOf(true)
    var automaticOptions by mutableStateOf(true)
    var editorFocused by mutableStateOf(false)
    val clipboardEligible get() = clipboardReady && clipboardForeground && suggestionsEnabled && !editorFocused && destination == "Home" &&
        !hasSuggestion && !hasPendingShare && linkQueue == null && !multi.visible && !invalidShare && !shareActive && text.isEmpty() &&
        optionsState == OptionsState.Hidden && !hasPendingNotificationChoice
    var destination by mutableStateOf(saved.get<String>("destination") ?: "Home")
        private set

    private var warmUpJob: Job? = null
    private var warmUpComplete = false
    /** Resume alone only warms local binaries; fresh eligible intake owns automatic inspection. */
    fun onForeground() {
        if (currentSource() != null || suggestion != null || pendingShare != null) warmUp()
    }
    private fun warmUp() {
        if (warmUpComplete || warmUpJob?.isActive == true || !engine.isAvailable) return
        warmUpJob = viewModelScope.launch {
            try {
                warmUpComplete = EngineTiming.measure("engine_init") { engine.warmUp() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Check reports typed availability/errors; no raw diagnostics. */ }
        }
    }

    fun navigate(destination: String) {
        if (destination != "Home") { dismissSuggestion(); dismissOptions() }
        if (destination == "Statuses" && this.destination != destination) usage.count(UsageKeys.STATUSES_OPENED)
        editorFocused = false
        this.destination = destination; saved["destination"] = destination
    }
    fun onCreateShare(input: ShareInput?) {
        if (saved.get<Boolean>("created") == true) return
        saved["created"] = true
        onNewShare(input)
    }
    fun onNewShare(input: ShareInput?) {
        if (input == null) return
        if (openMulti(input.text)) { navigate("Home"); usage.count(UsageKeys.SHARE); return }
        val candidates = LinkInput.candidates(input.text)
        val link = candidates.firstOrNull()
        dismissShare()
        replaceLinkQueue(candidates)
        if (hasOptionsFor(link)) return
        suggestion = null
        val wasEditing = editorFocused
        navigate("Home")
        invalidShare = link == null
        if (link == null) return
        usage.count(UsageKeys.SHARE)
        warmUp()
        // Only a link being typed is protected. A shared, pasted or suggested link (or the one just
        // downloaded, which is cleared anyway) is simply replaced: no "replace?" step on the daily path.
        if (wasEditing || (text.isNotEmpty() && typedDraft)) {
            pendingShare = link.rawUrl; saved["pendingShare"] = pendingShare
        } else {
            updateText(link.rawUrl)
            shareHint = input.hint
            lookUpSavedLink(link)
            shareActive = true; saved["shareActive"] = true
            if (automaticOptions) checkLink()
        }
    }
    fun clearShareMessage() { invalidShare = false }
    fun acceptShare() {
        val link = pendingShare ?: return
        pendingShare = null; saved.remove<String>("pendingShare")
        updateText(link)
        shareActive = true; saved["shareActive"] = true
        if (automaticOptions) checkLink()
    }
    fun dismissShare() {
        if (hasPendingShare) skipRemainingLinks()
        pendingShare = null; saved.remove<String>("pendingShare")
    }
    fun edit(value: String) {
        skipRemainingLinks()
        suggestion = null
        clearShareMessage()
        updateText(value.take(8192))
        setTyped(text.isNotEmpty())
        shareActive = false; saved["shareActive"] = false
    }
    private fun setTyped(value: Boolean) { typedDraft = value; saved["typedDraft"] = value }
    fun paste(value: String?) {
        if (value.isNullOrBlank()) { emptyPasteChannel.trySend(Unit); return }
        if (openMulti(value)) { usage.count(UsageKeys.PASTE); return }
        val candidates = LinkInput.candidates(value)
        val link = candidates.firstOrNull()
        dismissShare()
        if (hasOptionsFor(link)) { replaceLinkQueue(candidates); return }
        if (link != null) usage.count(UsageKeys.PASTE)
        suggestion = null
        edit(link?.rawUrl ?: value)
        replaceLinkQueue(candidates)
        setTyped(false)
        copiedLink = currentSource() != null
        if (automaticOptions && currentSource() != null) checkLink()
    }
    private fun hasOptionsFor(link: ValidatedLink?): Boolean = link != null &&
        (optionsState is OptionsState.Checking || optionsState is OptionsState.Ready || optionsState is OptionsState.ImageReady) &&
        currentSource()?.link?.normalizedUrl == link.normalizedUrl
    /**
     * A saved file's sheet: read its kept link again, explicitly (allowed with automatic options off, like View options).
     * Only selection and a note change; Download still needs its own tap.
     */
    fun lookForBetter(link: String, saved: Format) {
        val parsed = LinkInput.parse(link) ?: return
        dismissShare()
        suggestion = null
        edit(parsed.rawUrl)
        setTyped(false)
        betterFrom = saved
        betterThan = VideoQuality.of(saved.width, saved.height)
        checkLink()
    }
    private fun clearBetter() { betterFrom = null; betterThan = null }
    private fun updateText(value: String, persist: Boolean = true) {
        clearBetter()
        automaticClipboardDraft = false
        shareHint = null
        savedLinkJob?.cancel(); savedLinkJob = null; savedLink = null
        setTyped(false)
        revision++
        invalidateOptions()
        copiedLink = false
        text = value
        if (persist) saved["text"] = value else saved.remove<String>("text")
        invalid = false; saved["invalid"] = false
        if (LinkInput.parse(value) != null) warmUp()
    }
    fun checkLink() {
        // Retry after a failed local initialization tries it again now instead of after the backoff.
        if ((optionsState as? OptionsState.Error)?.error == DownloadError.EngineUnavailable) engine.retryInitialization()
        if (automaticClipboardDraft) {
            automaticClipboardDraft = false
            saved["text"] = text // Explicit Check/Retry promotes this in-memory link to a reviewed draft.
        }
        startInspection()
    }
    private fun startInspection() {
        refreshedTikTok = false
        invalidateOptions()
        val source = currentSource()
        invalid = source == null; saved["invalid"] = invalid
        if (source == null) return
        val kind = YouTubeLinkKind.classify(source.link)
        if (kind == YouTubeLinkKind.Playlist) {
            optionsState = OptionsState.Checking(source, newOperationId())
            playlist.open(source)
            return
        }
        if (kind == YouTubeLinkKind.PrivateList) {
            optionsState = OptionsState.Error(DownloadError.LoginRequired)
            return
        }
        if (source.link.host == Host.YOUTUBE && kind == YouTubeLinkKind.Other &&
            URI(source.link.normalizedUrl).path.trimEnd('/') == "/playlist") {
            optionsState = OptionsState.Error(DownloadError.Unsupported)
            return
        }
        optionsProvenance = null
        inspectionTiming = InspectionTiming().also { it.record(InspectionPhase.INTAKE_ACCEPTED) }
        inspectionStartedAt = now()
        inspectionLink = source.link.rawUrl
        usage.count(UsageKeys.FUNNEL_LINK)
        fun inspect() {
            if (ShortLinks.isShortLink(source.link)) startShortLink(source) else route(source, source)
        }
        val request = Any()
        previewRequest = request
        val session = previews?.let { cache ->
            viewModelScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) { cache.open(source.link.normalizedUrl) }
        }
        previewSession = session
        // Routing and live inspection start immediately; optional disk bootstrap must never delay them.
        inspect()
        if (session == null) return
        cachedPreviewJob = viewModelScope.launch {
            // Both reads and writes await this same session: its generation token exists before any write.
            val cached = session.await().read { if (currentEngineVersion != null) currentEngineVersion.invoke() else engine.installedVersion() }
            if (previewRequest === request && currentSource()?.matches(source) == true && optionsState is OptionsState.Checking)
                cachedPreview = cached
        }
    }
    /** Resolve a shortener's redirect first, so its destination takes its own fast route (YouTube, readers). */
    private fun startShortLink(source: LinkRevision) {
        val id = newOperationId()
        operationId = id
        optionsState = OptionsState.Checking(source, id)
        val timing = requireNotNull(inspectionTiming)
        inspectionJob = viewModelScope.launch(timing.context()) {
            val outcome = try { resolveShortLink(source.link) }
                catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { ShortLinkOutcome.Unresolved }
            if (operationId != id || currentSource()?.matches(source) != true) return@launch
            operationId = null
            timing.record(InspectionPhase.SHORT_LINK_DONE)
            when (outcome) {
                // A non-public destination is never handed to the engine, not even as the short link it follows.
                ShortLinkOutcome.Blocked -> optionsState = OptionsState.Error(DownloadError.Unsupported)
                ShortLinkOutcome.Unresolved -> route(source, source)
                is ShortLinkOutcome.Resolved -> route(LinkRevision(outcome.link, source.revision), source)
            }
        }
    }
    /** [input] is what gets read; [origin] is the intake link every staleness check binds to. */
    private fun route(input: LinkRevision, origin: LinkRevision) {
        val readerOrigin = origin.takeUnless { it.matches(input) }
        if (ImageRoute.isUnsupported(input.link)) {
            optionsState = if (FacebookPostIdentity.isStoryLink(input.link.rawUrl))
                OptionsState.ImageError(ImageFailure.FACEBOOK_STORY, phase = ImagePhase.PAGE)
                else OptionsState.Error(DownloadError.Unsupported)
            return
        }
        // Image routes run before (and without) the yt-dlp availability gate.
        val images = images ?: return startLegacyInspection(input, readerOrigin)
        when (val route = ImageRoute.of(input.link)) {
            MediaSourceRoute.LEGACY -> startLegacyInspection(input, readerOrigin)
            MediaSourceRoute.DIRECT_IMAGE, MediaSourceRoute.LINKEDIN, MediaSourceRoute.FACEBOOK, MediaSourceRoute.X,
            MediaSourceRoute.INSTAGRAM, MediaSourceRoute.TELEGRAM, MediaSourceRoute.BLUESKY, MediaSourceRoute.TIKTOK,
            MediaSourceRoute.REDDIT, MediaSourceRoute.PINTEREST ->
                startImageInspection(images, input, route, origin)
        }
    }
    private fun startImageInspection(images: ImageInspect, source: LinkRevision, route: MediaSourceRoute,
        origin: LinkRevision = source, legacyFallback: Inspection? = null,
        selectedPositions: Set<Int>? = null) {
        val id = newOperationId()
        operationId = id
        imageOperation = true
        optionsState = OptionsState.Checking(origin, id, image = true)
        // A video verdict or pre-verdict page failure hands off to yt-dlp; start that read now, beside the reader.
        if (route in HANDOFF_ROUTES) startRace(legacyReaderSource(source, route))
        // Not cancelled on invalidation: a late result must still arrive here so its preview is closed.
        viewModelScope.launch(requireNotNull(inspectionTiming).context()) {
            // A new inspection retires the previous reference, which would fail an in-flight Save as STALE.
            imageSaver?.awaitIdle()
            if (operationId != id) return@launch
            // Ownership is taken inside the call, before resumption: a cleared ViewModel still closes a late preview.
            val delivered = java.util.concurrent.atomic.AtomicReference<MediaInspectResult?>()
            val result = try {
                withContext(NonCancellable) {
                    delivered.set(images.inspect(source, id, route))
                }
                delivered.getAndSet(null) ?: MediaInspectResult.Failure(ImageFailure.UNAVAILABLE, ImagePhase.IMAGE)
            } catch (cancelled: CancellationException) {
                (delivered.getAndSet(null) as? MediaInspectResult.Success)?.close()
                throw cancelled
            } catch (_: Exception) { MediaInspectResult.Failure(ImageFailure.UNAVAILABLE, ImagePhase.IMAGE) }
            catch (_: LinkageError) { MediaInspectResult.Failure(ImageFailure.UNAVAILABLE, ImagePhase.IMAGE) }
            catch (_: StackOverflowError) { MediaInspectResult.Failure(ImageFailure.UNAVAILABLE, ImagePhase.IMAGE) }
            InspectionTiming.mark(InspectionPhase.NATIVE_READER_DONE)
            if (operationId != id || currentSource()?.matches(origin) != true) {
                (result as? MediaInspectResult.Success)?.close()
                return@launch
            }
            val handoff = route in HANDOFF_ROUTES && when (result) {
                is MediaInspectResult.Failure -> readerFallsBack(route, result, source)
                is MediaInspectResult.VideoRedirect -> result.source.matches(source)
                else -> false
            }
            if (!handoff) endRace()
            when (result) {
                is MediaInspectResult.Success -> {
                    val previews = try { withContext(previewDispatcher) {
                        val returned = result.previews.ifEmpty {
                            val ref = result.inspection.items.firstOrNull()?.renditions?.firstOrNull()?.ref
                            val preview = result.preview
                            if (ref == null || preview == null) emptyMap() else mapOf(ref to preview)
                        }
                        returned.mapNotNull { (ref, raster) ->
                            val copy = try { previewImage(raster) } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                            copy?.let { ref to it }
                        }.toMap()
                    } } finally { result.close() }
                    // Copying yields Main: a newer draft/inspection may now own the UI.
                    if (operationId != id || currentSource()?.matches(origin) != true) return@launch
                    selectedImageRefs = result.inspection.items.flatMap { it.renditions.filterIsInstance<MediaRendition.Image>() }
                        .map { it.ref }.toSet()
                    if (selectedPositions == null) soundSelected = false // A new post starts with its sound unticked.
                    if (selectedPositions != null) selectedImageRefs = result.inspection.items
                        .filter { it.position in selectedPositions }.flatMap { it.renditions }.map { it.ref }.toSet()
                    operationId = null; imageOperation = false
                    optionsProvenance = UsageKeys.Path.OWN
                    val rendition = result.inspection.items.firstOrNull()?.renditions?.firstOrNull() as? MediaRendition.Image
                    optionsState = when {
                        !result.inspection.source.matches(source) -> OptionsState.Error(DownloadError.FormatExpired)
                        rendition == null -> OptionsState.ImageError(ImageFailure.NO_MEDIA)
                        else -> OptionsState.ImageReady(result.inspection, rendition, previews[rendition.ref], result.discovery, result.saveAvailability,
                            route == MediaSourceRoute.DIRECT_IMAGE, previews, result.videoSource, origin.takeUnless { it.matches(source) })
                    }
                }
                is MediaInspectResult.Failure -> {
                    operationId = null; imageOperation = false
                    // yt-dlp found only this photo post's sound; a failed photo read shows its error (with Retry), not the sound alone.
                    if (legacyFallback != null && route == MediaSourceRoute.TIKTOK)
                        optionsState = OptionsState.ImageError(result.reason, result.statusCode, phase = result.phase)
                    else if (selectedPositions != null && route == MediaSourceRoute.TIKTOK)
                        optionsState = OptionsState.ImageError(
                            if (result.reason == ImageFailure.NO_MEDIA ||
                                (result.reason == ImageFailure.HTTP && result.statusCode in setOf(404, 410)))
                                ImageFailure.EXPIRED else result.reason,
                            result.statusCode, phase = result.phase)
                    else if (handoff) awaitRace(legacyReaderSource(source, route), origin)
                    else if (readerFallsBack(route, result, source)) {
                        val landing = result.fallback?.takeIf { route == MediaSourceRoute.LINKEDIN &&
                            it.revision == source.revision && CANONICAL_LINKEDIN_POST.matches(it.link.rawUrl) }
                        startLegacyInspection(landing ?: legacyReaderSource(source, route), origin,
                            noMedia = route == MediaSourceRoute.FACEBOOK && result.reason == ImageFailure.NO_MEDIA,
                            tikTokPhotos = route != MediaSourceRoute.TIKTOK,
                            photoFailure = if (route == MediaSourceRoute.TIKTOK) OptionsState.ImageError(result.reason,
                                result.statusCode, phase = result.phase) else null)
                    } else optionsState = OptionsState.ImageError(result.reason, result.statusCode,
                        directImage = route == MediaSourceRoute.DIRECT_IMAGE, phase = result.phase)
                }
                is MediaInspectResult.Legacy -> {
                    operationId = null; imageOperation = false
                    optionsState = OptionsState.ImageError(ImageFailure.UNAVAILABLE)
                }
                is MediaInspectResult.VideoRedirect -> {
                    operationId = null; imageOperation = false
                    // A Facebook/LinkedIn video post: yt-dlp inspects (and the job downloads) only its own clean link:
                    // Facebook's exact watch link, LinkedIn's exact post URL; from that route's reader, at this revision.
                    photosUnavailable = result.photosUnavailable
                    if (legacyFallback != null && route == MediaSourceRoute.TIKTOK) {
                        showReady(legacyFallback, origin); return@launch
                    }
                    val watch = result.source
                    // These readers hand back the user's own link; normalize existing X/Instagram aliases before yt-dlp.
                    if (route in HANDOFF_ROUTES) {
                        if (handoff) awaitRace(legacyReaderSource(source, route), origin)
                        else optionsState = OptionsState.ImageError(ImageFailure.UNSUPPORTED)
                        return@launch
                    }
                    if (route == MediaSourceRoute.TIKTOK && watch.revision == source.revision &&
                        TikTokPostIdentity.parse(watch.link.rawUrl)?.videoUrl == watch.link.rawUrl &&
                        TikTokPostIdentity.parse(watch.link.rawUrl)?.postId == TikTokPostIdentity.parse(source.link.rawUrl)?.postId) {
                        startLegacyInspection(watch, origin, tikTokPhotos = false); return@launch
                    }
                    val canonical = when (route) {
                        MediaSourceRoute.FACEBOOK -> CANONICAL_WATCH
                        MediaSourceRoute.LINKEDIN -> CANONICAL_LINKEDIN_POST
                        else -> null
                    }
                    if (canonical != null && watch.revision == source.revision &&
                        canonical.matches(watch.link.rawUrl)) startLegacyInspection(watch, origin)
                    else optionsState = OptionsState.ImageError(ImageFailure.UNSUPPORTED)
                }
            }
        }
    }
    private fun legacyReaderSource(source: LinkRevision, route: MediaSourceRoute): LinkRevision {
        val canonical = when (route) {
            MediaSourceRoute.LINKEDIN -> LinkedInPostIdentity.parse(source.link.rawUrl)?.cleanUrl
            MediaSourceRoute.X -> XPostIdentity.parse(source.link.rawUrl)?.legacyUrl
            MediaSourceRoute.INSTAGRAM -> InstagramPostIdentity.parse(source.link.rawUrl)?.legacyUrl
            MediaSourceRoute.TIKTOK -> TikTokPostIdentity.parse(source.link.rawUrl)?.videoUrl
            MediaSourceRoute.PINTEREST -> PinterestPostIdentity.parse(source.link.rawUrl)?.fetchUrl
            else -> null
        }
        return canonical?.let(LinkInput::parse)?.let { LinkRevision(it, source.revision) } ?: source
    }

    /** Only pre-verdict post-page failures get one legacy inspection; media verdicts stay terminal.
     *  yt-dlp cannot read a Facebook /share/ link, so a failed share-page read shows its own error (with Retry). */
    private fun readerFallsBack(route: MediaSourceRoute, result: MediaInspectResult.Failure, source: LinkRevision): Boolean =
        route != MediaSourceRoute.DIRECT_IMAGE && result.phase == ImagePhase.PAGE && !tikTokPhotoLink(route, source) && when (result.reason) {
            ImageFailure.HTTP -> result.statusCode != 404 && result.statusCode != 410 && !facebookShare(route, source)
            ImageFailure.SOURCE_REFUSED, ImageFailure.BUDGET, ImageFailure.UNAVAILABLE, ImageFailure.NETWORK -> !facebookShare(route, source)
            ImageFailure.NO_MEDIA -> route in setOf(MediaSourceRoute.FACEBOOK, MediaSourceRoute.LINKEDIN,
                MediaSourceRoute.TELEGRAM, MediaSourceRoute.BLUESKY, MediaSourceRoute.TIKTOK)
            else -> false
        }
    /** yt-dlp reads only a photo post's sound: a failed photo read shows its own error (with Retry), never sound alone. */
    private fun tikTokPhotoLink(route: MediaSourceRoute, source: LinkRevision) = route == MediaSourceRoute.TIKTOK &&
        TikTokPostIdentity.parse(source.link.rawUrl)?.isPhoto == true
    private fun facebookShare(route: MediaSourceRoute, source: LinkRevision) = route == MediaSourceRoute.FACEBOOK &&
        FacebookPostIdentity.parse(source.link.rawUrl)?.kind == FacebookLinkKind.SHARE
    /** Keep staleness bound to the intake link when the reader or canonicalization changes the engine URL. */
    private fun startLegacyInspection(input: LinkRevision, readerOrigin: LinkRevision? = null, noMedia: Boolean = false,
        tikTokPhotos: Boolean = true, photoFailure: OptionsState.ImageError? = null) {
        // A carousel's explicit View video options starts a new inspection without startInspection().
        val timing = inspectionTiming ?: InspectionTiming().also {
            it.record(InspectionPhase.INTAKE_ACCEPTED)
            inspectionTiming = it
            inspectionStartedAt = now()
            inspectionLink = input.link.rawUrl
        }
        // Reels/TV bypass the image reader, so canonicalize Instagram at the shared legacy entry too.
        val source = legacyReaderSource(input, MediaSourceRoute.INSTAGRAM)
        val origin = readerOrigin ?: input.takeUnless { it.matches(source) }
        val id = newOperationId()
        operationId = id
        val knownThumbnail = YouTubeOEmbed.thumbnail(source.link.rawUrl)
        optionsState = OptionsState.Checking(origin ?: source, id, earlyPreview = knownThumbnail?.let { EarlyPreview(null, it) })
        if (YouTubeOEmbed.supports(source.link.rawUrl)) {
            earlyPreviewJob = viewModelScope.launch {
                val preview = try { kotlinx.coroutines.withTimeoutOrNull(4_000) { earlyPreview(source.link) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (preview != null && operationId == id && currentSource()?.matches(origin ?: source) == true) {
                    (optionsState as? OptionsState.Checking)?.takeIf { it.operationId == id }?.let {
                        optionsState = it.copy(earlyPreview = EarlyPreview(preview.title, preview.thumbnailUrl ?: knownThumbnail))
                        if (preview.title != null) timing.record(InspectionPhase.EARLY_TITLE)
                    }
                }
            }
        }
        inspectionJob = viewModelScope.launch(timing.context()) { showLegacy(readLegacy(source, id), id, source, origin, noMedia, tikTokPhotos, photoFailure) }
    }
    /** One yt-dlp read under maintenance admission; generations are captured there and applied only by [showLegacy]. */
    private class LegacyRead(val result: InspectResult, val readGeneration: Long? = null,
        val version: Pair<String?, Long?>? = null)
    private suspend fun readLegacy(source: LinkRevision, id: String): LegacyRead {
        val inspect: suspend () -> LegacyRead = inspect@{
            InspectionTiming.mark(InspectionPhase.ENGINE_GATE_PASSED)
            // A transient first-install failure may look unavailable while maintenance still owns readiness.
            // Evaluate only after admission; do not turn the shared-link sheet into an immediate failure.
            if (!engine.isAvailable) return@inspect LegacyRead(InspectResult.Failure(DownloadError.EngineUnavailable))
            val result = EngineTiming.measure("engine_inspect") { engine.inspect(source, id) }
            val generation = downloads?.maintenance?.state?.value?.generation
            // Capture after readiness, within admission: a first-run update may have changed generation.
            LegacyRead(result, generation.takeIf { result is InspectResult.Failure },
                if (currentEngineVersion == null && result is InspectResult.Success) engine.installedVersion() to generation else null)
        }
        return try {
            downloads?.maintenance?.withInspection(waiting = { waiting ->
                if (id == raceOperationId) raceWaiting = waiting
                if (operationId == id) (optionsState as? OptionsState.Checking)?.let {
                    optionsState = it.copy(preparing = waiting)
                }
            }, block = inspect) ?: inspect()
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LegacyRead(InspectResult.Failure(DownloadError.Failed)) }
    }
    private fun startRace(source: LinkRevision) {
        val id = newOperationId()
        raceOperationId = id
        raceWaiting = false
        // Dispatched, not immediate: the reader starts first; yt-dlp follows on the next main-loop turn.
        race = viewModelScope.async(Dispatchers.Main + requireNotNull(inspectionTiming).context()) { readLegacy(source, id) }
    }
    /** Photo verdict, terminal failure or a new sheet: the speculative read never reaches the UI, nor admission later. */
    private fun endRace() {
        val id = raceOperationId
        race?.cancel(); race = null
        raceOperationId = null; raceWaiting = false
        id?.let(engine::cancel)
    }
    /** The reader handed off: the speculative read becomes this sheet's only yt-dlp inspection. */
    private fun awaitRace(input: LinkRevision, readerOrigin: LinkRevision) {
        val running = race ?: return startLegacyInspection(input, readerOrigin)
        val id = raceOperationId ?: return startLegacyInspection(input, readerOrigin)
        // [race] stays set so invalidation still cancels it; the id is now [operationId]'s to cancel.
        raceOperationId = null
        val source = legacyReaderSource(input, MediaSourceRoute.INSTAGRAM)
        operationId = id
        optionsState = OptionsState.Checking(readerOrigin, id, preparing = raceWaiting)
        inspectionJob = viewModelScope.launch(requireNotNull(inspectionTiming).context()) {
            val read = running.await()
            if (race === running) race = null
            showLegacy(read, id, source, readerOrigin, noMedia = false)
        }
    }
    private fun showLegacy(read: LegacyRead, id: String, source: LinkRevision, origin: LinkRevision?, noMedia: Boolean,
        tikTokPhotos: Boolean = true, photoFailure: OptionsState.ImageError? = null) {
        read.version?.let { (version, generation) ->
            inspectedEngineVersion = version
            inspectedMaintenanceGeneration = generation
        }
        // Both the attempt and URL revision must still belong to this sheet.
        if (operationId != id || currentSource()?.matches(origin ?: source) != true) return
        operationId = null
        earlyPreviewJob?.cancel(); earlyPreviewJob = null
        when (val result = read.result) {
            is InspectResult.Failure -> {
                optionsState = if (noMedia && result.error in setOf(
                    DownloadError.Failed, DownloadError.Unavailable, DownloadError.Unsupported))
                    OptionsState.ImageError(ImageFailure.NO_MEDIA) else OptionsState.Error(result.error)
                failedReadGeneration = read.readGeneration
            }
            is InspectResult.Success -> {
                optionsProvenance = when (result.provenance) {
                    dev.ahmed.savlino.engine.InspectionProvenance.CACHE -> UsageKeys.Path.CACHE
                    dev.ahmed.savlino.engine.InspectionProvenance.NPE -> UsageKeys.Path.NPE
                    dev.ahmed.savlino.engine.InspectionProvenance.YTDLP -> UsageKeys.Path.YTDLP
                    dev.ahmed.savlino.engine.InspectionProvenance.OWN -> UsageKeys.Path.OWN
                    null -> null
                }
                val returned = result.inspection
                when {
                    !returned.source.matches(source) -> optionsState = OptionsState.Error(DownloadError.FormatExpired)
                    displayFormats(returned).isEmpty() -> optionsState = OptionsState.Error(DownloadError.NoFormats)
                    tikTokPhotos && images != null && isTikTokPhotoAudio(returned) ->
                        startImageInspection(images, source, MediaSourceRoute.TIKTOK, origin ?: source, returned)
                    // A TikTok page read failed and yt-dlp found only a photo post's sound: show the photo failure
                    // (with Retry), never the sound alone, whatever the link's /video/ or /photo/ shape.
                    photoFailure != null && isTikTokPhotoAudio(returned) -> optionsState = photoFailure
                    else -> showReady(returned, origin)
                }
            }
        }
    }
    /** Derived MP3 choices still name the same returned audio source; real video formats never trigger a page read. */
    private fun isTikTokPhotoAudio(inspection: Inspection): Boolean =
        TikTokPostIdentity.parse(inspection.source.link.rawUrl)?.isPhoto == false && inspection.formats.isNotEmpty() &&
            inspection.formats.all { !it.hasVideo && it.hasAudio && it.sourceFormatId == "audio" }

    private fun showReady(returned: Inspection, origin: LinkRevision?) {
        val ready = OptionsState.Ready(returned, origin)
        readAt = now()
        optionsState = ready
        previewSession?.let { session -> viewModelScope.launch { session.await().write(returned) } }
        downloads?.let { coordinator -> viewModelScope.launch {
            val found = coordinator.savedBefore(returned.source.link.normalizedUrl)
            if (optionsState === ready) savedFormats = found
        } }
        // The Share sheet keeps its own view model: a pick confirmed there is the one to remember here too.
        // Only a change made elsewhere counts: a stored pick that merely lags this screen's restored one must not win.
        storedPick?.invoke()?.takeIf { it != seenPick }?.let { pick ->
            seenPick = pick
            lastUsedHeight = pick.second?.takeIf { it > 0 }
            lastUsedPreset = if (lastUsedHeight != null) null else pick.first?.takeUnless { it.audio }
        }
        // Video links prefer remembered/Balanced, then the engine's BEST, never audio;
        // a link with no video at all (TikTok photo slideshow) preselects its first audio row.
        val options = presetOptions
        val preferred = if (formatOptions.any { it.hasVideo }) lastUsedPreset ?: Preset.BALANCED
            else options.firstOrNull()?.first
        val initial = options.singleOrNull { it.first == preferred }
            ?: options.firstOrNull { it.first == Preset.BEST }
        val remembered = lastUsedFormat
        selectedId = remembered?.id ?: initial?.second?.id
        selectedPreset = if (remembered != null) {
            options.firstOrNull { it.second.id == remembered.id && it.first == lastUsedPreset }
                ?.first ?: options.firstOrNull { it.second.id == remembered.id }?.first
        } else initial?.first
        // A single selectable row (LinkedIn/X lone "Video") is always the choice.
        if (selectedId == null) eligibleFormats.singleOrNull()?.let { only ->
            selectedId = only.id
            selectedPreset = options.firstOrNull { it.second.id == only.id }?.first
        }
        preselected = selectedFormat?.takeIf { betterFrom == null && eligibleFormats.size > 1 }
        betterFrom?.let { from ->
            // The next tier up from the saved file, as the main list shows it; nothing above → say so, keep the options.
            val above = BetterQuality.above(formatOptions, from)
            val tier = above.lastOrNull()?.let { VideoQuality.of(it.width, it.height) }
            val next = primaryFormats.firstOrNull { it in above && VideoQuality.of(it.width, it.height) == tier } ?: above.lastOrNull()
            if (next == null) betterNoneChannel.trySend(Unit)
            else { selectedId = next.id; selectedPreset = options.firstOrNull { it.second.id == next.id }?.first }
        }
    }
    private fun currentSource() = LinkInput.parse(text)?.let { LinkRevision(it, revision) }

    /** Engine supplies ranking/pairs. UI only removes unplayable/unsupported presentation rows. */
    private fun isSelectable(row: Format): Boolean = if (row.hasVideo) {
        // The engine chooses MP4 or MKV for merges. WebM stays hidden except the direct-file fallback.
        // A silent X GIF needs no audio partner and may have no reported height.
        row.container in setOf("mp4", "mkv") && (row.height != null || row.animatedGif) &&
            (row.hasAudio || row.requiresMerge || row.animatedGif) && row.conversion == null
    } else row.hasAudio && ((row.container == "m4a" && row.conversion == null) || (row.container == "mp3" && row.conversion != null))

    /** Facebook's named progressive files remain beside DASH; other unreported files stay fallback-only. */
    private fun selectable(value: Inspection): List<Format> = value.formats.filter {
        isSelectable(it) || (it.unreportedStreams && it.container == "mp4" && it.id in setOf("sd", "hd")) }
        .ifEmpty { value.formats.filter { it.unreportedStreams && it.container in setOf("mp4", "mov", "webm") } }

    private fun displayFormats(value: Inspection): List<Format> {
        val selectable = selectable(value)
        val videos = selectable.filter { it.hasVideo }.sortedByDescending { FormatChoices.quality(it) }
        val audio = selectable.filter { !it.hasVideo }
            .sortedWith(compareBy<Format> { it.conversion != null }.thenBy { it.conversion?.bitrateKbps ?: it.bitrateKbps ?: 0 })
        return videos + audio
    }
    fun selectFormat(id: String) {
        if (!submitted && !refreshing && eligibleFormats.any { it.id == id }) {
            if (selectedId != id) pendingPermissionChoice = null
            selectedId = id; selectedPreset = null; optionsChanged = false
        }
    }
    fun selectPreset(preset: Preset) {
        if (submitted || refreshing) return
        presetOptions.singleOrNull { it.first == preset }?.let {
            if (selectedId != it.second.id) pendingPermissionChoice = null
            selectedId = it.second.id; selectedPreset = preset; optionsChanged = false
        }
    }
    fun showMoreFormats() { if (optionsState is OptionsState.Ready && !submitted) moreFormats = true }
    fun hideMoreFormats() { moreFormats = false }
    fun dismissOptions() {
        clearBetter()
        if (optionsState != OptionsState.Hidden && !hasPendingShare && linkQueue != null) nextLinkReady = true
        LinkInput.parse(text)?.let { link ->
            gate.dismiss(link)
            // Before initialization this is only a session gate; never persist its temporary tags.
            if (clipboardReady) {
                val hashes = gate.dismissedHashes()
                viewModelScope.launch { persistClipboard(hashes) }
            } else pendingClipboardDismissals += link
        }
        invalidateOptions()
        shareHint = null; savedLink = null
        if (automaticClipboardDraft) {
            automaticClipboardDraft = false
            text = ""; saved.remove<String>("text")
            copiedLink = false
        }
    }
    /** After a download or save is handed off, the field is empty again, ready for the next link. */
    private fun clearField() {
        updateText("", persist = false)
        shareActive = false; saved["shareActive"] = false
    }
    /**
     * "Paste another link" on a failure sheet: the field is cleared; a different copied link is pasted
     * (and loads as any paste does). The same failed link is not pasted again.
     */
    fun pasteAnother(value: String?) {
        val failed = currentSource()?.link?.normalizedUrl
        dismissOptions()
        clearField()
        val next = value?.let(LinkInput::parse) ?: return
        if (next.normalizedUrl != failed) paste(value)
    }
    private fun invalidateOptions() {
        previewRequest = null
        cachedPreviewJob?.cancel(); cachedPreviewJob = null
        previewSession?.cancel(); previewSession = null
        cachedPreview = null
        pendingPlaylistPermission?.cancel(); pendingPlaylistPermission = null
        playlist.close()
        failedReadGeneration = null
        photosUnavailable = false
        pendingPermissionChoice = null
        val old = operationId
        val image = imageOperation
        operationId = null // Invalidate before cancellation can release a late callback.
        imageOperation = false
        inspectionJob?.cancel(); inspectionJob = null
        earlyPreviewJob?.cancel(); earlyPreviewJob = null
        inspectionTiming = null
        inspectionLink = null
        optionsProvenance = null
        old?.let { if (image) images?.cancel(it) else engine.cancel(it) }
        endRace()
        selectedId = null; selectedPreset = null; command = null; submissionId = null; submitted = false; moreFormats = false
        refreshing = false; optionsChanged = false; readAt = null
        savedFormats = emptyMap()
        optionsState = OptionsState.Hidden
    }
    /** Consume the immutable confirmation exactly once. */
    @Synchronized fun takeConfirmedCommand(): DownloadCommand? = command.also { command = null }
    @Synchronized fun confirmDownload() = confirmDownload(refreshed = false)
    /** [refreshed]: this tap already read the link again, so a sheet that is still stale fails instead of reading again. */
    @Synchronized private fun confirmDownload(refreshed: Boolean) {
        if (submitted || refreshing || optionsChanged || engineUpdating) return
        val inspected = inspection ?: return
        val current = currentSource() ?: return
        val origin = confirmAgainst ?: return
        val version = engineVersion()
        val stale = inspected.engineVersion != version || readAt.let { it == null || now() - it >= SHEET_FRESH_MS }
        if (!engine.isAvailable || version == null || !origin.matches(current) || (stale && refreshed)) {
            failConfirmation(if (!engine.isAvailable) DownloadError.EngineUnavailable else DownloadError.FormatExpired)
            return
        }
        val row = selectedFormat ?: return
        if (stale) return refreshBeforeDownload(row)
        // A resolved watch link is the job's source; the field keeps showing the user's own link.
        val jobSource = if ((optionsState as? OptionsState.Ready)?.resolvedFrom != null) inspected.source else current
        when (val result = DownloadCommand.confirm(UUID.randomUUID().toString(), inspected, jobSource, row.id, "Nezzel")) {
            is ConfirmResult.Confirmed -> {
                usage.count(UsageKeys.FUNNEL_TAPPED)
                UsageKeys.pick(preselected, row)?.let(usage::count)
                submitted = true
                command = result.command
                EngineTiming.startDownload(result.command.jobId)
                if (row.hasVideo) {
                    val preset = selectedPreset?.takeUnless { it.audio }
                    val height = FormatChoices.quality(row)
                    if (preset != null || height != null) {
                        lastUsedPreset = preset
                        lastUsedHeight = height.takeIf { preset == null }
                        saved["lastPreset"] = preset?.name
                        saved["lastHeight"] = lastUsedHeight
                        if (preset != null) rememberPreset(preset) else rememberHeight(checkNotNull(height))
                        seenPick = storedPick?.invoke()
                    }
                }
                downloads?.let { coordinator ->
                    val confirmed = takeConfirmedCommand() ?: return@let
                    val preset = selectedPreset
                    submissionId = confirmed.jobId
                    // Application scope survives Activity recreation and ViewModel clearance.
                    (coordinatorScope ?: viewModelScope).launch {
                        try {
                            coordinator.enqueue(confirmed, preset)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                if (submissionId == confirmed.jobId) { acceptedJob = confirmed.jobId; dismissOptions(); clearField() }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Throwable) {
                            // A class-init or recursion Error must fail the submission visibly, not vanish in the app handler.
                            if (failure is VirtualMachineError && failure !is StackOverflowError) throw failure
                            EngineTiming.forgetDownload(confirmed.jobId)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                                if (submissionId == confirmed.jobId) failConfirmation(DownloadError.ExecutionRestricted)
                            }
                        }
                    }
                }
            }
            ConfirmResult.Stale -> failConfirmation(DownloadError.FormatExpired)
            is ConfirmResult.Invalid -> failConfirmation(result.reason)
        }
    }
    /**
     * Download on a stale sheet (engine updated, or options older than [SHEET_FRESH_MS]) reads the same link again on
     * the normal path (NPE first on YouTube, then yt-dlp), whatever "Load options automatically" says: the user tapped.
     * It downloads only when the fresh read offers the same choice, and always the fresh row (rule 3); otherwise the
     * fresh options show with a note and the user picks again.
     */
    private fun refreshBeforeDownload(picked: Format) {
        val ready = optionsState as? OptionsState.Ready ?: return
        val source = ready.inspection.source
        val pickedBytes = shownBytes(ready.inspection, picked)
        val preset = selectedPreset
        val id = newOperationId()
        operationId = id
        refreshing = true
        inspectionJob = viewModelScope.launch {
            val read = readLegacy(source, id)
            if (operationId != id) return@launch // Edited or dismissed meanwhile: invalidation already reset the sheet.
            refreshing = false
            showLegacy(read, id, source, ready.resolvedFrom, noMedia = false)
            // All formats has no error view: a failed read goes back to the sheet, which shows it.
            if (optionsState !is OptionsState.Ready) moreFormats = false
            // The read landed only if its inspection is the one now on screen (not an error, not a dropped late result).
            val fresh = (read.result as? InspectResult.Success)?.inspection
                ?.takeIf { (optionsState as? OptionsState.Ready)?.inspection === it } ?: return@launch
            val row = selectable(fresh).singleOrNull { it.id == picked.id }
            if (row == null || !FormatChoices.sameChoice(picked, row, pickedBytes, shownBytes(fresh, row))) {
                optionsChanged = true
                return@launch
            }
            selectedId = row.id
            selectedPreset = preset?.takeIf { choice -> presetOptions.any { it.first == choice && it.second.id == row.id } }
            confirmDownload(refreshed = true)
        }
    }
    private fun shownBytes(from: Inspection, format: Format): Long? = when (val size = format.size) {
        is Size.Exact -> size.bytes
        is Size.Estimated -> size.bytes
        Size.Unknown -> from.roughSizeBytes[format.id]
    }
    fun cancelDownload(id: String) {
        downloads?.let { coordinator ->
            coordinator.requestCancellation(id)
            (coordinatorScope ?: viewModelScope).launch { coordinator.cancel(id) }
        }
    }
    /** The Save this sheet started; outcomes for any other selection are ignored. */
    private var refreshedTikTok = false
    private var pendingImageSave by mutableStateOf<MediaSelection?>(null)
    private val imageEventChannel = Channel<ImageSaveState>(Channel.UNLIMITED) // Tiny events; none may be lost.
    /** Saved or failed outcomes of this sheet's Save, for the snackbar; kept until the screen collects them. */
    val imageEvents: Flow<ImageSaveState> = imageEventChannel.receiveAsFlow()
    var selectedImageRefs by mutableStateOf<Set<MediaRenditionRef>>(emptySet())
        private set
    var imageSaveProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    /** TikTok photo post: the sound tile is ticked; Save also downloads it. Unticked for every new post. */
    var soundSelected by mutableStateOf(false)
        private set
    /** The sheet whose Save is still reading its sound; it holds only that sheet, never the next link's. */
    private var soundSheet by mutableStateOf<OptionsState.ImageReady?>(null)
    private var soundJob: kotlinx.coroutines.Job? = null
    private var soundOperation: String? = null
    /** Sound failed while photos still save: told after the photo result, so its snackbar does not hide this one. */
    private var soundNoticeAfterPhotos = false
    private val soundFailedChannel = Channel<Unit>(Channel.UNLIMITED)
    /** The sound of this sheet's Save could not be downloaded (no read, or no audio-only format returned). */
    val soundFailedEvents: Flow<Unit> = soundFailedChannel.receiveAsFlow()
    val imageSaving get() = pendingImageSave != null || soundSheet.let { it != null && it === optionsState }
    /** Only the sound is being read: the bar says so instead of a photo count. */
    val soundSaving get() = pendingImageSave == null && imageSaving
    fun toggleSound() {
        val ready = optionsState as? OptionsState.ImageReady ?: return
        if (!imageSaving && ready.discovery.sound != null) soundSelected = !soundSelected
    }
    fun toggleImage(ref: MediaRenditionRef) {
        val ready = optionsState as? OptionsState.ImageReady ?: return
        if (imageSaving || ref in ready.expiredPhotos || ready.photos.none { it.ref == ref }) return
        selectedImageRefs = if (ref in selectedImageRefs) selectedImageRefs - ref else selectedImageRefs + ref
    }
    fun selectAllImages(selected: Boolean) {
        val ready = optionsState as? OptionsState.ImageReady ?: return
        if (!imageSaving) selectedImageRefs = if (selected) ready.photos.map { it.ref }.toSet() - ready.expiredPhotos else emptySet()
    }
    /** Only returned video or sound evidence exposes this switch; the existing legacy route performs inspection. */
    fun inspectCarouselVideo() {
        val ready = optionsState as? OptionsState.ImageReady ?: return
        if ((!ready.discovery.hasVideo && ready.discovery.sound == null) || imageSaving) return
        val origin = ready.origin ?: ready.inspection.source
        if (currentSource()?.matches(origin) != true) return
        val source = ready.inspection.source
        val route = ImageRoute.of(source.link)
        val video = ready.videoSource
        val sound = tikTokSoundSource(ready)
        invalidateOptions()
        if (sound != null) startLegacyInspection(sound, origin, tikTokPhotos = false)
        else if (video == null) startLegacyInspection(legacyReaderSource(source, route), origin)
        else if (route == MediaSourceRoute.FACEBOOK && video.revision == source.revision && CANONICAL_WATCH.matches(video.link.rawUrl))
            startLegacyInspection(video, origin)
        else optionsState = OptionsState.ImageError(ImageFailure.UNSUPPORTED)
    }
    /** The post's own exact /video/ link at this revision: the only link its sound is read from. */
    private fun tikTokSoundSource(ready: OptionsState.ImageReady): LinkRevision? {
        val video = ready.videoSource ?: return null
        val source = ready.inspection.source
        val identity = TikTokPostIdentity.parse(video.link.rawUrl)
        return video.takeIf { ready.discovery.sound != null && it.revision == source.revision && identity?.videoUrl == it.link.rawUrl &&
            identity.postId == TikTokPostIdentity.parse(source.link.rawUrl)?.postId }
    }
    init { imageSaver?.let { saver -> viewModelScope.launch { saver.outcome.collect { onImageOutcome(it) } } } }
    val imageSaveEnabled get() = (optionsState as? OptionsState.ImageReady)?.let { ready ->
        val photos = imageSaver != null && ready.availability == ImageSaveAvailability.Available &&
            ready.photos.any { it.ref in selectedImageRefs && it.ref !in ready.expiredPhotos }
        !imageSaving && (photos || (soundSelected && ready.discovery.sound != null)) &&
            currentSource()?.matches(ready.origin ?: ready.inspection.source) == true
    } == true
    /** Explicit Save of the chosen returned images (and the ticked sound). No notification permission: nothing is posted. */
    fun saveImage() {
        if (!imageSaveEnabled) return
        val ready = optionsState as? OptionsState.ImageReady ?: return
        val chosen = ready.photos.filter { it.ref in selectedImageRefs && it.ref !in ready.expiredPhotos }
            .takeIf { imageSaver != null && ready.availability == ImageSaveAvailability.Available }.orEmpty()
        val sound = soundSelected
        usage.count(UsageKeys.FUNNEL_TAPPED)
        // Photos first: a sound failure seen before their save starts would otherwise be hidden by their snackbar.
        if (chosen.isNotEmpty()) savePhotos(ready, chosen)
        if (sound) saveSound(ready, alone = chosen.isEmpty())
    }
    private fun savePhotos(ready: OptionsState.ImageReady, chosen: List<MediaRendition.Image>) {
        val first = chosen.first()
        val metadata = ready.inspection.items.first().metadata
        val title = (metadata.title ?: metadata.uploader)?.let(::shortFileTitle)?.takeIf { it.isNotBlank() } ?: "Nezzel_" +
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", java.util.Locale.ROOT))
        val selection = ready.inspection.selection(first.ref) ?: return
        // Preserve single-image outcomes/API. Carousel suffixes use original returned item positions, not tap order.
        if (!ready.isCarousel) {
            if (imageSaver?.save(selection, first.image, title) == true) {
                pendingImageSave = selection; imageSaveProgress = 1 to 1
                usage.count(UsageKeys.photo(UsageKeys.Download.STARTED))
            }
        } else {
            val total = maxOf(ready.discovery.claimedCount ?: 0, ready.inspection.items.maxOf { it.position + 1 },
                ready.photos.size + ready.discovery.unavailableCount + ready.discovery.cappedCount)
            val requests = chosen.map { photo ->
                val item = ready.inspection.items.single { it.ref == photo.ref.item }
                dev.ahmed.savlino.media.ImageSaveRequest(checkNotNull(ready.inspection.selection(photo.ref)), photo.image,
                    if (total == 1) title else dev.ahmed.savlino.media.indexedImageTitle(title, item.position + 1,
                        photo.image.format.extension, total))
            }
            if (imageSaver?.saveBatch(requests) == true) {
                pendingImageSave = selection; imageSaveProgress = 1 to requests.size
                repeat(requests.size) { usage.count(UsageKeys.photo(UsageKeys.Download.STARTED)) }
            }
        }
    }
    /** At most [IMAGE_TITLE_MAX] code points, cut at the last word break when there is one. */
    private fun shortFileTitle(text: String): String {
        val trimmed = text.trim()
        if (trimmed.codePointCount(0, trimmed.length) <= IMAGE_TITLE_MAX) return trimmed
        val cut = trimmed.substring(0, trimmed.offsetByCodePoints(0, IMAGE_TITLE_MAX))
        return cut.substringBeforeLast(' ').takeIf { it.isNotBlank() }?.trim() ?: cut
    }
    /**
     * Save is the confirmation: read the post's /video/ link as the sound route does, keep only an audio-only row the
     * engine returned (M4A first) and enqueue it as a normal job. The photo sheet stays as it is meanwhile.
     */
    private fun saveSound(ready: OptionsState.ImageReady, alone: Boolean) {
        soundSelected = false // Handled by this Save; a second Save after a partial photo failure must not repeat it.
        val video = tikTokSoundSource(ready)
        val coordinator = downloads
        if (video == null || coordinator == null) { soundFailed(); return }
        val id = newOperationId()
        soundOperation = id
        soundSheet = ready
        // Application scope: the job is the person's confirmed choice and outlives the sheet; Main as the legacy read is.
        soundJob = (coordinatorScope ?: viewModelScope).launch(Dispatchers.Main) {
            val inspected = (readLegacy(video, id).result as? InspectResult.Success)?.inspection?.takeIf { it.source.matches(video) }
            val audio = inspected?.let(::selectable)?.filter { !it.hasVideo && it.hasAudio }
            val row = audio?.firstOrNull { it.container == "m4a" } ?: audio?.firstOrNull()
            val command = if (inspected == null || row == null) null else
                (DownloadCommand.confirm(UUID.randomUUID().toString(), inspected, video, row.id, "Nezzel") as? ConfirmResult.Confirmed)?.command
            val queued = command != null && try {
                coordinator.enqueue(command, inspected?.presets?.firstOrNull { it.formatId == row?.id }?.preset)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) { if (failure is VirtualMachineError && failure !is StackOverflowError) throw failure; false }
            if (soundOperation != id) return@launch
            soundOperation = null; soundJob = null; soundSheet = null
            if (!queued) soundFailed()
            else if (alone && optionsState === ready) { acceptedJob = command?.jobId; dismissOptions(); clearField() }
        }
    }
    private fun soundFailed() { if (pendingImageSave != null) soundNoticeAfterPhotos = true else soundFailedChannel.trySend(Unit) }
    fun cancelImageSave() {
        if (!imageSaving) return
        imageSaver?.cancel()
        soundOperation?.let { engine.cancel(it); soundOperation = null }
        soundJob?.cancel(); soundJob = null; soundSheet = null
    }
    private fun onImageOutcome(outcome: ImageSaveState) {
        if (outcome is ImageSaveState.Saving) {
            if (imageSaving) imageSaveProgress = outcome.current to outcome.total
            return
        }
        val selection = when (outcome) {
            is ImageSaveState.BatchFinished -> outcome.selection
            is ImageSaveState.Saved -> outcome.selection
            is ImageSaveState.Failed -> outcome.selection
            else -> return
        }
        if (pendingImageSave != selection) return
        pendingImageSave = null
        imageSaveProgress = null
        val ready = optionsState as? OptionsState.ImageReady
        val showing = ready?.photos?.any { it.ref == selection.renditionRef } == true
        countPhotos(outcome, selection.source.link.rawUrl)
        when {
            outcome is ImageSaveState.BatchFinished -> {
                if (showing && outcome.saved.size == outcome.total) { dismissOptions(); clearField() }
                else if (showing) selectedImageRefs = selectedImageRefs - outcome.saved.map { it.selection.renditionRef }.toSet()
                imageEventChannel.trySend(outcome)
                if (showing && ready != null) refreshExpiredImages(ready, outcome.failures)
            }
            outcome is ImageSaveState.Saved -> { if (showing) { dismissOptions(); clearField() }; imageEventChannel.trySend(outcome) }
            (outcome as ImageSaveState.Failed).image == ImageFailure.CANCELLED -> Unit // Back to the same choice.
            showing && ready != null && outcome.image == ImageFailure.EXPIRED -> {
                imageEventChannel.trySend(outcome)
                refreshExpiredImages(ready, listOf(outcome))
            }
            showing -> optionsState = OptionsState.ImageError(outcome.image ?: ImageFailure.STAGING, outcome.statusCode, outcome.storage,
                ready?.directImage == true)
            else -> imageEventChannel.trySend(outcome)
        }
        if (soundNoticeAfterPhotos) { soundNoticeAfterPhotos = false; soundFailedChannel.trySend(Unit) }
    }
    private fun refreshExpiredImages(ready: OptionsState.ImageReady, failures: List<ImageSaveState.Failed>) {
        val expired = failures.filter { it.image == ImageFailure.EXPIRED }.map { it.selection.renditionRef }
        if (expired.isEmpty()) return
        ready.expiredPhotos = ready.expiredPhotos + expired
        if (!refreshedTikTok && images != null && TikTokPostIdentity.parse(ready.inspection.source.link.rawUrl) != null) {
            refreshedTikTok = true
            val positions = ready.inspection.items.filter { item -> item.renditions.any { it.ref in selectedImageRefs } }
                .map { it.position }.toSet()
            startImageInspection(images, ready.inspection.source, MediaSourceRoute.TIKTOK,
                ready.origin ?: ready.inspection.source, selectedPositions = positions)
        }
    }

    private fun countPhotos(outcome: ImageSaveState, link: String?) {
        val (saved, failed, cancelled) = when (outcome) {
            is ImageSaveState.BatchFinished -> Triple(outcome.saved.size, outcome.failedCount,
                // One cancel tap is one cancel, however many photos it stopped (or none, between items).
                if (outcome.cancelled || outcome.cancelledCount > 0) 1 else 0)
            is ImageSaveState.Saved -> Triple(1, 0, 0)
            is ImageSaveState.Failed -> if (outcome.image == ImageFailure.CANCELLED) Triple(0, 0, 1) else Triple(0, 1, 0)
            else -> return
        }
        repeat(saved) { usage.count(UsageKeys.photo(UsageKeys.Download.FINISHED)) }
        repeat(failed) { usage.count(UsageKeys.photo(UsageKeys.Download.FAILED)) }
        repeat(cancelled) { usage.count(UsageKeys.photo(UsageKeys.Download.CANCELLED)) }
        // One Save tap, one funnel outcome and one site, however many photos it held.
        if (saved > 0) usage.count(UsageKeys.FUNNEL_FINISHED) else if (failed > 0) usage.count(UsageKeys.FUNNEL_FAILED)
        if (saved + failed > 0) usage.site(link)
    }
    private fun failConfirmation(error: DownloadError) {
        selectedId = null; selectedPreset = null; moreFormats = false
        optionsState = OptionsState.Error(error)
    }
    override fun onCleared() {
        invalidateOptions()
        downloads?.maintenance?.setPendingLink(false)
        super.onCleared()
    }
    fun suggest(resumed: Boolean, focused: Boolean, foreground: Boolean, read: () -> String?) {
        if (!clipboardReady || !resumed || !focused || !foreground) return
        try {
            if (!clipboardEligible) return
            if (!SuggestionGate.canRead(focused, resumed, foreground, text.isEmpty(), shareActive)) return
            val value = read()
            suggestion = gate.offer(value, focused, resumed, foreground, text.isEmpty(), shareActive)
            if (suggestion != null) {
                usage.count(UsageKeys.CLIP_SHOWN)
                replaceLinkQueue(LinkInput.candidates(value))
                warmUp()
                if (automaticOptions) inspectSuggestion(automatically = true)
            }
        } finally {
            // Publish intake first: the IO maintenance coroutine must never observe an empty gap.
            downloads?.maintenance?.let {
                it.setPendingLink(hasPendingLink())
                it.foregroundClipboardChecked()
            }
        }
    }
    init {
        usage.count(UsageKeys.OPEN)
        publishPendingLink()
        initializeClipboard?.let { load ->
            viewModelScope.launch {
                gate = load()
                val hasPendingDismissals = pendingClipboardDismissals.isNotEmpty()
                pendingClipboardDismissals.forEach(gate::dismiss)
                pendingClipboardDismissals.clear()
                clipboardReady = true
                clipboardInit.complete(Unit)
                if (hasPendingDismissals) persistClipboard(gate.dismissedHashes())
            }
        }
    }
    private fun canInspectSuggestion() = clipboardReady && clipboardForeground && suggestionsEnabled &&
        !editorFocused && destination == "Home" && text.isEmpty() && !shareActive &&
        !hasPendingShare && !nextLinkReady && !invalidShare && optionsState == OptionsState.Hidden && !hasPendingNotificationChoice

    /** Serialize every snapshot; await durable handling, then revalidate the still-current offer on Main. */
    fun inspectSuggestion(automatically: Boolean = false) {
        val offered = suggestion ?: return
        if (!canInspectSuggestion()) return
        if (!automatically) {
            acceptSuggestion(offered, automatically = false)
            return
        }
        if (!automaticOptions || automaticOffer === offered) return
        gate.dismiss(offered)
        val hashes = gate.dismissedHashes()
        val offeredRevision = revision
        automaticOffer = offered
        viewModelScope.launch {
            try {
                val persisted = persistClipboard(hashes)
                currentCoroutineContext().ensureActive()
                if (!persisted) return@launch // Manual review stays available.
                if (suggestion !== offered || revision != offeredRevision || !automaticOptions || !canInspectSuggestion()) return@launch
                acceptSuggestion(offered, automatically = true)
            } finally {
                if (automaticOffer === offered) automaticOffer = null
            }
        }
    }
    private fun acceptSuggestion(offered: ValidatedLink, automatically: Boolean) {
        if (!automatically) usage.count(UsageKeys.CLIP_ACCEPTED)
        suggestion = null
        updateText(offered.rawUrl, persist = !automatically)
        automaticClipboardDraft = automatically
        copiedLink = true
        startInspection()
    }
    private suspend fun persistClipboard(hashes: Set<String>): Boolean = clipboardWrites.withLock {
        try { rememberHandledClipboard(hashes) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    fun dismissSuggestion() {
        if (suggestion != null) skipRemainingLinks()
        suggestion?.let {
            gate.dismiss(it)
            val hashes = gate.dismissedHashes()
            viewModelScope.launch { persistClipboard(hashes) }
        }
        suggestion = null
    }
    /** Copy link: the copied link counts as handled, so the clipboard never offers it back, also after a restart. */
    suspend fun markHandled(rawUrl: String): Boolean {
        val link = LinkInput.parse(rawUrl) ?: return false
        clipboardInit.await() // Outside clipboardWrites: init persists through it.
        gate.dismiss(link)
        return persistClipboard(gate.dismissedHashes())
    }
    fun dismissedHashes(): Set<String> = gate.dismissedHashes()
}

private val Preset.audio get() = this == Preset.AUDIO_M4A || this == Preset.AUDIO_MP3
