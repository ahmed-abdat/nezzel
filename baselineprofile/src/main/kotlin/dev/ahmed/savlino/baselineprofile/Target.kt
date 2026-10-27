package dev.ahmed.savlino.baselineprofile

import android.content.res.Resources
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.util.regex.Pattern

/**
 * The app under test. The baselineprofile plugin passes its id; generation must only ever drive the separate
 * ".demo" copy, because the test run uninstalls what it installed and the real app holds the owner's files.
 */
val targetPackage: String = InstrumentationRegistry.getArguments().getString("targetAppId")
    ?.also { check(it.endsWith(".demo")) { "Refusing to profile $it: only the .demo copy may be installed and removed" } }
    ?: error("targetAppId instrumentation argument missing")

private const val TIMEOUT = 5_000L

/** Labels come from the installed app in the device language, so the journeys work in Arabic and English. */
private val appResources: Resources by lazy {
    InstrumentationRegistry.getInstrumentation().context.packageManager.getResourcesForApplication(targetPackage)
}

fun string(name: String): String {
    val id = appResources.getIdentifier(name, "string", targetPackage)
    check(id != 0) { "Missing string $name" }
    return appResources.getString(id)
}

fun MacrobenchmarkScope.find(selector: BySelector, timeout: Long = TIMEOUT): UiObject2? =
    device.wait(Until.findObject(selector), timeout)

/** The first-run tour and the notification prompt, on a fresh .demo install; both are optional afterwards. */
fun MacrobenchmarkScope.passFirstRun() {
    repeat(3) {
        find(By.text(string("onboarding_skip")), 1_500)?.click()
            ?: find(By.res(Pattern.compile(".*:id/permission_deny_button")), 1_500)?.click()
            ?: return
        device.waitForIdle()
    }
}

fun MacrobenchmarkScope.openTab(label: String): Boolean {
    val tab = find(By.text(label)) ?: return false
    tab.click()
    device.waitForIdle()
    return true
}

/** Flings the largest scrollable surface down and back; no-op on a screen with nothing to scroll. */
fun MacrobenchmarkScope.scrollMain() {
    val list = device.findObjects(By.scrollable(true)).maxByOrNull { it.visibleBounds.height() } ?: return
    list.setGestureMargin(device.displayWidth / 5)
    repeat(2) { list.fling(Direction.DOWN) }
    list.fling(Direction.UP)
    device.waitForIdle()
}

/** Opens the first saved tile (its description is "name · details") in the viewer, then closes it. */
fun MacrobenchmarkScope.openFirstTile(filterLabel: String?): Boolean {
    filterLabel?.let { label -> find(By.text(label), 1_000)?.click()?.also { device.waitForIdle() } }
    val tile = find(By.descContains(string("separator")).clickable(true), 2_000) ?: return false
    tile.click()
    device.waitForIdle()
    Thread.sleep(1_500) // let playback start so the player path is profiled
    device.pressBack()
    device.waitForIdle()
    return true
}

/**
 * A fresh .demo install sees Download/Nezzel only through the folder grant it offers. The system picker opens
 * pre-pointed at that folder; its buttons are matched by id, not by the device language. Skipped when not offered.
 */
fun MacrobenchmarkScope.grantOldDownloads() {
    val show = find(By.text(string("old_downloads_show")), 2_000) ?: return
    show.click()
    val use = find(By.res(Pattern.compile(".*:id/action_menu_select")))
    if (use == null) { device.pressBack(); return }
    use.click()
    find(By.res("android:id/button1"))?.click()
    device.wait(Until.hasObject(By.pkg(targetPackage).depth(0)), TIMEOUT)
    device.waitForIdle()
}
