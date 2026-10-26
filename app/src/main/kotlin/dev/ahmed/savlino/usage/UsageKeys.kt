package dev.ahmed.savlino.usage

import dev.ahmed.savlino.domain.Format
import dev.ahmed.savlino.domain.VideoQuality
import dev.ahmed.savlino.report.Report

/**
 * The one table of what a usage ping may count. The private inbox mirrors it (nezzel-worker `src/usage.js`): change both
 * together. Keys name a feature, setting or bucket only; no ID, link, title or file name can form one.
 */
object UsageKeys {
    const val OPEN = "open"
    const val ACTIVE = "active"
    const val SHARE = "share"
    const val PASTE = "paste"
    const val CLIP_SHOWN = "clip.shown"
    const val CLIP_ACCEPTED = "clip.accepted"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val PAUSE_ALL = "pause_all"
    const val RETRY = "retry"
    const val STATUSES_OPENED = "statuses.opened"
    const val STATUSES_SAVED = "statuses.saved"
    /** A Download/Nezzel grant after a reinstall; no count of files or names. */
    const val OLD_DOWNLOADS_FOUND = "old_downloads.found"
    const val WIDGET_ADDED = "widget.added"
    const val UPDATE_INSTALLED = "update.installed"
    /** Sites past the local cap of distinct sites between sends. */
    const val SITE_OTHER = "site.other"
    const val FUNNEL_LINK = "funnel.link"
    const val FUNNEL_OPTIONS = "funnel.options"
    const val FUNNEL_TAPPED = "funnel.tapped"
    const val FUNNEL_FINISHED = "funnel.finished"
    const val FUNNEL_FAILED = "funnel.failed"
    /** A confirmed video link against the quality Nezzel had picked: kept it, went higher, lower, or took audio. */
    const val PICK_KEPT = "pick.kept"
    const val PICK_UP = "pick.up"
    const val PICK_DOWN = "pick.down"
    const val PICK_AUDIO = "pick.audio"
    /** 0.5.1 player, Statuses and sharing: one count per explicit action, never per frame, timer or restore. */
    const val PLAYER_OPEN_VIDEO = "player.open.video"
    const val PLAYER_OPEN_AUDIO = "player.open.audio"
    const val PLAYER_SEEK = "player.seek"
    const val PLAYER_SPEED = "player.speed"
    const val PLAYER_FULLSCREEN = "player.fullscreen"
    const val PLAYER_BACKGROUND = "player.background"
    const val PLAYER_FAILED = "player.failed"
    const val STATUSES_READY = "statuses.ready"
    const val STATUSES_SAVE_FAILED = "statuses.save_failed"
    /** The WhatsApp app opened with the file; not a sent message or posted status. */
    const val SHARE_WHATSAPP = "share.whatsapp"

    enum class Download { STARTED, FINISHED, FAILED, CANCELLED }
    /** Which reader produced the options shown; CACHE = a recent inspection reused, no reader ran. */
    enum class Path(val key: String) { NPE("npe"), YTDLP("ytdlp"), OWN("own"), CACHE("cache") }

    private val events = Download.entries.map { it.name.lowercase() }
    private val tiers = mapOf("video" to listOf("low", "sd", "hd", "fhd", "qhd", "uhd", "unknown"),
        "audio" to listOf("mp3", "original"), "photo" to listOf("any"))
    private val optionTimes = listOf("t0-2", "t2-5", "t5-10", "t10up")
    private val speeds = listOf("s0-100k", "s100k-1m", "s1m-5m", "s5mup")

    /** Every fixed key; [SITE], [SDK] and [BRAND] are the only patterned ones. */
    val fixed: Set<String> = buildSet {
        addAll(listOf(OPEN, ACTIVE, SHARE, PASTE, CLIP_SHOWN, CLIP_ACCEPTED, PAUSE, RESUME, PAUSE_ALL, RETRY,
            STATUSES_OPENED, STATUSES_SAVED, OLD_DOWNLOADS_FOUND, WIDGET_ADDED, UPDATE_INSTALLED, SITE_OTHER,
            FUNNEL_LINK, FUNNEL_OPTIONS, FUNNEL_TAPPED, FUNNEL_FINISHED, FUNNEL_FAILED, PICK_KEPT, PICK_UP, PICK_DOWN, PICK_AUDIO,
            PLAYER_OPEN_VIDEO, PLAYER_OPEN_AUDIO, PLAYER_SEEK, PLAYER_SPEED, PLAYER_FULLSCREEN, PLAYER_BACKGROUND, PLAYER_FAILED,
            STATUSES_READY, STATUSES_SAVE_FAILED, SHARE_WHATSAPP))
        for (event in events) for ((kind, names) in tiers) for (tier in names) add("dl.$event.$kind.$tier")
        for (path in Path.entries) for (time in optionTimes) add("options.${path.key}.$time")
        addAll(speeds.map { "speed.$it" })
        addAll(listOf("auto", "ar", "en", "fr").map { "set.lang.$it" })
        addAll(listOf("system", "light", "dark").map { "set.theme.$it" })
        addAll(listOf("on", "off").flatMap { listOf("set.autoload.$it", "set.wallpaper.$it") })
        addAll((1..3).map { "set.parallel.$it" })
        addAll(listOf("ar", "en", "fr").map { "lang.$it" })
        addAll(listOf("day0", "d1-7", "d8-30", "d31up").map { "age.$it" })
        addAll(listOf("compact", "medium", "expanded").map { "screen.$it" })
    }

    const val SITE = "site:"
    const val SDK = "sdk:"
    const val BRAND = "brand:"
    private val domain = Regex("[a-z0-9-]{1,63}(\\.[a-z0-9-]{1,63})+")
    private val brandName = Regex("[a-z0-9]{1,24}")

    fun allowed(key: String): Boolean = key in fixed ||
        key.startsWith(SITE) && key.length <= SITE.length + 64 && domain.matches(key.removePrefix(SITE)) ||
        key.startsWith(SDK) && key.removePrefix(SDK).toIntOrNull()?.takeIf { it in 21..99 }?.toString() == key.removePrefix(SDK) ||
        key.startsWith(BRAND) && brandName.matches(key.removePrefix(BRAND))

    /** The registrable name only (the same one a report carries); null for anything that is not a web link. */
    fun site(link: String?): String? = link?.let(Report::site)?.let { SITE + it }?.takeIf(::allowed)

    /** Build.MANUFACTURER, lowercased letters and digits only; never the model. */
    fun brand(manufacturer: String?): String {
        val name = manufacturer.orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(24)
        return BRAND + name.ifEmpty { "other" }
    }

    fun download(event: Download, format: Format): String {
        val kind = when { format.hasVideo -> "video"; format.hasAudio -> "audio"; else -> "photo" }
        val tier = when (kind) {
            "video" -> when (VideoQuality.of(format.width, format.height) ?: 0) {
                in 1..479 -> "low"; in 480..719 -> "sd"; in 720..1079 -> "hd"
                in 1080..1439 -> "fhd"; in 1440..2159 -> "qhd"; in 2160..Int.MAX_VALUE -> "uhd"; else -> "unknown"
            }
            "audio" -> if (format.container == "mp3") "mp3" else "original"
            else -> "any"
        }
        return "dl.${event.name.lowercase()}.$kind.$tier"
    }

    /** Null when Nezzel had picked no video row (audio-only links, a lone row, the better-quality flow). */
    fun pick(preselected: Format?, chosen: Format): String? {
        if (preselected == null || !preselected.hasVideo) return null
        if (!chosen.hasVideo) return PICK_AUDIO
        if (chosen.id == preselected.id) return PICK_KEPT
        val from = VideoQuality.of(preselected.width, preselected.height) ?: 0
        val to = VideoQuality.of(chosen.width, chosen.height) ?: 0
        return if (to > from) PICK_UP else if (to < from) PICK_DOWN else PICK_KEPT
    }

    fun photo(event: Download) = "dl.${event.name.lowercase()}.photo.any"

    fun options(path: Path, millis: Long) = "options.${path.key}." + when {
        millis < 2_000 -> "t0-2"; millis < 5_000 -> "t2-5"; millis < 10_000 -> "t5-10"; else -> "t10up"
    }

    fun speed(bytesPerSecond: Double) = "speed." + when {
        bytesPerSecond < 100_000 -> "s0-100k"; bytesPerSecond < 1_000_000 -> "s100k-1m"
        bytesPerSecond < 5_000_000 -> "s1m-5m"; else -> "s5mup"
    }

    fun age(days: Long) = "age." + when { days < 1 -> "day0"; days <= 7 -> "d1-7"; days <= 30 -> "d8-30"; else -> "d31up" }

    fun screen(smallestWidthDp: Int) = "screen." + when { smallestWidthDp < 600 -> "compact"; smallestWidthDp < 840 -> "medium"; else -> "expanded" }
}
