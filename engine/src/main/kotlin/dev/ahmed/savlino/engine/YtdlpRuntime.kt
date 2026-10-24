package dev.ahmed.savlino.engine

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dev.ahmed.savlino.domain.DownloadError
import dev.ahmed.savlino.domain.DownloadFailureCause
import java.io.File
import java.io.PushbackInputStream
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Caches only a successful native install. A failure is retried after [backoffMs] or on an explicit user
 * Retry ([requestRetry]); the runtime first clears what a failed/killed extraction left behind.
 * No retries or updates on intake beyond local initialization.
 */
internal class EngineInitialization(private val nanoTime: () -> Long = System::nanoTime,
    private val backoffMs: Long = INIT_RETRY_BACKOFF_MS) {
    @Volatile private var ready = false
    @Volatile private var failedAt: Long? = null
    @Volatile private var retryRequested = false
    // Writes stay under the exclusive native gate or initialization; cached reads never wait for a transfer.
    @Volatile var installedVersion: String? = null
    val versionGate = kotlinx.coroutines.sync.Mutex()
    private val preparingState = MutableStateFlow(false)
    /** True only while a first (or re-)extraction of the bundled engine runs. */
    val preparing: StateFlow<Boolean> = preparingState.asStateFlow()
    val isAvailable: Boolean get() = ready || retryAllowed()
    private fun retryAllowed(): Boolean {
        val failed = failedAt ?: return true
        return retryRequested || nanoTime() - failed >= backoffMs * 1_000_000
    }
    /** User Retry: the next ensure attempts initialization again, without waiting for the backoff. */
    fun requestRetry() { if (!ready && failedAt != null) retryRequested = true }
    @Synchronized fun ensure(reset: () -> Unit = {}, needsExtraction: () -> Boolean = { false },
        initialize: () -> Unit): Boolean {
        if (ready) return true
        if (!retryAllowed()) return false
        val retry = failedAt != null
        retryRequested = false
        val succeeded = try {
            // A previous attempt can leave a partial extraction that the wrapper would treat as complete.
            if (retry) reset()
            preparingState.value = try { needsExtraction() } catch (_: Exception) { false }
            initialize(); true
        } catch (_: Exception) { false } catch (_: LinkageError) { false }
        finally { preparingState.value = false }
        if (succeeded) { ready = true; failedAt = null } else failedAt = nanoTime()
        return succeeded
    }
    companion object { const val INIT_RETRY_BACKOFF_MS = 30_000L }
}

/** Boundary for blocking native operations; test doubles never ship as app bindings. */
internal interface YtdlpRuntime {
    val initialization: EngineInitialization
    fun initialize()
    /** Whether [initialize] has to unpack the bundled engine (first run or a new bundle). */
    fun needsExtraction(): Boolean = false
    /** Before a retried [initialize]: delete only what an incomplete initialization left behind. */
    fun resetPartialInstall() = Unit
    fun execute(args: List<String>, processId: String, line: (String) -> Unit): String
    /** Best-effort local container polish. Never sees inspection JSON or source URLs. */
    fun rewriteTitle(file: File, title: String, processId: String) = Unit
    fun cancelTitleRewrite(processId: String): Boolean = false
    fun observe(processId: String) = Unit
    fun destroy(processId: String): Boolean
    fun update()
    /** Network-only first update stage; touches no installed file. The default has none: [installUpdate] runs [update]. */
    suspend fun prepareUpdate(): PreparedUpdate = PreparedUpdate.Unstaged
    /** Local second stage under the exclusive gate: install a staged release, or run the whole [update]. */
    fun installUpdate(prepared: PreparedUpdate) { update() }
    /** Bundled zip size when an update version is recorded; null when no reconciliation is needed. */
    fun bundledYtdlpSize(): Long? = null
    /** Expected size of the plain copy (bundled, or our recorded install); null protects an unknown update from repair. */
    fun bundledYtdlpSizeForRepair(): Long? = null
    fun clearUpdatedYtdlpVersion() = Unit
    /** Successful local probe for this installed binary; no process or network needed to read it. */
    fun warmedVersion(): String? = null
    fun rememberWarmedVersion(version: String) = Unit
    /** Drop an updated yt-dlp and restore the bundled one. Never touches the network. */
    fun restoreBundledYtdlp() = Unit
}

/** Tracks native execute calls; the native boundary is replaceable by offline process-table tests. */
internal class AndroidYtdlpRuntime(
    private val native: YtdlpRuntime,
    private val newTree: (List<String>) -> ProcessTree,
    private val nanoTime: () -> Long = System::nanoTime,
    private val pause: (Long) -> Unit = Thread::sleep,
) : YtdlpRuntime by native {
    constructor(context: Context) : this(DirectoryYtdlpRuntime(YoutubeDlNative(context),
        File(context.applicationContext.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}"),
        enabled = true), { args ->
        val app = context.applicationContext
        val parent = android.os.Process.myPid()
        val python = File(app.applicationInfo.nativeLibraryDir, "libpython.so").absolutePath
        val script = File(app.noBackupFilesDir, "${YoutubeDL.baseName}/${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}").absolutePath
        ProcessTree(FileProcTable()) { row -> matchesYtdlpChild(row, parent, python, script, args) }
    })
    private data class Execution(val tree: ProcessTree, val registeredAt: Long)
    private val executions = ConcurrentHashMap<String, Execution>()
    override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
        val execution = Execution(newTree(args), nanoTime())
        executions[processId] = execution // Register before fork; cancel keeps retrying until /proc exposes PID.
        val transfer = args.contains("--no-simulate")
        val diagnostics = YtdlpDiagnostics()
        try {
            return native.execute(args, processId) { output ->
                diagnostics.accept(output); line(output) // The canceller polls /proc independently of output volume.
            }
        } catch (error: Exception) {
            // With merged stderr the wrapper throws an empty YoutubeDLException on nonzero exit.
            // Preserve its typed diagnostic without retaining URLs/output in a cause or message.
            if (transfer) throw diagnostics.failure() else throw error
        } finally {
            try {
                // Python may exit just before cancellation polls, leaving ffmpeg reparented/alive.
                // Never replace the native result/failure if a final best-effort signal is denied.
                try { execution.tree.terminate(includeRoot = false) { pause(150) } }
                catch (_: Exception) { /* Silent cleanup; no native diagnostics or URLs escape. */ }
            } finally { executions.remove(processId, execution) }
        }
    }
    override fun observe(processId: String) { executions[processId]?.tree?.observe() }
    override fun destroy(processId: String): Boolean {
        val execution = executions[processId] ?: return native.cancelTitleRewrite(processId)
        val terminated = try {
            execution.tree.terminate { pause(150) } // Adapter joins escalation before returning.
        } catch (_: Exception) {
            return native.destroy(processId) // /proc visibility/signalling must not disable cancellation.
        }
        // The pinned argv match still needs Android proof. Keep the wrapper's process-map fallback
        // after a matched tree kill, or after a bounded registration/discovery window without a match.
        if (terminated || nanoTime() - execution.registeredAt >= 300_000_000) {
            val fallback = native.destroy(processId)
            return terminated || fallback
        }
        return false
    }
}

/** The pinned wrapper remains responsible for native initialization, launch and logical-ID lookup. */
private class YoutubeDlNative(context: Context) : YtdlpRuntime {
    private val app = context.applicationContext
    private val base get() = File(app.noBackupFilesDir, YoutubeDL.baseName)
    private val prefs get() = app.getSharedPreferences(YtdlpRelease.PREFS, Context.MODE_PRIVATE)
    private val titleFfmpeg = TitleFfmpegProcess { args ->
        ProcessBuilder(listOf(File(app.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath) + args)
            .apply {
                // Pinned 0.18.1 FFmpeg.init unpacks the libraries here; no new executable or dependency.
                // Same order as YoutubeDL.execute: librubberband needs the python package's libc++_shared.so.
                environment()["LD_LIBRARY_PATH"] = listOf("python", "ffmpeg")
                    .joinToString(":") { File(base, "packages/$it/usr/lib").absolutePath }
                environment()["TMPDIR"] = app.cacheDir.absolutePath
                redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            }.start()
    }
    // 0.18.1 keeps `initialized` private per wrapper; track which half completed so a retry never
    // deletes files the already-initialized half is using.
    @Volatile private var youtubeDlReady = false
    @Volatile private var ffmpegReady = false
    override val initialization get() = sharedInitialization
    override fun initialize() {
        installer.sweep()
        YoutubeDL.init(app); youtubeDlReady = true
        FFmpeg.init(app); ffmpegReady = true
    }
    /** Mirrors 0.18.1 initPython/initFFmpeg: unpack when the dir is missing or the recorded zip size differs. */
    override fun needsExtraction(): Boolean {
        val libs = File(app.applicationInfo.nativeLibraryDir)
        fun stale(dir: String, zip: String, key: String) = !File(base, "packages/$dir").exists() ||
            prefs.getString(key, null) != File(libs, zip).length().toString()
        return stale("python", "libpython.zip.so", "pythonLibVersion") || stale("ffmpeg", "libffmpeg.zip.so", "ffmpegLibVersion") ||
            !File(base, "${YoutubeDL.ytdlpDirName}/${YoutubeDL.ytdlpBin}").exists()
    }
    override fun resetPartialInstall() {
        if (!youtubeDlReady) {
            File(base, "packages/python").deleteRecursively()
            prefs.edit().remove("pythonLibVersion").commit()
            // A kill mid-copy leaves a truncated bundled yt-dlp that init_ytdlp keeps. Only the bundled
            // copy (no recorded update) is wiped; it is re-extracted from res/raw offline.
            if (prefs.getString(DLP_VERSION, null) == null) File(base, YoutubeDL.ytdlpDirName).deleteRecursively()
        }
        if (!ffmpegReady) {
            File(base, "packages/ffmpeg").deleteRecursively()
            prefs.edit().remove("ffmpegLibVersion").commit()
        }
    }
    override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
        val diagnostics = YtdlpDebugDiagnostics(args = args)
        val transfer = args.contains("--no-simulate")
        try {
            if ("--dump-json" in args) InspectionTiming.mark(InspectionPhase.YTDLP_SPAWNED)
            // Transfer output is merged by the wrapper; metadata stdout must stay JSON-only.
            val response = YoutubeDL.execute(YoutubeDLRequest(emptyList()).addCommands(args), processId = processId,
                redirectErrorStream = transfer, callback = { _, _, output ->
                    if (transfer) diagnostics.accept(output)
                    line(output)
                })
            diagnostics.finished(response.exitCode, response.err)
            // Metadata stdout stays JSON-only, but successful empty inspections can explain themselves in stderr.
            if (!transfer) response.err.lineSequence().forEach(line)
            return response.out
        } catch (error: Exception) {
            // 0.18.1 throws without exposing the failed process exit code. Never infer a code.
            diagnostics.finished(null, if (transfer) "" else error.message.orEmpty())
            throw error
        }
    }
    override fun rewriteTitle(file: File, title: String, processId: String) {
        Mp4TitleRewriter(readTitle = { input ->
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(input.absolutePath)
                retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
            } finally { retriever.release() }
        }, ffmpeg = { args -> titleFfmpeg.execute(args, processId) }).rewrite(file, title)
    }
    override fun cancelTitleRewrite(processId: String) = titleFfmpeg.cancel(processId)
    override fun destroy(processId: String) = YoutubeDL.getInstance().destroyProcessById(processId)
    override fun update() {
        prefs.edit().remove(YtdlpStagedInstall.SIZE_KEY).commit() // The library's own copy records no size.
        YoutubeDL.updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
    }
    private val releaseClient by lazy { YtdlpReleaseFetcher.client(EngineLimits().fetchMs) }
    override suspend fun prepareUpdate(): PreparedUpdate =
        YtdlpReleaseFetcher(releaseClient, app.cacheDir, { prefs.getString(DLP_VERSION, null) }).fetch()
    private val installer get() = YtdlpStagedInstall(File(base, YoutubeDL.ytdlpDirName), YoutubeDL.ytdlpBin, ::update,
        { staged, size -> prefs.edit().putString(DLP_VERSION, staged.tag).putString(YtdlpRelease.VERSION_NAME_KEY, staged.name)
            .putLong(YtdlpStagedInstall.SIZE_KEY, size).commit() }, ::restoreBundledYtdlp)
    /** The local half of 0.18.1 `YoutubeDLUpdater.update` (replace the binary, restore bundled on failure, then prefs). */
    override fun installUpdate(prepared: PreparedUpdate) = installer.install(prepared)
    override fun bundledYtdlpSize(): Long? = if (prefs.getString(DLP_VERSION, null) == null) null else
        bundledSize()
    override fun bundledYtdlpSizeForRepair(): Long? = YtdlpStagedInstall.expectedSize(prefs.getString(DLP_VERSION, null),
        prefs.getLong(YtdlpStagedInstall.SIZE_KEY, -1).takeIf { it >= 0 }, ::bundledSize)
    private fun bundledSize(): Long =
        app.resources.openRawResource(com.yausername.youtubedl_android.R.raw.ytdlp).use { it.available().toLong() }
    override fun clearUpdatedYtdlpVersion() {
        prefs.edit().remove(DLP_VERSION).remove(YtdlpRelease.VERSION_NAME_KEY).remove(YtdlpStagedInstall.SIZE_KEY).commit()
    }
    private val warmPrefs get() = app.getSharedPreferences("engine-warm-up", Context.MODE_PRIVATE)
    // The stamp also distinguishes a bundled restore/re-extraction of a previously warmed version.
    private fun installedKey(): String {
        return listOf(prefs.getString(DLP_VERSION, "bundled-0.18.1"),
            ytdlpInstallationStamp(File(base, YoutubeDL.ytdlpDirName)),
            prefs.getString("pythonLibVersion", null), prefs.getString("ffmpegLibVersion", null)).joinToString(":")
    }
    override fun warmedVersion(): String? = warmPrefs.getString("version", null)
        ?.takeIf { warmPrefs.getString("installed", null) == installedKey() }
    override fun rememberWarmedVersion(version: String) {
        warmPrefs.edit().putString("installed", installedKey()).putString("version", version).commit()
    }
    /** 0.18.1 YoutubeDLUpdater keeps its tag in prefs; init_ytdlp re-copies res/raw/ytdlp when the file is gone. */
    override fun restoreBundledYtdlp() {
        val dir = File(base, YoutubeDL.ytdlpDirName)
        dir.deleteRecursively()
        prefs.edit().remove(DLP_VERSION).remove(YtdlpRelease.VERSION_NAME_KEY).remove(YtdlpStagedInstall.SIZE_KEY).commit()
        YoutubeDL.getInstance().init_ytdlp(app, dir)
    }
    private companion object {
        val sharedInitialization = EngineInitialization()
        const val DLP_VERSION = YtdlpRelease.VERSION_KEY
    }
}

internal fun matchesYtdlpChild(row: ProcProcess, parent: Int, python: String, script: String,
    args: List<String>): Boolean = row.parentPid == parent && row.command.size >= args.size + 2 &&
    row.command.take(2) == listOf(python, script) && row.command.takeLast(args.size) == args

internal class YtdlpExecutionFailure(val error: DownloadError, val expiredUrl: Boolean = false,
    val resumeAllowed: Boolean = true, val failureCause: DownloadFailureCause = error.cause,
    val reextractionBlocked: Boolean = false) : Exception()
internal class YtdlpDiagnostics {
    private val error = AtomicReference(DownloadError.Failed)
    private val cause = AtomicReference(DownloadFailureCause.OTHER)
    private val expired = java.util.concurrent.atomic.AtomicBoolean()
    private val httpFailure = java.util.concurrent.atomic.AtomicBoolean()
    private val reextractionBlocked = java.util.concurrent.atomic.AtomicBoolean()
    fun accept(line: String) {
        if (expiredUrl(line)) expired.set(true)
        if (blocksResume(line)) httpFailure.set(true)
        // Inspect each diagnostic independently: a later 403 or generic failure must never erase
        // an earlier transport/storage/post-processing/cancellation signal. Retain booleans only.
        line.lineSequence().forEach { diagnostic ->
            val normalized = when {
                diagnostic.startsWith("[download] Got error:") -> "ERROR:" + diagnostic.substringAfter("[download] Got error:")
                diagnostic.startsWith("WARNING:") -> "ERROR:" + diagnostic.removePrefix("WARNING:")
                else -> diagnostic
            }
            if (normalized.startsWith("ERROR:")) {
                val safe = normalized.replace(URL, "").lowercase(java.util.Locale.ROOT)
                if (HTTP_NO_RESUME.containsMatchIn(safe) || EXCLUDED_SIGNAL.containsMatchIn(safe) ||
                    YtdlpErrorMapper.map(normalized) in EXCLUDED_ERRORS) reextractionBlocked.set(true)
            }
            if (POSTPROCESSING.any(diagnostic::startsWith)) reextractionBlocked.set(true)
        }
        // Retry warnings are not terminal ERROR lines. Retain only the HTTP class, never their URL/message.
        if (line.lineSequence().filter { it.startsWith("[download] Got error:") }
                .any { HTTP_NO_RESUME.containsMatchIn(it.replace(URL, "").lowercase(java.util.Locale.ROOT)) }) {
            httpFailure.set(true)
            remember(DownloadError.Failed, DownloadFailureCause.HTTP)
        }
        if (line.lineSequence().any { it.startsWith("ERROR:") })
            remember(YtdlpErrorMapper.map(line), YtdlpErrorMapper.cause(line))
    }
    private fun remember(mappedError: DownloadError, mappedCause: DownloadFailureCause) {
        cause.updateAndGet { previous ->
            if (previous == DownloadFailureCause.HTTP || mappedCause == DownloadFailureCause.OTHER)
                previous else mappedCause
        }
        if (mappedError != DownloadError.Failed) error.set(mappedError)
    }
    /** The sole diagnostic precedence rule, shared by callback, native and adapter failures. */
    fun failure(fallback: Exception? = null): YtdlpExecutionFailure {
        if (fallback != null) {
            accept(fallback.message.orEmpty())
            if (fallback is InterruptedException || fallback is kotlinx.coroutines.CancellationException)
                reextractionBlocked.set(true)
            if (fallback is YtdlpExecutionFailure) {
                if (fallback.expiredUrl) expired.set(true)
                if (!fallback.resumeAllowed) httpFailure.set(true)
                if (fallback.reextractionBlocked || !fallback.resumeAllowed || fallback.error in EXCLUDED_ERRORS ||
                    fallback.failureCause in setOf(DownloadFailureCause.TRANSPORT, DownloadFailureCause.STALL))
                    reextractionBlocked.set(true)
                remember(fallback.error, fallback.failureCause)
            }
        }
        val mapped = error.get().let {
            if (httpFailure.get() && cause.get() == DownloadFailureCause.HTTP &&
                it in setOf(DownloadError.Network, DownloadError.Offline)) DownloadError.Failed else it
        }
        return YtdlpExecutionFailure(mapped, expired.get(), !httpFailure.get(), cause.get(), reextractionBlocked.get())
    }
    companion object {
        fun blocksResume(message: String): Boolean = errorLines(message).any(HTTP_NO_RESUME::containsMatchIn)
        fun expiredUrl(message: String): Boolean = errorLines(message).any { HTTP_EXPIRED.containsMatchIn(it) || EXPIRED.containsMatchIn(it) }
        /** ERROR lines without URLs (a path may spell an error code), lowercased. */
        private fun errorLines(message: String) = message.lineSequence().filter { it.startsWith("ERROR:") }
            .map { it.replace(URL, "").lowercase(java.util.Locale.ROOT) }
        private val URL = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        private val HTTP_NO_RESUME = Regex("\\bhttp error (429|5\\d\\d)\\b")
        private val HTTP_EXPIRED = Regex("\\bhttp error (401|403|410)\\b")
        private val EXPIRED = Regex("\\b(url|signature|token)\\b.{0,40}\\bexpired\\b|\\bexpired (url|signature|token)\\b")
        private val EXCLUDED_ERRORS = setOf(DownloadError.Network, DownloadError.Offline, DownloadError.InsufficientSpace,
            DownloadError.SaveFailed, DownloadError.FolderUnavailable, DownloadError.ExecutionRestricted,
            DownloadError.MergeFailed, DownloadError.Interrupted, DownloadError.Cancelled)
        private val EXCLUDED_SIGNAL = Regex("permission denied|read-only file system|operation not permitted|" +
            "unable to (?:open|write)|interrupted|keyboardinterrupt|\\bcancelled\\b|\\bcanceled\\b|" +
            "too many requests|temporarily unavailable|service unavailable|postprocessing|ffmpeg exited|unable to merge|" +
            "network is unreachable|name resolution|unable to resolve host|no address associated with hostname|" +
            "no route to host|name or service not known|nodename nor servname provided|timed out|connection refused|" +
            "connection reset|connection abort|remote end closed connection|failed to establish a new connection|" +
            "failed to resolve|getaddrinfo failed|broken pipe|incompleteread|eof occurred in violation|ssleoferror|\\bssl\\b[^\\n]*eof")
        private val POSTPROCESSING = listOf("[Merger]", "[ffmpeg]", "[Fixup", "[VideoRemuxer]", "[VideoConvertor]",
            "[Metadata]", "[MoveFiles]", "[EmbedThumbnail]", "[ExtractAudio]")
    }
}

/** Local-only preparation runs inside the existing init/update gates, before any version probe. */
internal class DirectoryYtdlpRuntime(
    private val native: YtdlpRuntime, directory: File, enabled: Boolean,
    private val timing: YtdlpPreparationTiming = YtdlpPreparationTiming.process,
    rename: (File, File) -> Boolean = File::renameTo,
) : YtdlpRuntime by native {
    private val binary = File(directory, "yt-dlp")
    private val archive = File(directory, "yt-dlp.zip")
    private val installation = YtdlpDirectory(directory, enabled, rename, timing)
    override fun initialize() {
        // Recover before upstream init: a missing executable would otherwise be replaced by the
        // bundled zip while the update-version preference still describes the backed-up update.
        installation.recover()
        // 0.18.1 skips any existing path, even a first-copy fragment left by process death. Only
        // unversioned plain bundles and our recorded installs have a known size; retain other updates
        // and materialized directories. A deleted update is replaced by the bundled copy below.
        if (binary.isFile) native.bundledYtdlpSizeForRepair()?.let { expected ->
            if (binary.length() != expected) check(binary.delete())
        }
        native.initialize()
        reconcileVersion()
        installation.materialize()
    }
    override fun execute(args: List<String>, processId: String, line: (String) -> Unit): String {
        timing.mode(binary.isDirectory)
        return native.execute(args, processId, line)
    }
    override fun update() = installUpdate(PreparedUpdate.Unstaged)
    override fun installUpdate(prepared: PreparedUpdate) {
        try { native.installUpdate(prepared) } catch (error: Exception) {
            runCatching { reconcileVersion() }
            throw error
        }
        installation.materialize()
    }
    private fun reconcileVersion() {
        val installed = if (binary.isFile) binary else archive
        // init_ytdlp copies res/raw verbatim; its AssetInputStream size also identifies the retained
        // zip after materialization, without launching Python or reading the archive contents.
        if (!installed.isFile || native.bundledYtdlpSize() == installed.length()) {
            native.clearUpdatedYtdlpVersion()
        }
    }
    override fun restoreBundledYtdlp() { native.restoreBundledYtdlp(); installation.materialize() }
}

/** Only fixed labels, elapsed time and exception class names may reach the debug timing log. */
internal class YtdlpPreparationTiming(
    private val enabled: Boolean = BuildConfig.DEBUG,
    private val log: (String, String) -> Unit = { tag, message -> android.util.Log.d(tag, message); Unit },
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val modeLogged = AtomicBoolean()
    fun start(): Long = if (enabled) nanoTime() else 0L
    fun materialized(start: Long, error: Exception? = null) {
        if (!enabled) return
        val result = if (error == null) "ok ${(nanoTime() - start) / 1_000_000}ms"
            else "failed ${error.javaClass.simpleName}"
        emit("ytdlp_materialize $result")
    }
    fun mode(directory: Boolean) {
        if (enabled && modeLogged.compareAndSet(false, true)) emit("ytdlp_mode ${if (directory) "dir" else "zip"}")
    }
    private fun emit(message: String) {
        runCatching { log("NezzelTiming", message) } // Diagnostics must never disable initialization.
    }
    companion object { val process = YtdlpPreparationTiming() }
}

private const val MATERIALIZED_ID = ".materialized-id"

/** A failed optional extraction keeps the zip executable and disables this experiment for the run. */
private class YtdlpDirectory(
    directory: File, private var enabled: Boolean, private val rename: (File, File) -> Boolean,
    private val timing: YtdlpPreparationTiming,
) {
    private val binary = File(directory, "yt-dlp")
    private val archive = File(directory, "yt-dlp.zip")
    private val staging = File(directory, ".materialize-tmp")
    private fun complete() = binary.isDirectory && File(binary, "__main__.py").isFile &&
        File(binary, MATERIALIZED_ID).isFile

    fun recover() = optional {
        clear(staging)
        if (!binary.isFile && !complete()) {
            clear(binary)
            if (archive.isFile) check(rename(archive, binary))
            // Without a backup, leave the path absent so upstream init restores the bundled zip.
        }
    }

    fun materialize() = optional(reportSuccess = true) {
        if (complete()) return@optional
        clear(staging)
        check(binary.isFile)
        check(staging.mkdir())
        PushbackInputStream(binary.inputStream().buffered(), 4).use { input ->
            // Zipapps start with a shebang. Android ZipFile may reject the prefix/offsets;
            // sequential local headers need neither the prefix nor central-directory offsets.
            var header = 0
            while (header != 0x504b0304) {
                val byte = input.read()
                if (byte == -1) throw ZipException("Missing local header")
                header = (header shl 8) or byte
            }
            input.unread(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            ZipInputStream(input).use { zip ->
                val root = staging.canonicalFile.toPath()
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val destination = File(staging, entry.name).canonicalFile
                    require(destination.toPath().startsWith(root) && destination.toPath() != root)
                    if (entry.isDirectory) {
                        check(destination.isDirectory || destination.mkdirs())
                    } else {
                        val parent = requireNotNull(destination.parentFile)
                        check(parent.isDirectory || parent.mkdirs())
                        destination.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
        }
        check(File(staging, "__main__.py").isFile)
        File(staging, MATERIALIZED_ID).writeText(UUID.randomUUID().toString())
        // Never discard the current executable until extraction and validation have succeeded.
        check(!archive.exists() || (archive.isFile && archive.delete()))
        check(rename(binary, archive))
        check(rename(staging, binary))
    }

    private inline fun optional(reportSuccess: Boolean = false, action: () -> Unit) {
        if (!enabled) return
        val start = timing.start()
        try {
            action()
            if (reportSuccess) timing.materialized(start)
        } catch (error: Exception) {
            enabled = false
            // Independent cleanup attempts: a staging cleanup failure must not skip rollback.
            runCatching { clear(staging) }
            if (!binary.isFile && archive.isFile) runCatching {
                clear(binary)
                check(rename(archive, binary))
            }
            timing.materialized(start, error)
        }
    }

    /** Files.walk does not follow symlinks, including a leftover staging symlink. */
    private fun clear(file: File) {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }
    }
}

/** Directory mtimes change when Python creates __pycache__; only the extraction ID should re-warm. */
internal fun ytdlpInstallationStamp(directory: File): String {
    val executable = File(directory, "yt-dlp")
    val materialized = executable.isDirectory
    val binary = if (materialized) File(directory, "yt-dlp.zip") else executable
    val state = if (materialized) "directory:" + runCatching {
        File(executable, MATERIALIZED_ID).readText()
    }.getOrDefault("unmarked") else "zip"
    return "${binary.length()}:${binary.lastModified()}:$state"
}
