package dev.ahmed.savlino.ui.nav

import android.view.View
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.platform.findViewTreeCompositionContext

/**
 * Test-only: counts the recompose scopes that run, named after the composable that owns each one (the first stack
 * frame outside the runtime). Production code never sees it.
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
class RecompositionProbe private constructor(recomposer: Recomposer) : AutoCloseable {
    private val counts = linkedMapOf<String, Int>()
    /** The same scopes with the lambda kept in the name (`NezzelApp$lambda$12`), to tell a body from its lambdas. */
    val detailed = linkedMapOf<String, Int>()
    /** State objects that invalidated a scope during the last [record], by their description. */
    val invalidations = linkedMapOf<String, Int>()
    /** While set, reads of matching states are attributed to the app line that read them. */
    var readFilter: Regex? = null
    val readers = linkedMapOf<String, Int>()
    private val handles = mutableListOf<CompositionObserverHandle>()
    var recording = false
    private val observer = object : CompositionObserver {
        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) {
            if (!recording) return
            val frame = Thread.currentThread().stackTrace.firstOrNull { frame ->
                val c = frame.className
                !c.startsWith("java.") && !c.startsWith("androidx.compose.runtime.") && !c.startsWith("dev.ahmed.savlino.ui.nav.RecompositionProbe") &&
                    !c.startsWith("kotlin.") && !c.startsWith("androidx.collection.")
            }
            val owner = frame?.let { "${it.className.substringAfterLast('.').substringBefore('$')}.${it.methodName.substringBefore('$')}" } ?: "?"
            counts[owner] = (counts[owner] ?: 0) + 1
            val full = frame?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" } ?: "?"
            detailed[full] = (detailed[full] ?: 0) + 1
        }
        override fun onReadInScope(scope: RecomposeScope, value: Any) {
            val filter = readFilter ?: return
            if (!recording || !filter.containsMatchIn(value.toString().take(120))) return
            val frame = Thread.currentThread().stackTrace.firstOrNull {
                it.className.startsWith("dev.ahmed.savlino.") && !it.className.startsWith("dev.ahmed.savlino.ui.nav.RecompositionProbe") &&
                    !it.className.startsWith("dev.ahmed.savlino.ui.LinkViewModel")
            }
            val key = frame?.let { "${it.fileName}:${it.lineNumber}" } ?: "?"
            readers[key] = (readers[key] ?: 0) + 1
        }
        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {
            if (!recording) return
            val key = value?.toString()?.take(80) ?: "null"
            invalidations[key] = (invalidations[key] ?: 0) + 1
        }
        override fun onScopeDisposed(scope: RecomposeScope) {}
    }
    private val registration = recomposer.observe(object : CompositionRegistrationObserver {
        override fun onCompositionRegistered(composition: ObservableComposition) { handles += composition.setObserver(observer) }
        override fun onCompositionUnregistered(composition: ObservableComposition) {}
    })

    /** Scopes run inside [block], by owner. */
    fun record(block: () -> Unit): Map<String, Int> {
        counts.clear(); detailed.clear(); invalidations.clear(); readers.clear(); recording = true
        try { block() } finally { recording = false }
        return LinkedHashMap(counts)
    }

    override fun close() { handles.forEach { it.dispose() }; registration.dispose() }

    companion object {
        fun attach(view: View) = RecompositionProbe(view.findViewTreeCompositionContext() as Recomposer)
        fun total(counts: Map<String, Int>) = counts.values.sum()
        fun describe(counts: Map<String, Int>) = counts.entries.sortedByDescending { it.value }.joinToString { "${it.key}=${it.value}" }
    }
}
