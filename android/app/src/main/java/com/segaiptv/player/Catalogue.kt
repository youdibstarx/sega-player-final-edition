package com.segaiptv.player

import java.net.URI
import java.util.Locale

internal fun isVodEntry(item: Channel, series: Boolean): Boolean {
    val path = runCatching { URI(item.url).path.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
    val type = item.mediaType.lowercase(Locale.ROOT)
    // Category names are display metadata, never proof of media type (Serie A, Cinema TV, etc.).
    if (type == "live") return false
    if (type in setOf("series", "episode")) return series
    if (type in setOf("movie", "vod")) return !series
    if (path.contains("/live/")) return false
    if (path.contains("/series/")) return series
    if (path.contains("/movie/")) return if (episodeLabel(item).episode != null) series else !series
    val videoFile = Regex("""\.(mp4|mkv|avi|m4v|mov)$""").containsMatchIn(path)
    val structuredEpisode = item.seriesTitle.isNotBlank() || item.episode != null
    if (structuredEpisode) return series
    // Name patterns only refine an already identified video file, never a live TS/HLS entry.
    return videoFile && (series == (episodeLabel(item).episode != null))
}

internal data class EpisodeLabel(val title: String, val season: Int?, val episode: Int?)
private val episodePatterns = listOf(
    Regex("""(?i)(?<![\p{L}\d])S(\d{1,3})[\s._-]*E(\d{1,4})\b"""),
    Regex("""(?i)(?<!\d)(\d{1,3})x(\d{1,4})\b"""),
    Regex("""(?i)(?:season|saison|الموسم)\s*(\d{1,3})[\s._-]*(?:episode|épisode|ep|الحلقة)\s*(\d{1,4})\b""")
)
internal fun episodeLabel(name: String): EpisodeLabel {
    val match = episodePatterns.firstNotNullOfOrNull { it.find(name) } ?: return EpisodeLabel(name, null, null)
    return EpisodeLabel(name.substring(0, match.range.first).trim().trimEnd('-', '.', '_', '|').trim().ifBlank { name },
        match.groupValues[1].toIntOrNull(), match.groupValues[2].toIntOrNull())
}
internal fun episodeLabel(item: Channel): EpisodeLabel {
    val parsed = episodeLabel(item.name)
    return EpisodeLabel(item.seriesTitle.ifBlank { parsed.title }, item.season ?: parsed.season, item.episode ?: parsed.episode)
}
internal data class VodTitle(val title: String, val entries: List<Channel>) {
    val cover: String get() = entries.firstOrNull { it.logo.isNotBlank() }?.logo.orEmpty()
    val id: String get() = entries.first().url
}
internal fun buildVodTitles(entries: List<Channel>, series: Boolean): List<VodTitle> {
    val catalogue = entries.filter { isVodEntry(it, series) }.distinctBy { it.url }
    if (!series) return catalogue.map { VodTitle(it.name, listOf(it)) }
    return catalogue.groupBy { Triple(it.playlist, it.category, it.seriesId.ifBlank { episodeLabel(it).title.lowercase(Locale.ROOT) }) }
        .values.map { episodes -> VodTitle(episodeLabel(episodes.first()).title,
            episodes.sortedWith(compareBy({ episodeLabel(it).season ?: 0 }, { episodeLabel(it).episode ?: 0 }))) }
}
