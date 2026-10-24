package dev.ahmed.savlino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import dev.ahmed.savlino.ui.theme.isLargeText
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.locale.NezzelFormat
import dev.ahmed.savlino.ui.theme.Space
import java.net.URI

/** One options surface for authorized intake or explicit Check; Download remains separate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsScreen(links: LinkViewModel, language: String, onDismiss: () -> Unit, snackbarHost: @Composable () -> Unit = {}) {
    if (links.optionsState == OptionsState.Hidden) return
    if (links.moreFormats) {
        MoreFormatsScreen(links, language, snackbarHost)
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box {
            OptionsSheetContent(links, language, onDismiss)
            Box(Modifier.align(Alignment.BottomCenter).padding(Space.s)) { snackbarHost() }
        }
    }
}

/** Sheet body, separate from the popup window so it can be rendered and tested in place. */
@Composable
internal fun OptionsSheetContent(links: LinkViewModel, language: String, onDismiss: () -> Unit) {
    androidx.compose.runtime.SideEffect { links.optionsShown() }
    Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)) {
        // A footer (Download or Save bar) brings its own 16dp top padding; the body doesn't add a second one.
        val footer = links.optionsState is OptionsState.Ready || links.optionsState is OptionsState.ImageReady
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
            .padding(start = Space.l, end = Space.l, top = Space.l, bottom = if (footer) 0.dp else Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            // The sheet's drag handle and the body's 16dp already open the sheet; the first heading adds no more.
            if (links.shareActive) SectionTitle(stringResource(R.string.shared_video), first = true)
            else if (links.copiedLink) SectionTitle(stringResource(R.string.clipboard_title), first = true)
            val host = LinkInput.parse(links.text)?.let { URI(it.rawUrl).host }
            // A failed check shows its own error tile; the grey preview skeleton would read as still loading.
            val failed = links.optionsState is OptionsState.Error || links.optionsState is OptionsState.ImageError
            if (host != null && links.optionsState !is OptionsState.ImageReady && !failed) {
                val inspection = (links.optionsState as? OptionsState.Ready)?.inspection
                // A new link starts a fresh header, so a kept preview never carries over to another video.
                key(links.text) {
                    InspectionHeader(inspection, host, links.optionsState is OptionsState.Checking,
                        (links.optionsState as? OptionsState.Checking)?.earlyPreview, onThumbnail = links::thumbnailShown)
                }
            }
            if (links.photosUnavailable) Text(stringResource(R.string.image_photos_unavailable),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
            when (val state = links.optionsState) {
                is OptionsState.Checking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(when {
                        links.engineWaiting -> R.string.engine_getting_ready
                        state.image -> R.string.checking_link
                        links.enginePreparing -> R.string.engine_preparing
                        else -> R.string.checking_qualities
                    }),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(R.string.nothing_downloads), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) }
                }
                is OptionsState.Error -> {
                    InspectionError(state.error, host, links::checkLink, links::pasteAnother, onDismiss,
                        if (!links.canUpdateAndRetry) null else {
                            { TextButton(onClick = { links.updateEngineAndRetry() }, enabled = !links.engineUpdating,
                                modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(if (links.engineUpdating) R.string.engine_updating else R.string.engine_update_retry))
                            } }
                        }, onReport = reportLinkAction(links))
                }
                is OptionsState.Ready -> {
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        val rows = links.presetRows
                        val outside = links.selectedFormat?.takeIf { selected -> rows.none { it.format.id == selected.id } }
                        val tiers = FormatChoices.tierNames(rows.map { it.format } + listOfNotNull(outside))
                        val (videoRows, audioRows) = rows.partition { it.format.hasVideo }
                        val video = videoRows.isNotEmpty() || outside?.hasVideo == true
                        if (video) GroupHeader(stringResource(R.string.video_group), links)
                        // A remembered All qualities pick joins the presets as a plain row, in height order.
                        (videoRows.map { it.format to it } + listOfNotNull(outside?.takeIf { it.hasVideo }?.let { it to null }))
                            .sortedByDescending { FormatChoices.quality(it.first) ?: 0 }.forEach { (format, row) ->
                                if (row != null) PresetRow(links, row, language, tiers)
                                else FormatChoiceRow(links, format, language, tiers, technical = false)
                            }
                        if (audioRows.isNotEmpty() || outside?.hasVideo == false) {
                            GroupHeader(stringResource(R.string.audio_group), links.takeUnless { video })
                            audioRows.forEach { row -> PresetRow(links, row, language, tiers) }
                            outside?.takeUnless { it.hasVideo }?.let { FormatChoiceRow(links, it, language, tiers, technical = false) }
                        }
                    }
                    ConfirmationDetails(links, language)
                }
                is OptionsState.ImageReady -> {
                    ImageOptions(state, host.orEmpty(), language, links.selectedImageRefs, !links.imageSaving,
                        links::toggleImage, links::selectAllImages, links::inspectCarouselVideo)
                    SavesTo()
                }
                is OptionsState.ImageError -> ImageInspectionError(state, links::checkLink, onDismiss, host, links::pasteAnother,
                    onReport = reportLinkAction(links))
                OptionsState.Hidden -> Unit
            }
        }
        if (links.optionsState is OptionsState.Ready) ConfirmationScreen(links, language)
        if (links.optionsState is OptionsState.ImageReady) ImageSaveBar(links, language)
    }
}

@Composable
private fun PresetRow(links: LinkViewModel, row: PresetRowChoice, language: String, tiers: Map<String, FormatChoices.Tier?>) {
    val preset = row.preset
    val format = row.format
    val label = stringResource(when (preset) {
        Preset.BEST -> R.string.preset_best
        Preset.BALANCED -> R.string.preset_balanced
        Preset.SMALL -> R.string.preset_small
        Preset.AUDIO_M4A, Preset.AUDIO_MP3 -> R.string.audio_only
    })
    // Same row as the full list ("Full HD · 1080p"); the preset name leads the hint as a short tag.
    // A merged row keeps every member's last-used state; selection follows the actual format.
    // "Last used" is the amber badge (DESIGN.md), not hint text.
    val last = if (links.lastUsedFormat?.id == format.id) stringResource(R.string.last_used) else null
    FormatRow(formatTitle(format, language, tiers), format, links.roughSizeBytes(format), links.selectedFormat?.id == format.id, !links.confirmed, language,
        // One short hint: the title already names the quality, so the tier's description would only repeat it.
        if (format.hasVideo) label else formatHint(format),
        badge = last) { links.selectPreset(preset) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreFormatsScreen(links: LinkViewModel, language: String, snackbarHost: @Composable () -> Unit = {}) {
    BackHandler(onBack = links::hideMoreFormats)
    val others = links.otherFormats
    // Opens on its own when the current choice is one of them; after that the person decides.
    var othersOpen by rememberSaveable { mutableStateOf(others.any { it.id == links.selectedFormat?.id }) }
    Scaffold(topBar = {
        // The bar is a fixed 64dp and clips an Arabic title at 200%, so the title lives in the scrolling content.
        TopAppBar(title = {}, navigationIcon = {
            IconButton(onClick = links::hideMoreFormats) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        })
    }, bottomBar = { ConfirmationScreen(links, language) }, snackbarHost = snackbarHost) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(Space.l).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(stringResource(R.string.all_formats), Modifier.fillMaxWidth().semantics { heading() },
                style = MaterialTheme.typography.headlineSmall)
            (links.optionsState as? OptionsState.Ready)?.inspection?.let { inspection ->
                inspection.title?.let { title ->
                    Text(title, style = MaterialTheme.typography.titleSmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content),
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                LinkInput.parse(links.text)?.let { link ->
                    Text(NezzelFormat.isolate(URI(link.rawUrl).host.orEmpty()),
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FormatList(links, links.primaryFormats, language, technical = false)
            if (others.isNotEmpty()) {
                TextButton(onClick = { othersOpen = !othersOpen }, enabled = !links.confirmed || othersOpen,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (othersOpen) stringResource(R.string.other_versions_hide)
                        else stringResource(R.string.other_versions, NezzelFormat.count(others.size, language)))
                    Spacer(Modifier.weight(1f))
                    Icon(if (othersOpen) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, contentDescription = null)
                }
                if (othersOpen) FormatList(links, others, language, technical = true)
            }
            ConfirmationDetails(links, language)
        }
    }
}

/** Video then audio rows. Other versions add their file type and codec or audio rate as one small line. */
@Composable
private fun FormatList(links: LinkViewModel, formats: List<Format>, language: String, technical: Boolean) {
    // Other versions repeat a quality on purpose (one per codec); the plain list never repeats a tier name.
    val tiers = if (technical) null else FormatChoices.tierNames(formats)
    listOf(formats.filter { it.hasVideo } to R.string.video_group, formats.filter { !it.hasVideo } to R.string.audio_group)
        .filter { it.first.isNotEmpty() }.forEach { (rows, title) ->
            SectionTitle(stringResource(title))
            rows.forEach { format ->
                FormatChoiceRow(links, format, language, tiers, technical)
            }
        }
}

@Composable
private fun FormatChoiceRow(links: LinkViewModel, format: Format, language: String,
    tiers: Map<String, FormatChoices.Tier?>?, technical: Boolean) {
    FormatRow(formatTitle(format, language, tiers), format, links.roughSizeBytes(format), links.selectedFormat?.id == format.id,
        !links.confirmed, language, formatHint(format), if (technical) moreFormatDetail(format, language) else null,
        badge = if (links.lastUsedFormat?.id == format.id) stringResource(R.string.last_used) else null) {
        links.selectFormat(format.id)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FormatRow(label: String, format: Format, roughSizeBytes: Long?, selected: Boolean, enabled: Boolean, language: String,
    hint: String, details: String? = null, badge: String? = null, onSelect: () -> Unit) {
    val unknownSize = format.size == Size.Unknown && roughSizeBytes == null
    val size = sizeLabel(format, language, roughSizeBytes)
    val sizeSpoken = spokenSize(format, language, roughSizeBytes)
    val sizeStyle = if (unknownSize) MaterialTheme.typography.bodySmall else MaterialTheme.typography.titleSmall
    val titleStyle = MaterialTheme.typography.titleMedium
    val sizeText = @Composable { modifier: Modifier ->
        Text(size, modifier, style = sizeStyle,
            color = if (unknownSize) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val spokenRow = listOfNotNull(label, badge, hint.ifEmpty { null }, details, sizeSpoken).joinToString(stringResource(R.string.spoken_separator))
    // Only the chosen quality is a container; the rest sit flat on the sheet.
    Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            // One spoken line for the row, so TalkBack says the size as a word ("About 4.7 MB, estimated"), never "≈".
            .semantics { contentDescription = spokenRow }) { BoxWithConstraints {
        // Stacks by measured width, not font scale: when the title (up to ~10em) no longer fits on one line beside
        // the radio and an end-aligned size, every fact takes the full card width and the row grows instead.
        val stacked = with(density) {
            val chrome = Space.l * 2 + 24.dp + Space.m * 2 // padding, the 24dp radio, the two gaps
            val sizeWidth = if (unknownSize) 0.dp else measurer.measure(size, sizeStyle, maxLines = 1).size.width.toDp()
            val titleNeed = minOf(measurer.measure(label, titleStyle, maxLines = 1).size.width.toDp(), titleStyle.fontSize.toDp() * 10)
            maxWidth - chrome - sizeWidth < titleNeed
        }
        if (stacked) {
            Column(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    RadioButton(selected, onClick = null, enabled = enabled)
                    Text(label, Modifier.weight(1f), style = titleStyle)
                }
                badge?.let { LastUsedBadge(it) }
                RowFacts(hint, details)
                sizeText(Modifier.fillMaxWidth())
            }
        } else Row(Modifier.padding(horizontal = Space.l, vertical = Space.s), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            RadioButton(selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.textPair)) {
                if (badge == null) Text(label, style = titleStyle)
                // The badge sits beside the title only when both fit whole; otherwise it wraps under the title.
                else FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = titleStyle)
                    LastUsedBadge(badge)
                }
                RowFacts(hint, details)
                if (unknownSize) sizeText(Modifier)
            }
            if (!unknownSize) sizeText(Modifier)
        }
    } }
}

/** DESIGN.md: amber marks "Last used" only (tertiaryContainer pill, labelMedium). */
@Composable
private fun LastUsedBadge(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.testTag("last_used_badge")) {
        Text(text, Modifier.padding(horizontal = Space.s, vertical = Space.xxs), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RowFacts(hint: String, details: String?) {
    if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (details != null) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * A group heading; the first one carries the way to every quality, so it is on screen as soon as the sheet opens
 * instead of below the last row. Large text stacks it under the heading.
 */
@Composable
private fun GroupHeader(title: String, links: LinkViewModel?) {
    if (links == null) return SectionTitle(title)
    val all: @Composable () -> Unit = {
        TextButton(onClick = links::showMoreFormats, enabled = !links.confirmed, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.all_formats))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
    if (isLargeText()) { SectionTitle(title); all(); return }
    Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
        all()
    }
}

@Composable
private fun SectionTitle(title: String, first: Boolean = false) {
    Text(title, Modifier.fillMaxWidth().padding(top = if (first) 0.dp else Space.m).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
}

/** Plain name people know: "Full HD · 1080p", "Audio only", "MP3". */
@Composable
private fun formatTitle(format: Format, language: String, tiers: Map<String, FormatChoices.Tier?>? = null): String {
    return when {
        format.animatedGif -> stringResource(R.string.video_animated_gif)
        format.hasVideo && FormatChoices.quality(format) == null -> stringResource(R.string.video_unknown_quality)
        format.hasVideo -> {
            val quality = requireNotNull(FormatChoices.quality(format))
            val resolution = stringResource(R.string.video_quality, NezzelFormat.count(quality, language))
            val tier = if (tiers != null && format.id in tiers) tiers[format.id] else FormatChoices.tier(quality)
            // Each token keeps its own direction, so an Arabic tier name and "540p" never swap places.
            if (tier == null) resolution
            else stringResource(R.string.quality_title, NezzelFormat.isolate(stringResource(tier.title)), NezzelFormat.isolate(resolution))
        }
        format.conversion != null -> stringResource(R.string.mp3_title)
        else -> stringResource(R.string.audio_only_type, NezzelFormat.isolate(format.container.uppercase(java.util.Locale.ROOT)))
    }
}

@Composable
private fun formatHint(format: Format): String {
    val resource = when {
        format.animatedGif -> R.string.video_animated_gif_hint
        format.hasVideo -> FormatChoices.quality(format)?.let { FormatChoices.tier(it).hint } ?: R.string.video_unknown_quality_hint
        format.conversion != null -> R.string.mp3_hint
        else -> return ""
    }
    return stringResource(resource)
}

/** Actual identifiers and codec/rate tokens stay LTR inside localized descriptions. */
@Composable
private fun moreFormatDetail(format: Format, language: String): String = listOfNotNull(
    // File type and a short codec name or audio rate tell versions apart; ids, merge partners and video rates stay out (owner 2026-10-21).
    NezzelFormat.isolate(format.container.uppercase(java.util.Locale.ROOT)),
    FormatChoices.codecName(format)?.let(NezzelFormat::isolate),
    FormatChoices.audioKbps(format)?.let { stringResource(R.string.format_kbps, NezzelFormat.isolate(NezzelFormat.count(it, language))) },
).joinToString(stringResource(R.string.separator))

/** Truthful copy per failure type; Try again only where a retry can succeed. */
@Composable
private fun InspectionError(error: DownloadError, host: String?, onRetry: () -> Unit, onPasteAnother: (String?) -> Unit,
    onDismiss: () -> Unit, secondary: (@Composable () -> Unit)? = null, onReport: (() -> Unit)? = null) {
    val (title, body) = when (error) {
        DownloadError.EngineUnavailable -> R.string.unavailable_title to R.string.unavailable_body
        DownloadError.Unsupported -> R.string.unsupported_title to R.string.unsupported_body
        DownloadError.LoginRequired -> R.string.private_title to R.string.private_body
        DownloadError.SourceRefused -> R.string.source_refused_title to R.string.source_refused_body
        DownloadError.DrmProtected -> R.string.protected_title to R.string.protected_body
        DownloadError.NoFormats -> R.string.no_formats_title to R.string.no_formats_body
        DownloadError.NoVideo -> R.string.no_video_title to R.string.no_video_body
        DownloadError.Unavailable -> R.string.video_gone_title to R.string.video_gone_body
        DownloadError.NotAPost -> R.string.not_a_post_title to R.string.not_a_post_body
        DownloadError.Offline -> R.string.offline_title to R.string.offline_body
        // Online but the request failed or timed out: not "No internet connection".
        DownloadError.Network -> R.string.network_title to R.string.network_body
        DownloadError.FormatExpired -> R.string.expired_title to R.string.expired_body
        else -> R.string.read_failed_title to R.string.read_failed_body
    }
    val retry = error in RETRYABLE_INSPECTION
    // Every link fails the same way while the engine is unavailable, so another link is no way out.
    FailureContent(title, body, host, onRetry.takeIf { retry },
        onPasteAnother.takeIf { error != DownloadError.EngineUnavailable }, onDismiss, secondary, onReport)
}

private val RETRYABLE_INSPECTION = setOf(DownloadError.EngineUnavailable, DownloadError.Offline, DownloadError.Network, DownloadError.FormatExpired,
    DownloadError.Failed, DownloadError.ExecutionRestricted)
