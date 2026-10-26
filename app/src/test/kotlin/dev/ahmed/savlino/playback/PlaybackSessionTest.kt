package dev.ahmed.savlino.playback

import android.net.Uri
import androidx.media3.common.Player
import dev.ahmed.savlino.viewer.ViewerItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlaybackSessionTest {
    private fun item(id: Int, saved: Boolean = true) = ViewerItem(Uri.parse("content://test/session/$id"), true, "video/mp4", 100_000,
        resumePolicy = if (saved) ResumePolicy.SAVED else ResumePolicy.SESSION_ONLY)
    @Test fun playingCheckpointsAtFiveSecondsAndPauseSavesLatestPosition() = runBlocking {
        val scheduler = ManualPlaybackScheduler()
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), scheduler = scheduler, sizeOf = { 10L })
        val player = RecordingPlayer()
        val current = item(100)
        env.resume.clear(current, 10)
        val session = PlaybackSession(player, env)
        session.open(current); player.play(); player.at(20_000)
        assertEquals(0, env.resume.position(current, 10, 100_000))
        scheduler.advance(5_000)
        assertEquals(20_000, env.resume.position(current, 10, 100_000))
        player.at(30_000); player.pause()
        assertEquals(30_000, env.resume.position(current, 10, 100_000))
        session.close()
        assertTrue(player.released)
    }
    @Test fun loopAndCompletionClearRealHistory() = runBlocking {
        val scheduler = ManualPlaybackScheduler()
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), scheduler = scheduler, sizeOf = { 10L })
        val current = item(101)
        val player = RecordingPlayer()
        val session = PlaybackSession(player, env)
        session.open(current); player.at(20_000); session.checkpoint()
        assertEquals(20_000, env.resume.position(current, 10, 100_000))
        player.loop()
        assertEquals(0, env.resume.position(current, 10, 100_000))
        player.play(); player.at(5_000); scheduler.advance(5_000)
        assertEquals(0, env.resume.position(current, 10, 100_000))
        player.at(30_000); session.checkpoint(); player.end()
        assertEquals(0, env.resume.position(current, 10, 100_000))
        session.close()
    }
    @Test fun statusResumeIsInMemoryAndNeverDurable() = runBlocking {
        val env = PlaybackEnvironment(RuntimeEnvironment.getApplication(), sizeOf = { 10L })
        val current = item(102, saved = false)
        val first = PlaybackSession(RecordingPlayer(), env)
        first.open(current); (first.player as RecordingPlayer).at(20_000); first.close()
        assertEquals(0, env.resume.position(current, 10, 100_000))
        val second = PlaybackSession(RecordingPlayer(), env)
        second.open(current)
        assertEquals(20_000, second.resumedAt)
        second.close()
    }
}
internal class ManualPlaybackScheduler : PlaybackScheduler {
    private data class Task(val at: Long, val action: () -> Unit, var cancelled: Boolean = false)
    private var time = 0L
    private val tasks = mutableListOf<Task>()
    override fun after(milliseconds: Long, action: () -> Unit): PlaybackCancellation {
        val task = Task(time + milliseconds, action); tasks += task
        return PlaybackCancellation { task.cancelled = true }
    }
    fun advance(milliseconds: Long) {
        val end = time + milliseconds
        while (true) {
            val task = tasks.filter { !it.cancelled && it.at <= end }.minByOrNull { it.at } ?: break
            tasks.remove(task); time = task.at; task.action()
        }
        time = end
    }
}
