package dev.ahmed.savlino.storage

import dev.ahmed.savlino.domain.LinkKeyer
import dev.ahmed.savlino.domain.SuggestionGate
import dev.ahmed.savlino.locale.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Lazy per-install identity and blocking durability boundary, always resolved on IO. */
internal class ClipboardPreferences(private val preferences: AppPreferences,
    keyer: () -> LinkKeyer? = KeystoreLinkKeyer::orNull) {
    private val keyer by lazy(keyer)

    suspend fun load(): SuggestionGate = withContext(Dispatchers.IO) {
        SuggestionGate(preferences.dismissedHashes, keyer ?: LinkKeyer.session)
    }

    /** No durable key: retain session deduplication but require manual inspection. */
    suspend fun store(hashes: Set<String>): Boolean = withContext(Dispatchers.IO) {
        keyer != null && preferences.storeDismissedHashes(hashes)
    }
}
