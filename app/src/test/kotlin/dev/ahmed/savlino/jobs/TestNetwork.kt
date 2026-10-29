package dev.ahmed.savlino.jobs

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.CopyOnWriteArrayList

/** The network a download attempt watches: tests drop it while a transfer, convert or save is running. */
internal class TestNetwork(connected: Boolean = true) : NetworkWatch {
    val connectivity = FakeConnectivity(ConnectivityState(online = connected, offline = !connected))
    private val adapter = NetworkWatch.Adapter(connectivity)
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    var connected: Boolean
        get() = connectivity.snapshot.online
        // Model a synchronous snapshot changing before Android delivers a loss callback.
        set(value) { connectivity.set(ConnectivityState(online = value, offline = !value), notify = value) }
    override fun online() = adapter.online()
    override fun watch(lost: () -> Unit): AutoCloseable {
        listeners += lost
        val subscription = adapter.watch(lost)
        return AutoCloseable { subscription.close(); listeners -= lost }
    }
    fun lose() { connectivity.set(ConnectivityState(online = false, offline = true)) }
}

/** The download runs of a job's unique work; each sits behind its own network gate. */
internal fun downloadRuns(context: Context, id: String): List<WorkInfo> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(id).get().filter { DownloadWorker::class.java.name in it.tags }
