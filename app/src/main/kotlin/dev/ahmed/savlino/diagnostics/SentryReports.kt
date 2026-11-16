package dev.ahmed.savlino.diagnostics

import android.content.Context
import android.os.Build
import dev.ahmed.savlino.BuildConfig
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.App
import io.sentry.protocol.DebugImage
import io.sentry.protocol.DebugMeta
import io.sentry.protocol.Mechanism
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import java.io.File
import java.util.Date

/**
 * Crash reports to Sentry, only while Help improve Nezzel is on and the build carries an address.
 * What leaves the phone is a stack trace with the app version, Android version and phone brand: [scrub] copies those
 * into a new report and sends nothing else.
 */
internal object SentryReports {
    private fun cache(context: Context) = File(context.cacheDir, "sentry")

    /** Starts or stops reporting; off also deletes reports not sent yet. Debug builds never report. [configure] is for tests. */
    fun apply(context: Context, on: Boolean, dsn: String = if (BuildConfig.DEBUG) "" else BuildConfig.SENTRY_DSN,
        configure: (SentryAndroidOptions) -> Unit = {}) {
        if (dsn.isBlank()) return
        try {
            if (!on) {
                // Unsent reports go first, so closing has nothing left to send.
                cache(context).deleteRecursively()
                // Closing otherwise waits up to 15 s for queued reports to send; at zero it drops them at once.
                if (Sentry.isEnabled()) Sentry.getCurrentScopes().options.flushTimeoutMillis = 0
                Sentry.close()
                cache(context).deleteRecursively()
                return
            }
            if (Sentry.isEnabled()) return
            // Startup must never wait on the network: without the SDK's startup-crash marker, init does not block to
            // send a crash from the last launch; it is sent in the background instead.
            cache(context).walkTopDown().filter { it.name == "startup_crash" }.forEach { it.delete() }
            SentryAndroid.init(context) { options ->
                options.dsn = dsn
                options.cacheDirPath = cache(context).absolutePath
                options.release = "${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}"
                if (BuildConfig.SENTRY_PROGUARD_UUID.isNotBlank()) options.proguardUuid = BuildConfig.SENTRY_PROGUARD_UUID
                options.isSendDefaultPii = false
                options.isEnableAutoSessionTracking = false
                options.isSendClientReports = false
                // Crashes only. Freezes (ANR) and native stops come from Android's own history with full thread dumps,
                // and could describe a moment when reporting was off.
                options.isAnrEnabled = false
                options.isTombstoneEnabled = false
                options.isAttachAnrThreadDump = false
                options.isAttachThreads = false
                // Only freeze reports read this on-disk copy of the scope; without it nothing but reports is cached.
                options.isEnableScopePersistence = false
                options.isCollectAdditionalContext = false
                options.isAttachScreenshot = false
                options.isAttachViewHierarchy = false
                options.isEnableUserInteractionBreadcrumbs = false
                options.maxBreadcrumbs = 0
                options.tracesSampleRate = null
                options.shutdownTimeoutMillis = 0
                options.setBeforeBreadcrumb { _, _ -> null }
                configure(options)
                options.setBeforeSend { event, _ -> scrub(event, Build.BRAND) }
            }
        } catch (_: Throwable) {
            // Reporting is optional: it must never stop startup or a settings change.
        }
    }

    /**
     * An allow-list, not a clean-up: a new report gets the stack frames (class, function, file name, line), the app
     * version, the Android version and the phone brand. Everything else on [event] stays behind: exception messages
     * (they can carry links, titles or file paths), the user (install ID), the device (model), other threads,
     * breadcrumbs, extras, tags and anything a later SDK version adds.
     */
    fun scrub(event: SentryEvent, brand: String): SentryEvent {
        val sent = SentryEvent(event.timestamp ?: Date())
        sent.level = event.level
        sent.platform = event.platform
        sent.release = event.release
        sent.exceptions = event.exceptions?.map { from ->
            SentryException().apply {
                type = from.type
                module = from.module
                mechanism = from.mechanism?.let { Mechanism().apply { type = it.type; isHandled = it.isHandled } }
                stacktrace = from.stacktrace?.let { trace ->
                    SentryStackTrace(trace.frames?.map { frame ->
                        SentryStackFrame().apply {
                            module = frame.module
                            function = frame.function
                            filename = frame.filename
                            lineno = frame.lineno
                            isInApp = frame.isInApp
                            isNative = frame.isNative
                        }
                    })
                }
            }
        }
        // Only the pointer to this build's R8 mapping, so obfuscated names can be read.
        val mappings = event.debugMeta?.images?.filter { it.type == "proguard" }.orEmpty()
        if (mappings.isNotEmpty()) sent.debugMeta = DebugMeta().apply {
            images = mappings.map { DebugImage().apply { type = it.type; uuid = it.uuid } }
        }
        event.contexts.operatingSystem?.let { os -> sent.contexts.setOperatingSystem(OperatingSystem().apply { name = os.name; version = os.version }) }
        event.contexts.app?.let { app -> sent.contexts.setApp(App().apply { appVersion = app.appVersion; appBuild = app.appBuild }) }
        sent.setTag("brand", brand)
        return sent
    }
}
