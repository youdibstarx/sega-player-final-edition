package com.segaiptv.player

import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Catalogue snapshots are immutable; never compare thousands of rows on the UI thread. */
internal class CatalogueIdentity(private val value: Any?) {
    override fun equals(other: Any?) = other is CatalogueIdentity && value === other.value
    override fun hashCode() = System.identityHashCode(value)
}

/** Keep the previous snapshot visible (and focused) while the next one is prepared. */
@Composable
internal fun <T> rememberCatalogueWork(initial: T, vararg inputs: Any?, compute: suspend () -> T): T {
    val keys = inputs.map { if (it is Collection<*> || it is Map<*, *>) CatalogueIdentity(it) else it }.toTypedArray()
    var result by remember { mutableStateOf(initial, referentialEqualityPolicy()) }
    val latest by rememberUpdatedState(compute)
    LaunchedEffect(*keys) {
        val work = latest
        val next = withContext(Dispatchers.Default) {
            val value = work()
            currentCoroutineContext().ensureActive()
            value
        }
        result = next
    }
    return result
}

internal suspend fun localCatalogueGroups(channels: List<Channel>, kind: String, m3uOnly: Boolean): Map<String, List<Channel>> {
    val job = currentCoroutineContext()
    val groups = linkedMapOf<String, MutableList<Channel>>()
    val eligibleSources = mutableMapOf<String, Boolean>()
    channels.forEachIndexed { index, channel ->
        if (index % 128 == 0) job.ensureActive()
        if ((m3uOnly || eligibleSources.getOrPut(channel.sourceUrl) { XtreamSource.from(channel.sourceUrl) == null }) &&
            catalogueKind(channel) == kind) {
            groups.getOrPut(channel.category.ifBlank { "Other" }) { mutableListOf() }.add(channel)
        }
    }
    return groups
}
