package dev.ahmed.savlino.engine

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.asContextElement

/** Which result supplied usable options, not whether a speculative process happened to start. */
enum class InspectionProvenance { NPE, YTDLP, OWN, CACHE }

/** Fixed vocabulary only: no source, operation id, title or engine output can enter the journal. */
enum class InspectionPhase(val label: String) {
    INTAKE_ACCEPTED("intake_accepted"), ENGINE_GATE_PASSED("engine_gate_passed"),
    NATIVE_READER_DONE("native_reader_done"), YTDLP_SPAWNED("ytdlp_spawned"),
    JSON_PARSED("json_parsed"), OPTIONS_SHOWN("options_shown"), EARLY_TITLE("early_title"), THUMBNAIL_SHOWN("thumbnail_shown"),
    SHORT_LINK_DONE("short_link_done")
}

/** Coroutine-scoped bridge to the existing NezzelTiming debug journal, including blocking runtime calls. */
class InspectionTiming internal constructor(
    private val enabled: Boolean,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    // Release builds stay silent unless a tester runs `adb shell setprop log.tag.NezzelTiming DEBUG` (phase names and ms only).
    constructor() : this(loggable(), SystemClock::elapsedRealtime, { Log.d(TAG, it); Unit })
    private val start = if (enabled) now() else 0L
    private val recorded = mutableSetOf<InspectionPhase>()
    fun context() = current.asContextElement(this)
    /** Phases are kept even when the journal is silent: the usage count reads which reader ran. */
    @Synchronized fun record(phase: InspectionPhase) {
        if (recorded.add(phase) && enabled) log("${phase.label} ${now() - start}ms")
    }
    @Synchronized fun has(phase: InspectionPhase) = phase in recorded
    companion object {
        private const val TAG = "NezzelTiming"
        private val current = ThreadLocal<InspectionTiming?>()
        /** Debug builds, or a release with `log.tag.NezzelTiming` set to DEBUG. */
        internal fun loggable() = BuildConfig.DEBUG || runCatching { Log.isLoggable(TAG, Log.DEBUG) }.getOrDefault(false)
        fun mark(phase: InspectionPhase) { current.get()?.record(phase) }
    }
}
