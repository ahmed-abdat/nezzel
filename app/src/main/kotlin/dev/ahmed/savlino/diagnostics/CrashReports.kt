package dev.ahmed.savlino.diagnostics

import android.content.Context
import android.os.Build
import dev.ahmed.savlino.BuildConfig
import java.io.File

internal object CrashReports {
    fun store(context: Context) = CrashReportStore(File(context.filesDir, "crash"),
        CrashMetadata(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, Build.VERSION.SDK_INT, Build.MODEL))

    fun install(context: Context) {
        // Android installs RuntimeInit's terminating handler before Application creation.
        val previous = Thread.getDefaultUncaughtExceptionHandler() ?: return
        if (previous is CrashExceptionHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashExceptionHandler(store(context), previous))
    }
}
