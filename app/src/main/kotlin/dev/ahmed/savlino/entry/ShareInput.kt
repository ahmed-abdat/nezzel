package dev.ahmed.savlino.entry

import android.content.Intent

/** Read only the supplied text; never dereference an inbound URI or ClipData. */
class ShareInput private constructor(val text: String?) {
    companion object {
        fun from(intent: Intent?): ShareInput? {
            if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return null
            val text = try { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }
                catch (_: RuntimeException) { null }
            return ShareInput(text?.takeIf { it.length <= 8192 })
        }
    }
}
