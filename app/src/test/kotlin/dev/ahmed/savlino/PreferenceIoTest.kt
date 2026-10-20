package dev.ahmed.savlino

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import dev.ahmed.savlino.domain.LinkKeyer
import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.storage.ClipboardPreferences
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class PreferenceIoTest {
    /** Keep real prefs effects; intercept only the blocking write boundary. */
    private fun context(onCommit: () -> Unit): Context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences(name, mode)
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor {
                    val editor = real.edit()
                    return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                        if (method.name == "commit") onCommit()
                        val result = method.invoke(editor, *(args ?: emptyArray()))
                        if (result === editor) proxy else result
                    } as SharedPreferences.Editor
                }
            }
        }
    }
    @Test fun cosmeticWritesUpdateValuesWithoutBlockingCommit() {
        val prefs = AppPreferences(context { fail("Cosmetic write synchronously commits") })
        prefs.chooseLanguage("ar"); prefs.theme = ThemePref.Dark
        prefs.matchWallpaper = true; prefs.suggestionsEnabled = false; prefs.automaticOptions = false
        assertEquals("ar", prefs.storedLanguage); assertEquals(ThemePref.Dark, prefs.theme)
        assertTrue(prefs.matchWallpaper); assertFalse(prefs.suggestionsEnabled); assertFalse(prefs.automaticOptions)
    }
    @Test fun keyLoadingAndDurableHashCommitRunOffMainAndReturnPersistedEffects() = runBlocking {
        var commits = 0
        val context = context {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread()); commits++
        }
        var keyLoads = 0
        val store = ClipboardPreferences(AppPreferences(context)) {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            keyLoads++; LinkKeyer.session
        }
        assertEquals(0, keyLoads)
        val gate = store.load()
        gate.dismiss(LinkInput.parse("https://youtu.be/one")!!)
        assertTrue(store.store(gate.dismissedHashes()))
        assertEquals(1, keyLoads); assertEquals(1, commits)
        assertEquals(gate.dismissedHashes(), AppPreferences(context).dismissedHashes)
        assertNull(store.load().offer("https://youtu.be/one"))
    }
    @Test fun journalConstructionDefersKeystoreUntilAnIoOperationNeedsATag() = runBlocking {
        val old = dev.ahmed.savlino.storage.KeystoreLinkKeyer.testOverride
        var loaded = 0
        dev.ahmed.savlino.storage.KeystoreLinkKeyer.testOverride = {
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            loaded++; LinkKeyer.session
        }
        try {
            dev.ahmed.savlino.jobs.JobStore(RuntimeEnvironment.getApplication()).use { journal ->
                assertEquals(0, loaded)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertTrue(journal.savedFormats("https://youtu.be/one").isEmpty())
                }
                assertEquals(1, loaded)
            }
        } finally { dev.ahmed.savlino.storage.KeystoreLinkKeyer.testOverride = old }
    }
    @Test fun unavailableKeystoreNeverPersistsSessionHashes() = runBlocking {
        val prefs = AppPreferences(context { fail("No durable key means no write") })
        val store = ClipboardPreferences(prefs) { null }
        val gate = store.load()
        gate.dismiss(LinkInput.parse("https://youtu.be/one")!!)
        assertFalse(store.store(gate.dismissedHashes()))
        assertEquals(emptySet<String>(), prefs.dismissedHashes)
    }
}
