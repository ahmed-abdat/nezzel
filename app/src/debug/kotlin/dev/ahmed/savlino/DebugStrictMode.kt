package dev.ahmed.savlino

internal object DebugStrictMode {
    fun install() {
        android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder()
            .detectAll().penaltyLog().build())
        android.os.StrictMode.setVmPolicy(android.os.StrictMode.VmPolicy.Builder()
            .detectLeakedClosableObjects().penaltyLog().build())
    }
}
