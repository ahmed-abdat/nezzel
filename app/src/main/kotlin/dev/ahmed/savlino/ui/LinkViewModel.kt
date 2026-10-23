package dev.ahmed.savlino.ui

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
import dev.ahmed.savlino.engine.InspectResult
import dev.ahmed.savlino.engine.InspectionPhase
import dev.ahmed.savlino.engine.InspectionTiming
import dev.ahmed.savlino.engine.mediahttp.EarlyPreview
import dev.ahmed.savlino.engine.mediahttp.YouTubeOEmbed
import dev.ahmed.savlino.engine.MediaSourceRoute
import dev.ahmed.savlino.engine.UnavailableEngine
import dev.ahmed.savlino.engine.facebook.FacebookPostIdentity
import dev.ahmed.savlino.engine.mediaimage.*
import dev.ahmed.savlino.engine.instagram.InstagramPostIdentity
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
        val previews: Map<MediaRenditionRef, ImageBitmap> = emptyMap(), val videoSource: LinkRevision? = null) : OptionsState {
        val photos = inspection.items.flatMap { it.renditions.filterIsInstance<MediaRendition.Image>() }
        val isCarousel get() = photos.size > 1 || discovery.cappedCount > 0 || discovery.unavailableCount > 0 || discovery.notPreviewedCount > 0
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
/** The only link a Facebook video post may hand to yt-dlp: no other host, path or query parameter. */
private val CANONICAL_WATCH = Regex("https://www\\.facebook\\.com/watch/\\?v=[0-9]{1,25}")
/** Readers that hand a proven video post back as the user's own link. */
private val HANDOFF_ROUTES = setOf(MediaSourceRoute.X, MediaSourceRoute.INSTAGRAM,
    MediaSourceRoute.TELEGRAM, MediaSourceRoute.BLUESKY)
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
    private val earlyPreview: suspend (ValidatedLink) -> EarlyPreview? = { null },
) : ViewModel() {
    var notificationPrompt: (() -> Unit)? = null
    private var pendingPermissionChoice: Pair<Inspection, String>? = null
    val hasPendingNotificationChoice get() = pendingPermissionChoice != null
    fun requestDownload() {
        if (!downloadEnabled || pendingPermissionChoice != null) return
        pendingPermissionChoice = (inspection ?: return) to (selectedId ?: return)
        notificationPrompt?.invoke() ?: finishNotificationRequest()
    }
    /** Grant, denial and skipping all continue the same explicit, still-current choice. */
    fun finishNotificationRequest() {
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
    fun updateEngine() {
        downloads?.let { coordinator ->
            (coordinatorScope ?: viewModelScope).launch { coordinator.maintenance.updateNow() }
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
    private fun reportCode(): String? = when (val state = optionsState) {
        is OptionsState.Error -> state.error.takeIf { it in ReportLink.downloadErrors }?.name
        is OptionsState.ImageError -> state.failure.takeIf { it in ReportLink.imageFailures }?.name
        else -> null
    }
    internal val canReportLink get() = reportCode() != null && currentSource() != null
    internal fun reportMessage(intro: String, versionName: String): String? {
        val code = reportCode() ?: return null
        val source = currentSource() ?: return null
        return ReportLink.message(intro, source.link.rawUrl, versionName, engineVersion(), code)
    }
    private var gate = SuggestionGate(dismissed, linkKeyer)
    private var clipboardReady by mutableStateOf(initializeClipboard == null)
    internal val clipboardDecisionReady get() = clipboardReady
    private val pendingClipboardDismissals = mutableListOf<ValidatedLink>()
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
        private set(value) { options = value; publishPendingLink() }
    private fun hasPendingLink() = pendingShare != null || suggestion != null || linkQueue != null ||
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
    private var inspectionJob: Job? = null
    private var earlyPreviewJob: Job? = null
    private var inspectionTiming: InspectionTiming? = null
    internal fun optionsShown() {
        if (optionsState is OptionsState.Ready || optionsState is OptionsState.ImageReady)
            inspectionTiming?.record(InspectionPhase.OPTIONS_SHOWN)
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
    /** Earlier completed downloads of this link, by format id (looked up once per inspection). */
    private var savedFormats by mutableStateOf<Map<String, dev.ahmed.savlino.jobs.SavedBefore>>(emptyMap())
    /** The chosen format of this link was saved before (file still present): a notice, never a block. */
    val savedBefore get() = selectedFormat?.let { savedFormats[it.id] }
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
    val confirmed get() = submitted
    val downloadEnabled get() = !submitted && !engineUpdating && engine.isAvailable && selectedFormat != null &&
        inspection?.let { currentSource()?.matches(confirmAgainst ?: return@let false) == true && it.engineVersion == engineVersion() } == true
    var suggestionsEnabled by mutableStateOf(true)
    var automaticOptions by mutableStateOf(true)
    var editorFocused by mutableStateOf(false)
    val clipboardEligible get() = clipboardReady && clipboardForeground && suggestionsEnabled && !editorFocused && destination == "Home" &&
        !hasSuggestion && !hasPendingShare && linkQueue == null && !invalidShare && !shareActive && text.isEmpty() &&
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
        warmUp()
        // Only a link being typed is protected. A shared, pasted or suggested link (or the one just
        // downloaded, which is cleared anyway) is simply replaced: no "replace?" step on the daily path.
        if (wasEditing || (text.isNotEmpty() && typedDraft)) {
            pendingShare = link.rawUrl; saved["pendingShare"] = pendingShare
        } else {
            updateText(link.rawUrl)
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
        if (value == null) return
        val candidates = LinkInput.candidates(value)
        val link = candidates.firstOrNull()
        dismissShare()
        if (hasOptionsFor(link)) { replaceLinkQueue(candidates); return }
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
    private fun updateText(value: String, persist: Boolean = true) {
        automaticClipboardDraft = false
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
        invalidateOptions()
        val source = currentSource()
        invalid = source == null; saved["invalid"] = invalid
        if (source == null) return
        inspectionTiming = InspectionTiming().also { it.record(InspectionPhase.INTAKE_ACCEPTED) }
        if (ImageRoute.isUnsupported(source.link)) {
            optionsState = if (FacebookPostIdentity.isStoryLink(source.link.rawUrl))
                OptionsState.ImageError(ImageFailure.FACEBOOK_STORY, phase = ImagePhase.PAGE)
                else OptionsState.Error(DownloadError.Unsupported)
            return
        }
        // Image routes run before (and without) the yt-dlp availability gate.
        val images = images ?: return startLegacyInspection(source)
        when (val route = ImageRoute.of(source.link)) {
            MediaSourceRoute.LEGACY -> startLegacyInspection(source)
            MediaSourceRoute.DIRECT_IMAGE, MediaSourceRoute.LINKEDIN, MediaSourceRoute.FACEBOOK, MediaSourceRoute.X,
            MediaSourceRoute.INSTAGRAM, MediaSourceRoute.TELEGRAM, MediaSourceRoute.BLUESKY ->
                startImageInspection(images, source, route)
        }
    }
    private fun startImageInspection(images: ImageInspect, source: LinkRevision, route: MediaSourceRoute) {
        val id = newOperationId()
        operationId = id
        imageOperation = true
        optionsState = OptionsState.Checking(source, id, image = true)
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
            InspectionTiming.mark(InspectionPhase.NATIVE_READER_DONE)
            if (operationId != id || currentSource()?.matches(source) != true) {
                (result as? MediaInspectResult.Success)?.close()
                return@launch
            }
            val handoff = route in HANDOFF_ROUTES && when (result) {
                is MediaInspectResult.Failure -> readerFallsBack(route, result)
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
                    if (operationId != id || currentSource()?.matches(source) != true) return@launch
                    selectedImageRefs = result.inspection.items.flatMap { it.renditions.filterIsInstance<MediaRendition.Image>() }
                        .map { it.ref }.toSet()
                    operationId = null; imageOperation = false
                    val rendition = result.inspection.items.firstOrNull()?.renditions?.firstOrNull() as? MediaRendition.Image
                    optionsState = when {
                        !result.inspection.source.matches(source) -> OptionsState.Error(DownloadError.FormatExpired)
                        rendition == null -> OptionsState.ImageError(ImageFailure.NO_MEDIA)
                        else -> OptionsState.ImageReady(result.inspection, rendition, previews[rendition.ref], result.discovery, result.saveAvailability,
                            route == MediaSourceRoute.DIRECT_IMAGE, previews, result.videoSource)
                    }
                }
                is MediaInspectResult.Failure -> {
                    operationId = null; imageOperation = false
                    if (handoff) awaitRace(legacyReaderSource(source, route), source)
                    else if (readerFallsBack(route, result)) {
                        val landing = result.fallback?.takeIf { route == MediaSourceRoute.LINKEDIN &&
                            it.revision == source.revision && CANONICAL_LINKEDIN_POST.matches(it.link.rawUrl) }
                        startLegacyInspection(landing ?: legacyReaderSource(source, route), source,
                            noMedia = route == MediaSourceRoute.FACEBOOK && result.reason == ImageFailure.NO_MEDIA)
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
                    val watch = result.source
                    // These readers hand back the user's own link; normalize existing X/Instagram aliases before yt-dlp.
                    if (route in HANDOFF_ROUTES) {
                        if (handoff) awaitRace(legacyReaderSource(source, route), source)
                        else optionsState = OptionsState.ImageError(ImageFailure.UNSUPPORTED)
                        return@launch
                    }
                    val canonical = when (route) {
                        MediaSourceRoute.FACEBOOK -> CANONICAL_WATCH
                        MediaSourceRoute.LINKEDIN -> CANONICAL_LINKEDIN_POST
                        else -> null
                    }
                    if (canonical != null && watch.revision == source.revision &&
                        canonical.matches(watch.link.rawUrl)) startLegacyInspection(watch, source)
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
            else -> null
        }
        return canonical?.let(LinkInput::parse)?.let { LinkRevision(it, source.revision) } ?: source
    }

    /** Only pre-verdict post-page failures get one legacy inspection; media verdicts stay terminal. */
    private fun readerFallsBack(route: MediaSourceRoute, result: MediaInspectResult.Failure): Boolean =
        route != MediaSourceRoute.DIRECT_IMAGE && result.phase == ImagePhase.PAGE && when (result.reason) {
            ImageFailure.HTTP -> result.statusCode != 404 && result.statusCode != 410
            ImageFailure.SOURCE_REFUSED, ImageFailure.BUDGET, ImageFailure.UNAVAILABLE, ImageFailure.NETWORK -> true
            ImageFailure.NO_MEDIA -> route in setOf(MediaSourceRoute.FACEBOOK, MediaSourceRoute.LINKEDIN,
                MediaSourceRoute.TELEGRAM, MediaSourceRoute.BLUESKY)
            else -> false
        }
    /** Keep staleness bound to the intake link when the reader or canonicalization changes the engine URL. */
    private fun startLegacyInspection(input: LinkRevision, readerOrigin: LinkRevision? = null, noMedia: Boolean = false) {
        // A carousel's explicit View video options starts a new inspection without startInspection().
        val timing = inspectionTiming ?: InspectionTiming().also {
            it.record(InspectionPhase.INTAKE_ACCEPTED)
            inspectionTiming = it
        }
        // Reels/TV bypass the image reader, so canonicalize Instagram at the shared legacy entry too.
        val source = legacyReaderSource(input, MediaSourceRoute.INSTAGRAM)
        val origin = readerOrigin ?: input.takeUnless { it.matches(source) }
        val id = newOperationId()
        operationId = id
        optionsState = OptionsState.Checking(origin ?: source, id)
        if (YouTubeOEmbed.supports(source.link.rawUrl)) {
            earlyPreviewJob = viewModelScope.launch {
                val preview = try { kotlinx.coroutines.withTimeoutOrNull(4_000) { earlyPreview(source.link) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (preview != null && operationId == id && currentSource()?.matches(origin ?: source) == true) {
                    (optionsState as? OptionsState.Checking)?.takeIf { it.operationId == id }?.let {
                        optionsState = it.copy(earlyPreview = preview)
                    }
                }
            }
        }
        inspectionJob = viewModelScope.launch(timing.context()) { showLegacy(readLegacy(source, id), id, source, origin, noMedia) }
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
    private fun showLegacy(read: LegacyRead, id: String, source: LinkRevision, origin: LinkRevision?, noMedia: Boolean) {
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
                val returned = result.inspection
                when {
                    !returned.source.matches(source) -> optionsState = OptionsState.Error(DownloadError.FormatExpired)
                    displayFormats(returned).isEmpty() -> optionsState = OptionsState.Error(DownloadError.NoFormats)
                    else -> {
                        val ready = OptionsState.Ready(returned, origin)
                        optionsState = ready
                        downloads?.let { coordinator -> viewModelScope.launch {
                            val found = coordinator.savedBefore(returned.source.link.normalizedUrl)
                            if (optionsState === ready) savedFormats = found
                        } }
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
                    }
                }
            }
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
        if (!submitted && eligibleFormats.any { it.id == id }) {
            if (selectedId != id) pendingPermissionChoice = null
            selectedId = id; selectedPreset = null
        }
    }
    fun selectPreset(preset: Preset) {
        if (submitted) return
        presetOptions.singleOrNull { it.first == preset }?.let {
            if (selectedId != it.second.id) pendingPermissionChoice = null
            selectedId = it.second.id; selectedPreset = preset
        }
    }
    fun showMoreFormats() { if (optionsState is OptionsState.Ready && !submitted) moreFormats = true }
    fun hideMoreFormats() { moreFormats = false }
    fun dismissOptions() {
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
        old?.let { if (image) images?.cancel(it) else engine.cancel(it) }
        endRace()
        selectedId = null; selectedPreset = null; command = null; submissionId = null; submitted = false; moreFormats = false
        savedFormats = emptyMap()
        optionsState = OptionsState.Hidden
    }
    /** Consume the immutable confirmation exactly once. */
    @Synchronized fun takeConfirmedCommand(): DownloadCommand? = command.also { command = null }
    @Synchronized fun confirmDownload() {
        if (submitted || engineUpdating) return
        val inspected = inspection ?: return
        val current = currentSource() ?: return
        val origin = confirmAgainst ?: return
        if (!engine.isAvailable || inspected.engineVersion != engineVersion() || !origin.matches(current)) {
            failConfirmation(if (!engine.isAvailable) DownloadError.EngineUnavailable else DownloadError.FormatExpired)
            return
        }
        val row = selectedFormat ?: return
        // A resolved watch link is the job's source; the field keeps showing the user's own link.
        val jobSource = if ((optionsState as? OptionsState.Ready)?.resolvedFrom != null) inspected.source else current
        when (val result = DownloadCommand.confirm(UUID.randomUUID().toString(), inspected, jobSource, row.id, "Nezzel")) {
            is ConfirmResult.Confirmed -> {
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
                                if (submissionId == confirmed.jobId) { dismissOptions(); clearField() }
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
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
    fun cancelDownload(id: String) {
        downloads?.let { coordinator ->
            coordinator.requestCancellation(id)
            (coordinatorScope ?: viewModelScope).launch { coordinator.cancel(id) }
        }
    }
    /** The Save this sheet started; outcomes for any other selection are ignored. */
    private var pendingImageSave by mutableStateOf<MediaSelection?>(null)
    private val imageEventChannel = Channel<ImageSaveState>(Channel.UNLIMITED) // Tiny events; none may be lost.
    /** Saved or failed outcomes of this sheet's Save, for the snackbar; kept until the screen collects them. */
    val imageEvents: Flow<ImageSaveState> = imageEventChannel.receiveAsFlow()
    var selectedImageRefs by mutableStateOf<Set<MediaRenditionRef>>(emptySet())
        private set
    var imageSaveProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set
    val imageSaving get() = pendingImageSave != null
    fun toggleImage(ref: MediaRenditionRef) {
        val ready = optionsState as? OptionsState.ImageReady ?: return
        if (imageSaving || ready.photos.none { it.ref == ref }) return
        selectedImageRefs = if (ref in selectedImageRefs) selectedImageRefs - ref else selectedImageRefs + ref
    }
    fun selectAllImages(selected: Boolean) {
        val ready = optionsState as? OptionsState.ImageReady ?: return
        if (!imageSaving) selectedImageRefs = if (selected) ready.photos.map { it.ref }.toSet() else emptySet()
    }
    /** Only a reader's actual video evidence exposes this switch; the existing legacy route performs inspection. */
    fun inspectCarouselVideo() {
        val ready = optionsState as? OptionsState.ImageReady ?: return
        if (!ready.discovery.hasVideo || imageSaving) return
        val source = currentSource()?.takeIf { it.matches(ready.inspection.source) } ?: return
        val route = ImageRoute.of(source.link)
        val video = ready.videoSource
        invalidateOptions()
        if (video == null) startLegacyInspection(legacyReaderSource(source, route), source)
        else if (route == MediaSourceRoute.FACEBOOK && video.revision == source.revision && CANONICAL_WATCH.matches(video.link.rawUrl))
            startLegacyInspection(video, source)
        else optionsState = OptionsState.ImageError(ImageFailure.UNSUPPORTED)
    }
    init { imageSaver?.let { saver -> viewModelScope.launch { saver.outcome.collect { onImageOutcome(it) } } } }
    val imageSaveEnabled get() = (optionsState as? OptionsState.ImageReady)?.let { ready ->
        imageSaver != null && !imageSaving && ready.availability == ImageSaveAvailability.Available &&
            ready.photos.any { it.ref in selectedImageRefs } && currentSource()?.matches(ready.inspection.source) == true
    } == true
    /** Explicit Save of the chosen returned images. No notification permission: nothing is posted. */
    fun saveImage() {
        if (!imageSaveEnabled) return
        val ready = optionsState as? OptionsState.ImageReady ?: return
        val chosen = ready.photos.filter { it.ref in selectedImageRefs }
        val first = chosen.firstOrNull() ?: return
        val metadata = ready.inspection.items.first().metadata
        val title = (metadata.title ?: metadata.uploader)?.let(::shortFileTitle)?.takeIf { it.isNotBlank() } ?: "Nezzel_" +
            java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", java.util.Locale.ROOT))
        val selection = ready.inspection.selection(first.ref) ?: return
        // Preserve single-image outcomes/API. Carousel suffixes use original returned item positions, not tap order.
        if (!ready.isCarousel) {
            if (imageSaver?.save(selection, first.image, title) == true) {
                pendingImageSave = selection; imageSaveProgress = 1 to 1
            }
        } else {
            val requests = chosen.map { photo ->
                val item = ready.inspection.items.single { it.ref == photo.ref.item }
                dev.ahmed.savlino.media.ImageSaveRequest(checkNotNull(ready.inspection.selection(photo.ref)), photo.image,
                    dev.ahmed.savlino.media.indexedImageTitle(title, item.position + 1, photo.image.format.extension))
            }
            if (imageSaver?.saveBatch(requests) == true) {
                pendingImageSave = selection; imageSaveProgress = 1 to requests.size
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
    fun cancelImageSave() { if (imageSaving) imageSaver?.cancel() }
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
        when {
            outcome is ImageSaveState.BatchFinished -> {
                if (showing && outcome.saved.size == outcome.total) { dismissOptions(); clearField() }
                else if (showing) selectedImageRefs = selectedImageRefs - outcome.saved.map { it.selection.renditionRef }.toSet()
                imageEventChannel.trySend(outcome)
            }
            outcome is ImageSaveState.Saved -> { if (showing) { dismissOptions(); clearField() }; imageEventChannel.trySend(outcome) }
            (outcome as ImageSaveState.Failed).image == ImageFailure.CANCELLED -> Unit // Back to the same choice.
            showing -> optionsState = OptionsState.ImageError(outcome.image ?: ImageFailure.STAGING, outcome.statusCode, outcome.storage,
                ready?.directImage == true)
            else -> imageEventChannel.trySend(outcome)
        }
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
        publishPendingLink()
        initializeClipboard?.let { load ->
            viewModelScope.launch {
                gate = load()
                val hasPendingDismissals = pendingClipboardDismissals.isNotEmpty()
                pendingClipboardDismissals.forEach(gate::dismiss)
                pendingClipboardDismissals.clear()
                clipboardReady = true
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
    fun dismissedHashes(): Set<String> = gate.dismissedHashes()
}

private val Preset.audio get() = this == Preset.AUDIO_M4A || this == Preset.AUDIO_MP3
