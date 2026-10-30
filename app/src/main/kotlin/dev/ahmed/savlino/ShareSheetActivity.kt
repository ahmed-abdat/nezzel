package dev.ahmed.savlino

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.lifecycleScope
import dev.ahmed.savlino.domain.LinkInput
import dev.ahmed.savlino.entry.NotificationAsk
import dev.ahmed.savlino.entry.ShareInput
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.locale.AppLocale
import dev.ahmed.savlino.locale.AppPreferences
import dev.ahmed.savlino.media.ImageSaveState
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.OptionsState
import dev.ahmed.savlino.ui.ShareSheet
import dev.ahmed.savlino.ui.ShareSheetHooks
import dev.ahmed.savlino.ui.imageBatchMessage
import dev.ahmed.savlino.ui.theme.NezzelTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Share, Open in Nezzel (selected text) and tapped links open the options as a sheet over the app they came from.
 * Translucent, excluded from recents, its own task: after Download or any dismissal it finishes and the person is
 * back where they were. Every share gets a fresh view model; downloads live in the Application's coordinator.
 */
class ShareSheetActivity : AppCompatActivity() {
    lateinit var links: LinkViewModel; private set
    private lateinit var preferences: AppPreferences
    /** What the other app sent, kept only to hand it to the full app unchanged. */
    private var sharedText: String? = null
    private var imageClose: Job? = null
    private var notifyLine by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (::links.isInitialized) links.finishNotificationRequest()
    }

    override fun attachBaseContext(base: Context) { super.attachBaseContext(AppLocale.attach(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        val input = ShareInput.from(intent)
        sharedText = input?.text
        // Cases the sheet cannot show well go to the full app: first run, several links in one share.
        if (handOffNeeded(input)) { openInNezzel(); return }
        links = linkViewModel(this, preferences)
        links.suggestionsEnabled = false
        links.clipboardForeground = false
        links.automaticOptions = preferences.automaticOptions
        links.notificationPrompt = ::beforeConfirmedDownload
        notifyLine = NotificationAsk.shouldAsk(this)
        links.onCreateShare(input)
        enableEdgeToEdge(SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        val language = AppLocale.current(this)
        setContent {
            var sentAt by remember(links.shareGeneration) { mutableStateOf<Long?>(null) }
            val haptics = LocalHapticFeedback.current
            val sent = links.confirmed || links.acceptedJob != null
            LaunchedEffect(sent, links.shareGeneration) {
                if (!sent) return@LaunchedEffect
                sentAt = android.os.SystemClock.uptimeMillis()
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
            // Close only once the coordinator has the job (enqueue done), after the check was seen. With an
            // screen reader on (touch exploration), the sheet stays until dismissed (Close, Back or outside).
            LaunchedEffect(links.acceptedJob, links.optionsState, links.shareGeneration, links.deferredShare) {
                if (links.deferredShare != null || links.acceptedJob == null || links.optionsState != OptionsState.Hidden || touchExploration()) return@LaunchedEffect
                val generation = links.shareGeneration
                val shown = android.os.SystemClock.uptimeMillis() - (sentAt ?: android.os.SystemClock.uptimeMillis())
                delay((STARTED_MILLIS - shown).coerceAtLeast(0))
                if (generation == links.shareGeneration && links.deferredShare == null) finishAndRemoveTask()
            }
            LaunchedEffect(links.deferredShare, links.confirmed, links.acceptedJob, links.imageSaving, links.hasPendingNotificationChoice) {
                links.takeDeferredShare()
            }
            LaunchedEffect(links.moreFormats) { if (links.moreFormats) openInNezzel() }
            LaunchedEffect(Unit) { links.imageEvents.collect(::onImageEvent) }
            LaunchedEffect(Unit) { links.soundFailedEvents.collect {
                Toast.makeText(applicationContext, R.string.sound_save_failed, Toast.LENGTH_LONG).show() } }
            NezzelTheme(preferences.theme, preferences.matchWallpaper) {
                ShareSheet(links, language, ShareSheetHooks(onOpenApp = { if (!links.imageSaving) openInNezzel() }, onFinish = ::finishAndRemoveTask,
                    notifyLine = notifyLine, onAllowNotifications = ::askNotifications,
                    sent = sent || (links.acceptedJob != null && links.optionsState == OptionsState.Hidden)))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val input = ShareInput.from(intent) ?: return
        setIntent(intent)
        sharedText = input.text
        if (!::links.isInitialized || handOffNeeded(input)) { openInNezzel(); return }
        imageClose?.cancel()
        links.deferShare(input)
    }

    private fun handOffNeeded(input: ShareInput?) = !preferences.onboardingSeen || LinkInput.candidates(input?.text).size > 1

    /** Handled-link mark for the clipboard: on Download only, never on dismissal. */
    private fun markHandled(link: String) {
        val handled = links
        (application as NezzelApplication).scope.launch { withTimeoutOrNull(5_000) { handled.markHandled(link) } }
    }

    private fun beforeConfirmedDownload() {
        LinkInput.parse(links.text)?.rawUrl?.let(::markHandled)
        if (NotificationAsk.shouldAsk(this)) launchNotificationRequest() else links.finishNotificationRequest()
    }

    private fun askNotifications() { if (NotificationAsk.shouldAsk(this)) launchNotificationRequest() }

    private fun launchNotificationRequest() {
        NotificationAsk.markAsked(this)
        notifyLine = false
        if (android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else links.finishNotificationRequest()
    }

    /** Photos save in this process: report the result as a toast (it outlives the sheet), then close if all were saved. */
    private fun onImageEvent(event: ImageSaveState) {
        val message = when (event) {
            is ImageSaveState.BatchFinished -> imageBatchMessage(resources, event, AppLocale.current(this))
            is ImageSaveState.Saved -> resources.getQuantityString(R.plurals.image_saved_count, 1,
                dev.ahmed.savlino.locale.NezzelFormat.count(1, AppLocale.current(this)))
            else -> return
        }
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        if (links.optionsState == OptionsState.Hidden && links.text.isEmpty() && links.deferredShare == null) {
            val generation = links.shareGeneration
            imageClose?.cancel()
            imageClose = lifecycleScope.launch {
                delay(dwellMillis())
                if (generation == links.shareGeneration && links.deferredShare == null) finishAndRemoveTask()
            }
        }
    }

    /** TalkBack-style screen readers; other accessibility services (password managers, switches) don't hold the sheet. */
    private fun touchExploration() = getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /** After a photo save the toast carries the result; TalkBack gets time to read it. */
    private fun dwellMillis(): Long = if (touchExploration()) 900 else 450

    private companion object { const val STARTED_MILLIS = 1_500L }

    /** The full app takes the same shared text, through the same Share path (explicit, so the moved filter does not matter). */
    private fun openInNezzel() {
        val text = sharedText
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (text != null) open.setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(open)
        finishAndRemoveTask()
    }
}
