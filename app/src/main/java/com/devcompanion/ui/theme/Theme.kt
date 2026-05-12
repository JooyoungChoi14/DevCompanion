package com.devcompanion.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ── Color Presets ─────────────────────────────────────────────────────

enum class ColorPreset(val label: String) {
    DRACULA("Dracula"),
    NORD("Nord"),
    MONOKAI("Monokai"),
    SOLARIZED_LIGHT("Solarized Light"),
}

// ── Dracula ───────────────────────────────────────────────────────────

object DraculaColors {
    val cyan = Color(0xFF8BE9FD)
    val purple = Color(0xFFBD93F9)
    val pink = Color(0xFFFF79C6)
    val green = Color(0xFF50FA7B)
    val red = Color(0xFFFF5555)
    val orange = Color(0xFFFFB86C)
    val yellow = Color(0xFFF1FA8C)

    val background = Color(0xFF1E1E2E)
    val surface = Color(0xFF282A36)
    val surfaceVariant = Color(0xFF2A2A3C)
    val elevated = Color(0xFF343746)

    val textPrimary = Color(0xFFF8F8F2)
    val textSecondary = Color(0xFFC7C5D0)
    val textMuted = Color(0xFF6272A4)

    val border = Color(0xFF44475A)
}

val DraculaDarkColors = darkColorScheme(
    primary = DraculaColors.cyan,
    onPrimary = Color(0xFF003641),
    primaryContainer = Color(0xFF004D5C),
    onPrimaryContainer = Color(0xFFB4ECFF),
    secondary = DraculaColors.purple,
    onSecondary = Color(0xFF2A0054),
    secondaryContainer = Color(0xFF411A79),
    onSecondaryContainer = Color(0xFFE8DDFF),
    tertiary = DraculaColors.pink,
    onTertiary = Color(0xFF5D0028),
    tertiaryContainer = Color(0xFF7D2953),
    onTertiaryContainer = Color(0xFFFFD8E8),
    error = DraculaColors.red,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = DraculaColors.background,
    onBackground = DraculaColors.textPrimary,
    surface = DraculaColors.surface,
    onSurface = DraculaColors.textPrimary,
    surfaceVariant = DraculaColors.surfaceVariant,
    onSurfaceVariant = DraculaColors.textSecondary,
    outline = DraculaColors.textMuted,
    outlineVariant = DraculaColors.border,
)

val DraculaLightColors = lightColorScheme(
    primary = Color(0xFF006A74),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF0FF),
    onPrimaryContainer = Color(0xFF001F25),
    secondary = Color(0xFF6B5E7F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2DAFF),
    onSecondaryContainer = Color(0xFF261437),
    tertiary = Color(0xFF8B4D73),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8F0),
    onTertiaryContainer = Color(0xFF380727),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F5FA),
    onBackground = Color(0xFF1A1B2E),
    surface = Color(0xFFFCFCFF),
    onSurface = Color(0xFF1A1B2E),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF7A757F),
    outlineVariant = Color(0xFFCAC4CF),
)

// ── Nord ──────────────────────────────────────────────────────────────

object NordColors {
    val frost = Color(0xFF8FBCBB)
    val auroraPurple = Color(0xFFB48EAD)
    val auroraPink = Color(0xFFBF616A)
    val auroraGreen = Color(0xFFA3BE8C)
    val auroraYellow = Color(0xFFEBCB8B)
    val auroraOrange = Color(0xFFD08770)

    val polarNight0 = Color(0xFF2E3440)
    val polarNight1 = Color(0xFF3B4252)
    val polarNight2 = Color(0xFF434C5E)
    val polarNight3 = Color(0xFF4C566A)

    val snowStorm0 = Color(0xFFD8DEE9)
    val snowStorm1 = Color(0xFFE5E9F0)
    val snowStorm2 = Color(0xFFECEFF4)
}

val NordDarkColors = darkColorScheme(
    primary = NordColors.frost,
    onPrimary = Color(0xFF003538),
    primaryContainer = Color(0xFF004E53),
    onPrimaryContainer = Color(0xFFA0F0F5),
    secondary = NordColors.auroraPurple,
    onSecondary = Color(0xFF36283E),
    secondaryContainer = Color(0xFF4D3F57),
    onSecondaryContainer = Color(0xFFEAD5F5),
    tertiary = NordColors.auroraPink,
    onTertiary = Color(0xFF5F1A1F),
    tertiaryContainer = Color(0xFF7D2F34),
    onTertiaryContainer = Color(0xFFFFDAD7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = NordColors.polarNight0,
    onBackground = NordColors.snowStorm2,
    surface = NordColors.polarNight1,
    onSurface = NordColors.snowStorm2,
    surfaceVariant = NordColors.polarNight2,
    onSurfaceVariant = NordColors.snowStorm0,
    outline = NordColors.polarNight3,
    outlineVariant = Color(0xFF546178),
)

val NordLightColors = lightColorScheme(
    primary = Color(0xFF00696E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9EF1F6),
    onPrimaryContainer = Color(0xFF002022),
    secondary = Color(0xFF6B5E7F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2DAFF),
    onSecondaryContainer = Color(0xFF261437),
    tertiary = Color(0xFF7A5646),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD1),
    onTertiaryContainer = Color(0xFF2E1509),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = NordColors.snowStorm2,
    onBackground = Color(0xFF1A1C2E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C2E),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6D0),
)

// ── Monokai ──────────────────────────────────────────────────────────

object MonokaiColors {
    val yellow = Color(0xFFE6DB74)
    val orange = Color(0xFFFD971F)
    val red = Color(0xFFF92672)
    val purple = Color(0xFFAE81FF)
    val green = Color(0xFFA6E22E)
    val blue = Color(0xFF66D9EF)

    val background = Color(0xFF1E1F1C)
    val surface = Color(0xFF2A2B26)
    val surfaceVariant = Color(0xFF38392F)

    val textPrimary = Color(0xFFF8F8F2)
    val textSecondary = Color(0xFFBCBCBC)
    val textMuted = Color(0xFF75715E)

    val border = Color(0xFF49483E)
}

val MonokaiDarkColors = darkColorScheme(
    primary = MonokaiColors.yellow,
    onPrimary = Color(0xFF3A3200),
    primaryContainer = Color(0xFF534B00),
    onPrimaryContainer = Color(0xFFFFE170),
    secondary = MonokaiColors.purple,
    onSecondary = Color(0xFF32005A),
    secondaryContainer = Color(0xFF4B1A78),
    onSecondaryContainer = Color(0xFFEEDBFF),
    tertiary = MonokaiColors.red,
    onTertiary = Color(0xFF5E0029),
    tertiaryContainer = Color(0xFF7D2945),
    onTertiaryContainer = Color(0xFFFFD9E2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = MonokaiColors.background,
    onBackground = MonokaiColors.textPrimary,
    surface = MonokaiColors.surface,
    onSurface = MonokaiColors.textPrimary,
    surfaceVariant = MonokaiColors.surfaceVariant,
    onSurfaceVariant = MonokaiColors.textSecondary,
    outline = MonokaiColors.textMuted,
    outlineVariant = MonokaiColors.border,
)

val MonokaiLightColors = lightColorScheme(
    primary = Color(0xFF6B5F00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF5E44B),
    onPrimaryContainer = Color(0xFF201C00),
    secondary = Color(0xFF6B5E7F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2DAFF),
    onSecondaryContainer = Color(0xFF261437),
    tertiary = Color(0xFF7A5646),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD1),
    onTertiaryContainer = Color(0xFF2E1509),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFBF0),
    onBackground = Color(0xFF1D1B10),
    surface = Color(0xFFFFFBF0),
    onSurface = Color(0xFF1D1B10),
    surfaceVariant = Color(0xFFE9E2CA),
    onSurfaceVariant = Color(0xFF4A4634),
    outline = Color(0xFF7C7663),
    outlineVariant = Color(0xFFCBC6B0),
)

// ── Solarized Light ───────────────────────────────────────────────────

object SolarizedColors {
    val base03 = Color(0xFF002B36)
    val base02 = Color(0xFF073642)
    val base01 = Color(0xFF586E75)
    val base00 = Color(0xFF657B83)
    val base0 = Color(0xFF839496)
    val base1 = Color(0xFF93A1A1)
    val base2 = Color(0xFFEEE8D5)
    val base3 = Color(0xFFFDF6E3)

    val yellow = Color(0xFFB58900)
    val orange = Color(0xFFCB4B16)
    val red = Color(0xFFDC322F)
    val magenta = Color(0xFFD33682)
    val violet = Color(0xFF6C71C4)
    val blue = Color(0xFF268BD2)
    val cyan = Color(0xFF2AA198)
    val green = Color(0xFF859900)
}

val SolarizedLightColors = lightColorScheme(
    primary = SolarizedColors.blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0E4FF),
    onPrimaryContainer = Color(0xFF001C38),
    secondary = SolarizedColors.violet,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3DFFF),
    onSecondaryContainer = Color(0xFF1D0E5E),
    tertiary = SolarizedColors.magenta,
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD9E7),
    onTertiaryContainer = Color(0xFF3B0024),
    error = SolarizedColors.red,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = SolarizedColors.base3,
    onBackground = SolarizedColors.base03,
    surface = SolarizedColors.base3,
    onSurface = SolarizedColors.base03,
    surfaceVariant = SolarizedColors.base2,
    onSurfaceVariant = SolarizedColors.base01,
    outline = SolarizedColors.base00,
    outlineVariant = SolarizedColors.base1,
)

val SolarizedDarkColors = darkColorScheme(
    primary = SolarizedColors.cyan,
    onPrimary = Color(0xFF003738),
    primaryContainer = Color(0xFF004F51),
    onPrimaryContainer = Color(0xFF9CF0F2),
    secondary = SolarizedColors.violet,
    onSecondary = Color(0xFF2D1B6E),
    secondaryContainer = Color(0xFF443587),
    onSecondaryContainer = Color(0xFFE0D0FF),
    tertiary = SolarizedColors.magenta,
    onTertiary = Color(0xFF5C0036),
    tertiaryContainer = Color(0xFF7D2050),
    onTertiaryContainer = Color(0xFFFFD9E4),
    error = SolarizedColors.red,
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = SolarizedColors.base03,
    onBackground = SolarizedColors.base1,
    surface = SolarizedColors.base02,
    onSurface = SolarizedColors.base1,
    surfaceVariant = Color(0xFF073642),
    onSurfaceVariant = SolarizedColors.base0,
    outline = SolarizedColors.base01,
    outlineVariant = SolarizedColors.base00,
)

// ── DevColors (legacy alias for DraculaColors) ────────────────────────

object DevColors {
    val cyan = DraculaColors.cyan
    val purple = DraculaColors.purple
    val pink = DraculaColors.pink
    val green = DraculaColors.green
    val red = DraculaColors.red
    val orange = DraculaColors.orange
    val yellow = DraculaColors.yellow

    val backgroundDark = DraculaColors.background
    val surfaceDark = DraculaColors.surface
    val surfaceVariantDark = DraculaColors.surfaceVariant
    val elevatedDark = DraculaColors.elevated

    val textPrimary = DraculaColors.textPrimary
    val textSecondary = DraculaColors.textSecondary
    val textMuted = DraculaColors.textMuted

    val border = DraculaColors.border
    val divider = DraculaColors.border
}

// ── Palette resolver ──────────────────────────────────────────────────

fun colorSchemeFor(preset: ColorPreset, darkTheme: Boolean): ColorScheme = when (preset) {
    ColorPreset.DRACULA -> if (darkTheme) DraculaDarkColors else DraculaLightColors
    ColorPreset.NORD -> if (darkTheme) NordDarkColors else NordLightColors
    ColorPreset.MONOKAI -> if (darkTheme) MonokaiDarkColors else MonokaiLightColors
    ColorPreset.SOLARIZED_LIGHT -> if (darkTheme) SolarizedDarkColors else SolarizedLightColors
}

// ── DataStore preferences ─────────────────────────────────────────────

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "theme_prefs")

class ThemePreferences(private val context: Context) {
    companion object {
        private val PRESET_KEY = stringPreferencesKey("color_preset")
        private val DARK_MODE_KEY = stringPreferencesKey("dark_mode") // "system", "dark", "light"
    }

    val preset: Flow<ColorPreset> = context.dataStore.data.map { prefs ->
        try { ColorPreset.valueOf(prefs[PRESET_KEY] ?: ColorPreset.DRACULA.name) }
        catch (_: Exception) { ColorPreset.DRACULA }
    }

    val darkMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[DARK_MODE_KEY] ?: "system"
    }

    suspend fun setPreset(preset: ColorPreset) {
        context.dataStore.edit { it[PRESET_KEY] = preset.name }
    }

    suspend fun setDarkMode(mode: String) {
        context.dataStore.edit { it[DARK_MODE_KEY] = mode }
    }
}

// ── Composition locals ────────────────────────────────────────────────

val LocalThemePreferences = staticCompositionLocalOf<ThemePreferences> {
    error("No ThemePreferences provided")
}

val LocalColorPreset = staticCompositionLocalOf { ColorPreset.DRACULA }

// ── Theme composable ─────────────────────────────────────────────────

@Composable
fun DevCompanionTheme(
    preset: ColorPreset = ColorPreset.DRACULA,
    darkModeOverride: String = "system", // "system", "dark", "light"
    content: @Composable () -> Unit,
) {
    val darkTheme = when (darkModeOverride) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val colorScheme = colorSchemeFor(preset, darkTheme)

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}