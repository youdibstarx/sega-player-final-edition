package com.segaiptv.player

import java.net.URI
import kotlinx.coroutines.CancellationException

/** Provider URL adaptation only: never rewrite stored playlist URLs or device identity. */
internal object PlaylistSource {
    private val htmlSeparator = Regex("&(?:amp;|#0*38;|#x0*26;)(?=[A-Za-z_][A-Za-z0-9_.-]*=)", RegexOption.IGNORE_CASE)
    private val basicType = Regex("(^|&)type=m3u(?=&|$)", RegexOption.IGNORE_CASE)

    fun normalize(url: String): String = url.trim().replace(htmlSeparator, "&")

    fun extendedUrl(url: String): String? {
        val source = normalize(url)
        val uri = runCatching { URI(source) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") ||
            !uri.path.orEmpty().substringAfterLast('/').equals("get.php", true)) return null
        val query = uri.rawQuery ?: return null
        if (!basicType.containsMatchIn(query)) return null
        val upgraded = query.replace(basicType) { "${it.groupValues[1]}type=m3u_plus" }
        // Reuse the raw query, keeping passwords, signatures and output encoding untouched.
        return source.substringBefore('?') + "?" + upgraded + (uri.rawFragment?.let { "#$it" } ?: "")
    }

    fun load(url: String, fetch: (String) -> List<Channel>): List<Channel> {
        val original = normalize(url)
        val extended = extendedUrl(original)
        var plainExtended: List<Channel> = emptyList()
        if (extended != null) {
            try {
                plainExtended = fetch(extended)
                if (plainExtended.any { it.category.isNotBlank() && it.category != "غير مصنف" }) return plainExtended
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A provider may reject m3u_plus. Preserve support for its original URL.
            }
        }
        return try {
            val basic = fetch(original)
            if (basic.any { it.category.isNotBlank() && it.category != "غير مصنف" }) basic
            else plainExtended.ifEmpty { basic }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (plainExtended.isNotEmpty()) plainExtended else throw error
        }
    }
}
