package dev.ahmed.savlino.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Everyday paths after start. Every media step is skipped when the library is empty. */
@RunWith(AndroidJUnit4::class)
class JourneysProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun journeys() = rule.collect(packageName = targetPackage) {
        pressHome()
        startActivityAndWait()
        passFirstRun()

        if (openTab(string("shortcut_downloads_short"))) {
            grantOldDownloads()
            scrollMain()
            openFirstTile(string("filter_videos"))
            openFirstTile(string("audio_group"))
            openFirstTile(string("filter_all"))
        }
        if (openTab(string("statuses_tab"))) scrollMain()
        if (openTab(string("settings"))) scrollMain()
        openTab(string("home"))
    }
}
