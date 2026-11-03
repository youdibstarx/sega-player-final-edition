package com.segaiptv.player

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

internal val supportedLanguages = linkedMapOf("ar" to "العربية", "en" to "English", "fr" to "Français", "es" to "Español")
internal data class AppLanguage(val code: String, val select: (String) -> Unit)
internal val LocalAppLanguage = staticCompositionLocalOf { AppLanguage("en") {} }
private val LocalTranslatedResources = staticCompositionLocalOf<Resources?> { null }
internal val LocalExitApp = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun LanguageHost(onExit: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemConfig = LocalConfiguration.current
    val prefs = remember { context.getSharedPreferences("segaiptv_ui", Context.MODE_PRIVATE) }
    var language by remember {
        mutableStateOf(prefs.getString("language", null)?.takeIf { it in supportedLanguages }
            ?: systemConfig.locales.get(0)?.language?.takeIf { it in supportedLanguages } ?: "en")
    }
    val config = remember(systemConfig, language) {
        Configuration(systemConfig).apply { setLocale(Locale.forLanguageTag(language)) }
    }
    val localized = remember(context, config) { context.createConfigurationContext(config) }
    // Keep the Activity context and its owners intact for launchers, dialogs and BackHandler.
    // Only string lookup uses the language-specific resources.
    CompositionLocalProvider(LocalTranslatedResources provides localized.resources,
        LocalExitApp provides onExit,
        LocalAppLanguage provides AppLanguage(language) { selected ->
            if (selected in supportedLanguages) {
                prefs.edit().putString("language", selected).apply()
                language = selected
            }
        }) { ViewerHost { content() } }
}

@Composable
internal fun tr(@StringRes id: Int, vararg args: Any): String {
    return (LocalTranslatedResources.current ?: LocalContext.current.resources).getString(id, *args)
}

@Composable
internal fun LanguagePicker(modifier: Modifier = Modifier) {
    val language = LocalAppLanguage.current
    var open by remember { mutableStateOf(false) }
    TvAction(tr(R.string.language) + ": " + supportedLanguages[language.code], modifier) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text(tr(R.string.language)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                supportedLanguages.forEach { (code, label) ->
                    TvAction(label, Modifier.fillMaxWidth(), selected = language.code == code) {
                        language.select(code)
                        open = false
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text(tr(R.string.close)) } })
}

/** Translate fixed app status fragments, leaving provider names and categories untouched. */
@Composable
internal fun statusText(raw: String): String {
    val replacements = mapOf(
        "saved catalogue; refresh unavailable" to fx("cached"),
        "live M3U fallback" to tr(R.string.live_fallback),
        "live catalogue unavailable" to tr(R.string.live_missing),
        "vod M3U fallback" to tr(R.string.vod_fallback),
        "vod catalogue unavailable" to tr(R.string.vod_missing),
        "series M3U fallback" to tr(R.string.series_fallback),
        "series catalogue unavailable" to tr(R.string.series_missing),

        "تعذر الاتصال بالخادم. حاول التحديث من جديد." to tr(R.string.connection_error),
        "تعذر تحميل:" to tr(R.string.load_error),
        "download unavailable" to tr(R.string.download_unavailable),
        "Playlist unavailable" to tr(R.string.download_unavailable),
        "catalogue unavailable; using available M3U data" to tr(R.string.catalogue_unavailable),
        "ملف M3U غير صالح" to tr(R.string.invalid_m3u)
    )
    return replacements.entries.fold(raw) { value, (from, to) -> value.replace(from, to) }
}
