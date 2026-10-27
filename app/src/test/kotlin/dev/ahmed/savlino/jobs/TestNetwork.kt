package dev.ahmed.savlino.jobs

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.CopyOnWriteArrayList

/** The network a download attempt watches: tests drop it while a transfer, convert or save is running. */
internal class TestNetwork(@Volatile var connected: Boolean = true) : NetworkWatch {
    val listeners = CopyOnWriteArrayList<() -> Unit>()
    override fun online() = connected
    override fun watch(lost: () -> Unit): AutoCloseable { listeners += lost; return AutoCloseable { listeners -= lost } }
    fun lose() { connected = false; listeners.forEach { it() } }
}

/** The download runs of a job's unique work; each sits behind its own network gate. */
internal fun downloadRuns(context: Context, id: String): List<WorkInfo> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWork(id).get().filter { DownloadWorker::class.java.name in it.tags }
