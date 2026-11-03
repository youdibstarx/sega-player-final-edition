package com.segaiptv.player

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Stable IDs are persisted so adding/reordering themes never changes an existing choice. */
internal enum class AppTheme(val id: String, val label: Int, val accent: Color,
    val background: Color, val surface: Color, val panel: Color, val selected: Color,
    val row: Color, val tileTop: Color, val tileBottom: Color, val wallpaper: Int) {
    CLASSIC("classic", R.string.theme_classic, Color(0xFFFF858C),
        Color(0xFF08090B), Color(0xFF191C20), Color(0xFF15181C), Color(0xFF54272D),
        Color(0xFF202327), Color(0xFF5A252D), Color(0xFF25171B), R.drawable.theme_carbon),
    OCEAN("ocean", R.string.theme_ocean, Color(0xFF80CEFF),
        Color(0xFF06121E), Color(0xFF142C41), Color(0xFF102438), Color(0xFF204C69),
        Color(0xFF19364D), Color(0xFF225A7D), Color(0xFF102B45), R.drawable.theme_ocean),
    EMERALD("emerald", R.string.theme_emerald, Color(0xFF84E0B8),
        Color(0xFF071610), Color(0xFF17382C), Color(0xFF112B22), Color(0xFF285441),
        Color(0xFF1B3E30), Color(0xFF28684D), Color(0xFF133526), R.drawable.theme_emerald);

    companion object {
        fun fromId(id: String?): AppTheme = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

internal data class ThemeSelection(val theme: AppTheme, val select: (AppTheme) -> Unit)
internal val LocalThemeSelection = staticCompositionLocalOf { ThemeSelection(AppTheme.CLASSIC) {} }
internal val appTheme: AppTheme
    @Composable get() = LocalThemeSelection.current.theme

@Composable
internal fun ThemeHost(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { context.getSharedPreferences("segaiptv_ui", Context.MODE_PRIVATE) }
    var theme by remember(prefs) { mutableStateOf(AppTheme.fromId(prefs.getString("theme", null))) }
    CompositionLocalProvider(LocalThemeSelection provides ThemeSelection(theme) { chosen ->
        prefs.edit().putString("theme", chosen.id).apply()
        theme = chosen
    }) { content() }
}

@Composable
internal fun ThemePicker(modifier: Modifier = Modifier) {
    val selection = LocalThemeSelection.current
    var open by remember { mutableStateOf(false) }
    TvAction(tr(R.string.appearance_theme) + ": " + tr(selection.theme.label), modifier) { open = true }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(tr(R.string.appearance_theme)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(R.string.theme_hint), fontSize = 12.sp)
                AppTheme.entries.forEach { option ->
                    Row(Modifier.fillMaxWidth().background(option.background).padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.width(16.dp)) {
                            Box(Modifier.size(16.dp).background(option.accent))
                            Box(Modifier.size(16.dp).background(option.surface))
                        }
                        TvAction(tr(option.label), Modifier.weight(1f), selection.theme == option) {
                            selection.select(option)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(tr(R.string.close)) } }
    )
}

