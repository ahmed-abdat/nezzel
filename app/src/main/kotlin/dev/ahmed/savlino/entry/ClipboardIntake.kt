package dev.ahmed.savlino.entry

import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.ahmed.savlino.ui.LinkViewModel

/** A blocked foreground entry can become eligible without a new resume/focus event. */
@Composable
internal fun ClipboardEntryEffect(links: LinkViewModel, readWhenFocused: () -> Unit) {
    LaunchedEffect(links, links.clipboardEligible) {
        if (links.clipboardEligible) readWhenFocused()
    }
}

/** Call only after foreground/focus eligibility or an explicit Paste tap. No URI coercion/IO. */
fun ClipboardManager.plainText(): String? = try {
    if (primaryClipDescription?.hasMimeType("text/plain") != true) null
    else primaryClip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text
        ?.takeIf { it.length <= 8192 }?.toString()
} catch (_: SecurityException) { null }
