package com.segaiptv.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class ProviderCategory(val playlist: Playlist, val kind: String, val id: String, val name: String,
    val sourceOrderKnown: Boolean = true, val channelCount: Int? = null) {
    val key: String get() = "${playlist.id}:$kind:$id"
}

/** Categories first. Never requests an unfiltered live/VOD/series catalogue or get.php. */
internal object CategoryCatalogue {
    fun parseCategories(playlist: Playlist, kind: String, json: String): List<ProviderCategory> {
        require(kind in setOf("live", "vod", "series"))
        val rows = JSONArray(json)
        require(rows.length() <= 10000) { "Catalogue too large" }
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val id = row.optString("category_id")
            val name = row.optString("category_name").takeUnless { it == "null" }.orEmpty()
            if (!id.matches(Regex("[0-9]+")) || name.isBlank()) null
            else {
                val count = listOf("streams_count", "stream_count", "channel_count", "total_streams", "total_count", "count")
                    .firstNotNullOfOrNull { key ->
                        val value = row.opt(key)
                        when (value) {
                            is Number -> value.toInt().takeIf { it >= 0 }
                            is String -> value.toIntOrNull()?.takeIf { it >= 0 }
                            else -> null
                        }
                    }
                ProviderCategory(playlist, kind, id, name, channelCount = count)
            }
        }.distinctBy { it.key }
    }

    suspend fun categories(playlist: Playlist, kind: String,
        fetch: (String) -> String = XtreamCatalogue::request): List<ProviderCategory> = withContext(Dispatchers.IO) {
        val source = requireNotNull(XtreamSource.from(playlist.url))
        parseCategories(playlist, kind, fetch(source.api("get_${kind}_categories")))
    }

    suspend fun entries(category: ProviderCategory,
        fetch: (String) -> String = XtreamCatalogue::request): List<Channel> = withContext(Dispatchers.IO) {
        val source = requireNotNull(XtreamSource.from(category.playlist.url))
        val action = if (category.kind == "series") "get_series" else "get_${category.kind}_streams"
        val json = fetch(source.api(action, category.id, "category_id"))
        val categories = JSONArray().put(JSONObject().put("category_id", category.id).put("category_name", category.name))
        XtreamCatalogue.parseSection(source, category.playlist, category.kind, json, categories.toString())
    }
}

