package dev.ahmed.savlino

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import dev.ahmed.savlino.entry.ShareInput
import dev.ahmed.savlino.entry.plainText
import dev.ahmed.savlino.entry.ClipboardEntryEffect
import dev.ahmed.savlino.jobs.NezzelApplication
import dev.ahmed.savlino.shortcuts.ShortcutActions
import dev.ahmed.savlino.locale.*
import dev.ahmed.savlino.ui.LinkViewModel
import dev.ahmed.savlino.ui.theme.NezzelTheme

/** The Application owns the engine, durable jobs and cancellation across Activity recreation. */
class MainActivity : AppCompatActivity() {
    private var showNotificationRationale by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) {
        if (::links.isInitialized) links.finishNotificationRequest()
    }
    private fun beforeConfirmedDownload() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            showNotificationRationale = true
        } else links.finishNotificationRequest()
    }
    private fun notificationRationaleDecision(request: Boolean) {
        showNotificationRationale = false
        getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
        if (request && android.os.Build.VERSION.SDK_INT >= 33) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        else links.finishNotificationRequest()
    }
    private fun allowNotifications() {
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this,
            android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            (!getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false) ||
                shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS))) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else try {
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: android.content.ActivityNotFoundException) { /* Some OEM builds lack this screen. */ }
    }
    private lateinit var preferences: AppPreferences
    lateinit var links: LinkViewModel; private set
    private var onboarding by mutableStateOf(false)
    private var replayingOnboarding = false
    private var resumed = false
    private var clipboardReadThisEntry = false
    private var pasteRequested = false
    private var theme by mutableStateOf(ThemePref.System)
    private var wallpaper by mutableStateOf(false)
    private var suggestions by mutableStateOf(true)
    private var language by mutableStateOf("en")

    override fun attachBaseContext(base: Context) { super.attachBaseContext(AppLocale.attach(base)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = AppPreferences(this)
        val openUpdate = intent.action == dev.ahmed.savlino.update.UpdateNotifications.OPEN
        val launchShare = ShareInput.from(intent)
        replayingOnboarding = launchShare == null && savedInstanceState?.getBoolean("onboardingReplay") == true
        onboarding = if (launchShare != null) {
            preferences.markOnboardingSeen()
            false
        } else replayingOnboarding || preferences.needsOnboarding {
            // Once-per-install upgrade check, only while onboarding_seen is false.
            dev.ahmed.savlino.jobs.JobStore(this).use { it.all().isNotEmpty() }
        }
        val downloads = (application as NezzelApplication).downloads
        val media = (application as NezzelApplication).media
        val clipboard = dev.ahmed.savlino.storage.ClipboardPreferences(preferences)
        links = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                @Suppress("UNCHECKED_CAST")
                return LinkViewModel(extras.createSavedStateHandle(),
                    engine = downloads.engine, downloads = downloads, coordinatorScope = (application as NezzelApplication).scope,
                    initialPreset = preferences.lastPreset, rememberPreset = { preferences.lastPreset = it },
                    initialHeight = preferences.lastHeight, rememberHeight = { preferences.lastHeight = it },
                    rememberHandledClipboard = clipboard::store, initializeClipboard = clipboard::load,
                    images = media.inspect, imageSaver = media.saver, earlyPreview = media::earlyPreview,
                    resolveShortLink = media::resolveShortLink,
                    usage = (application as NezzelApplication).usage) as T
            }
        })[LinkViewModel::class.java]
        val appUpdates = (application as NezzelApplication).appUpdates
        if (savedInstanceState == null && openUpdate && appUpdates.available) links.navigate("AppUpdate")
        links.notificationPrompt = ::beforeConfirmedDownload
        showNotificationRationale = savedInstanceState?.getBoolean("notificationRationale") == true && links.hasPendingNotificationChoice
        refreshPreferences()
        pasteRequested = !onboarding && (savedInstanceState?.getBoolean("pasteRequested") ?: ShortcutActions.isPasteEntry(intent))
        if (savedInstanceState == null && intent.action == ShortcutActions.OPEN_DOWNLOADS) links.navigate("Downloads")
        enableEdgeToEdge()
        if (!onboarding) {
            links.onCreateShare(launchShare)
            (application as NezzelApplication).startMaintenanceAfterOnboarding()
        }
        appUpdates.start() // After intake: a cold shared link reaches the view model first.
        // Consume the launch envelope; the ViewModel owns accepted/queued user state thereafter.
        setIntent(Intent(this, MainActivity::class.java))
        setContent {
            // Guards may clear without a new focus/resume event (e.g. restored Settings -> Home).
            // Re-use the same once-per-entry foreground reader; the ViewModel rechecks eligibility.
            if (!onboarding) ClipboardEntryEffect(links, ::suggestClipboard)
            NezzelTheme(theme, wallpaper) {
                if (onboarding) dev.ahmed.savlino.ui.OnboardingScreen(::finishOnboarding)
                else NezzelApp(links, language, theme, wallpaper, suggestions,
                    statusLibrary = media.statusLibrary,
                    onPaste = ::pasteClipboard,
                    onDismissSuggestion = links::dismissSuggestion,
                    onAcceptSuggestion = { links.inspectSuggestion() },
                    onAutomaticOptions = { preferences.automaticOptions = it; links.automaticOptions = it },
                    onAllowNotifications = ::allowNotifications,
                    notificationRationaleShowing = showNotificationRationale,
                    onHowItWorks = { replayingOnboarding = true; onboarding = true },
                    onLanguage = { AppLocale.choose(this, it) },
                    onTheme = { preferences.theme = it; theme = it; AppLocale.applyTheme(it) },
                    onWallpaper = { preferences.matchWallpaper = it; wallpaper = it },
                    onDismissOptions = links::dismissOptions,
                    onSuggestions = {
                        preferences.suggestionsEnabled = it; suggestions = it; links.suggestionsEnabled = it
                        if (!it) links.dismissSuggestion()
                    })
                if (showNotificationRationale) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { notificationRationaleDecision(false) },
                    title = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.notification_rationale_title)) },
                    text = { androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.notification_rationale_body)) },
                    confirmButton = { androidx.compose.material3.TextButton(onClick = { notificationRationaleDecision(true) }) {
                        androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.allow_notifications))
                    } },
                    dismissButton = { androidx.compose.material3.TextButton(onClick = { notificationRationaleDecision(false) }) {
                        androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(R.string.notification_skip))
                    } })
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notificationRationale", showNotificationRationale)
        outState.putBoolean("onboardingReplay", replayingOnboarding)
        outState.putBoolean("pasteRequested", pasteRequested)
        super.onSaveInstanceState(outState)
    }
    private fun refreshPreferences() {
        theme = preferences.theme; wallpaper = preferences.matchWallpaper
        suggestions = preferences.suggestionsEnabled; links.suggestionsEnabled = suggestions
        links.automaticOptions = preferences.automaticOptions
        language = AppLocale.current(this)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ShortcutActions.isPasteEntry(intent) || intent.action == ShortcutActions.OPEN_DOWNLOADS) {
            pasteRequested = ShortcutActions.isPasteEntry(intent) && !onboarding
            if (intent.action == ShortcutActions.OPEN_DOWNLOADS) links.navigate("Downloads")
            setIntent(Intent(this, MainActivity::class.java))
            pasteIfRequested()
            return
        }
        pasteRequested = false
        if (intent.action == dev.ahmed.savlino.update.UpdateNotifications.OPEN && (application as NezzelApplication).appUpdates.available) {
            links.navigate("AppUpdate")
            setIntent(Intent(this, MainActivity::class.java))
            return
        }
        val share = ShareInput.from(intent)
        links.onNewShare(share)
        if (onboarding && share != null) finishOnboarding()
        setIntent(Intent(this, MainActivity::class.java))
    }
    override fun onResume() {
        super.onResume(); resumed = true
        links.clipboardForeground = hasWindowFocus()
        refreshPreferences(); if (!onboarding) links.onForeground(); pasteIfRequested(); suggestClipboard()
    }
    override fun onPause() {
        resumed = false; clipboardReadThisEntry = false
        links.clipboardForeground = false
        super.onPause()
    }
    override fun onStop() {
        pasteRequested = false
        super.onStop()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::links.isInitialized) {
            links.clipboardForeground = resumed && hasFocus
            if (hasFocus) { pasteIfRequested(); suggestClipboard() }
        }
    }
    private fun finishOnboarding() {
        if (!preferences.markOnboardingSeen()) return
        onboarding = false
        replayingOnboarding = false
        (application as NezzelApplication).startMaintenanceAfterOnboarding()
        links.onCreateShare(null)
        if (resumed) links.onForeground()
        // ClipboardEntryEffect resumes intake after composition, so an incoming Share wins first.
    }
    private fun suggestClipboard() {
        if (onboarding || clipboardReadThisEntry) return
        links.suggest(resumed, hasWindowFocus(), !isFinishing && !isDestroyed) {
            clipboardReadThisEntry = true
            getSystemService(ClipboardManager::class.java).plainText()
        }
    }
    private fun pasteIfRequested() {
        if (!pasteRequested || !resumed || !hasWindowFocus()) return
        pasteRequested = false
        if (!onboarding) {
            links.navigate("Home")
            pasteClipboard()
        }
    }
    /** Shared by Home and explicit shortcut taps; mark the entry read before suggestions can run. */
    private fun pasteClipboard() {
        if (!resumed || !hasWindowFocus() || onboarding) return
        clipboardReadThisEntry = true
        links.paste(getSystemService(ClipboardManager::class.java).plainText())
        // Paste publishes its pending link before maintenance can observe this entry's decision.
        (application as NezzelApplication).downloads.maintenance.foregroundClipboardChecked()
    }
}
