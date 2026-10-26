package dev.ahmed.savlino.sharing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import dev.ahmed.savlino.R
import dev.ahmed.savlino.usage.UsageEvents
import dev.ahmed.savlino.usage.UsageKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FileShareTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val pm get() = shadowOf(app.packageManager)
    private val file = Uri.parse("content://share.test/downloads/7")
    private val gone = Uri.parse("content://share.test/downloads/8")
    private val counted = mutableListOf<String>()
    private val messages = mutableListOf<Int>()
    private val usage = object : UsageEvents { override fun count(key: String) { counted += key } }

    /** Serves one readable file; any other id is gone (deleted, moved or no longer granted). */
    class Files : ContentProvider() {
        lateinit var file: File
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
            if (uri.lastPathSegment == "7") ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            else throw java.io.FileNotFoundException()
        override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri) = "video/x-matroska"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
    }

    @Before fun provider() {
        val files = Files().apply { file = File(app.cacheDir, "share.mkv").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        files.attachInfo(app, android.content.pm.ProviderInfo().apply { authority = "share.test" })
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("share.test", files)
    }

    private fun install(pkg: String, label: String, enabled: Boolean = true, mime: String? = "video/x-matroska") {
        pm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; this.enabled = enabled; nonLocalizedLabel = label }
        })
        if (mime != null) {
            val send = android.content.ComponentName(pkg, "$pkg.Send")
            pm.addActivityIfNotPresent(send)
            pm.addIntentFilterForActivity(send, android.content.IntentFilter(Intent.ACTION_SEND, mime)
                .apply { addCategory(Intent.CATEGORY_DEFAULT) })
        }
    }

    private fun sharer(activity: ComponentActivity) = FileSharer(activity, usage, CoroutineScope(Dispatchers.Unconfined), { messages += it })

    @Test fun noWhatsAppMeansTheChooser() =
        assertEquals(emptyList<ShareTarget>(), FileShare.targets(app.packageManager, "video/x-matroska"))

    @Test fun oneWhatsAppIsTheOnlyTarget() {
        install(FileShare.WHATSAPP, "WhatsApp")
        assertEquals(listOf(ShareTarget(FileShare.WHATSAPP, "WhatsApp")), FileShare.targets(app.packageManager, "video/x-matroska"))
    }

    @Test fun bothAppsKeepTheirInstalledNames() {
        install(FileShare.WHATSAPP, "WhatsApp")
        install(FileShare.WHATSAPP_BUSINESS, "WA Business")
        assertEquals(listOf(ShareTarget(FileShare.WHATSAPP, "WhatsApp"), ShareTarget(FileShare.WHATSAPP_BUSINESS, "WA Business")),
            FileShare.targets(app.packageManager, "video/x-matroska"))
    }

    @Test fun disabledAppOrNoHandlerForThisTypeIsLeftOut() {
        install(FileShare.WHATSAPP, "WhatsApp", enabled = false)
        install(FileShare.WHATSAPP_BUSINESS, "WA Business", mime = "image/jpeg")
        assertEquals(emptyList<ShareTarget>(), FileShare.targets(app.packageManager, "video/x-matroska"))
        assertEquals(listOf(FileShare.WHATSAPP_BUSINESS), FileShare.targets(app.packageManager, "image/jpeg").map { it.packageName })
    }

    @Test fun intentGrantsOnlyThisFileReadOnlyWithItsTrueType() {
        val direct = FileShare.intent(file, "video/x-matroska", ShareTarget(FileShare.WHATSAPP, "WhatsApp"))
        assertEquals(Intent.ACTION_SEND, direct.action)
        assertEquals("video/x-matroska", direct.type)
        assertEquals(FileShare.WHATSAPP, direct.`package`)
        assertNull(direct.component)
        @Suppress("DEPRECATION") assertEquals(file, direct.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(1, direct.clipData!!.itemCount)
        assertEquals(file, direct.clipData!!.getItemAt(0).uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, direct.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        val chooser = FileShare.intent(file, "audio/mp4", null)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val inner = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals("audio/mp4", inner.type)
        assertNull(inner.`package`)
    }

    @Test fun whatsAppLaunchCountsOnceAndTheChooserNever() = withActivity { activity ->
        install(FileShare.WHATSAPP, "WhatsApp")
        runBlocking { sharer(activity).launch(file, "video/x-matroska", ShareTarget(FileShare.WHATSAPP, "WhatsApp")) }
        assertEquals(FileShare.WHATSAPP, shadowOf(activity).nextStartedActivity.`package`)
        assertEquals(listOf(UsageKeys.SHARE_WHATSAPP), counted)
        runBlocking { sharer(activity).launch(file, "video/x-matroska", null) }
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity.action)
        assertEquals(listOf(UsageKeys.SHARE_WHATSAPP), counted)
        assertEquals(emptyList<Int>(), messages)
    }

    @Test fun goneFileSaysSoAndLaunchesNothing() = withActivity { activity ->
        install(FileShare.WHATSAPP, "WhatsApp")
        runBlocking { sharer(activity).launch(gone, "video/x-matroska", ShareTarget(FileShare.WHATSAPP, "WhatsApp")) }
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(listOf(R.string.share_file_unavailable), messages)
        assertEquals(emptyList<String>(), counted)
    }

    @Test fun whatsAppGoneSinceTheListFallsBackToTheChooser() = withActivity { activity ->
        shadowOf(app).checkActivities(true)
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_CHOOSER),
            ResolveInfo().apply { activityInfo = ActivityInfo().apply { packageName = "android"; name = "Chooser" } })
        runBlocking { sharer(activity).launch(file, "video/x-matroska", ShareTarget(FileShare.WHATSAPP, "WhatsApp")) }
        assertEquals(Intent.ACTION_CHOOSER, shadowOf(activity).nextStartedActivity.action)
        assertEquals(emptyList<String>(), counted)
        assertEquals(emptyList<Int>(), messages)
    }

    @Test fun aRunOfSeveralFilesOffersViewFilesNotShare() {
        val targets = listOf(ShareTarget(FileShare.WHATSAPP, "WhatsApp"))
        assertEquals(CompletionAction.ViewSaved, completionAction(2, targets))
        assertEquals(CompletionAction.ViewSaved, completionAction(5, emptyList()))
        assertEquals(CompletionAction.Share(targets), completionAction(1, targets))
        assertEquals(CompletionAction.Share(emptyList()), completionAction(1, emptyList()))
    }

    private fun withActivity(block: (ComponentActivity) -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try { block(controller.get()) } finally { controller.pause().stop().destroy() }
    }
}
