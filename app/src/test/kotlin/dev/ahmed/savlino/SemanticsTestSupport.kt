@file:OptIn(ExperimentalComposeUiApi::class)

package dev.ahmed.savlino

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.ahmed.savlino.locale.ThemePref
import dev.ahmed.savlino.ui.theme.NezzelTheme
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.time.Duration

// Shared Compose semantics helpers for the Robolectric UI tests. Each test keeps its own click/matching rules.

fun roots(view: View): List<ViewRootForTest> = when (view) {
    is ViewRootForTest -> listOf(view)
    is ViewGroup -> (0 until view.childCount).flatMap { roots(view.getChildAt(it)) }
    else -> emptyList()
}

fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

fun texts(nodes: List<SemanticsNode>): List<String> =
    nodes.flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }

/** The activity's decor view plus every window registered with WindowManagerGlobal (a modal sheet attaches its own). */
fun windowViews(activity: Activity): List<View> {
    val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
    val field = global.javaClass.getDeclaredField("mViews").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST") val all = (field.get(global) as List<View>).toList()
    return (listOf(activity.window.decorView) + all).distinct()
}

/** Idles the main looper and lays out [rounds] times, then returns the unmerged semantics nodes of every root. */
fun semanticsNodes(activity: Activity, rounds: Int = 4, idleMillis: Long = 100, allWindows: Boolean = true): List<SemanticsNode> {
    fun views() = if (allWindows) windowViews(activity) else listOf(activity.window.decorView)
    repeat(rounds) {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(idleMillis))
        views().flatMap(::roots).forEach { it.measureAndLayoutForTest() }
    }
    return views().flatMap(::roots).flatMap { descendants(it.semanticsOwner.unmergedRootSemanticsNode) }
}

/** Hosts [content] in a light [NezzelTheme] activity, settles it with [settle], runs [check], and tears the activity down. */
fun renderThemed(settle: (ComponentActivity) -> Unit, content: @Composable () -> Unit, check: (ComponentActivity) -> Unit) {
    val c = Robolectric.buildActivity(ComponentActivity::class.java).create().start().resume().visible()
    try {
        c.get().setContent { NezzelTheme(ThemePref.Light, false) { content() } }
        settle(c.get()); check(c.get())
    } finally { c.pause().stop().destroy() }
}

/**
 * Waits in real time while [busy] holds, running [pump] between looks (worker threads need real time to take the store
 * lock). `withTimeout` can't stop a loop that never suspends, so a stuck condition used to hang the whole test run.
 */
internal fun waitWhile(pump: () -> Unit, timeoutMs: Long = 5_000, busy: () -> Boolean) {
    val end = System.nanoTime() + timeoutMs * 1_000_000
    while (busy()) {
        if (System.nanoTime() > end) throw AssertionError("Still waiting after $timeoutMs ms")
        pump(); Thread.sleep(5)
    }
}
