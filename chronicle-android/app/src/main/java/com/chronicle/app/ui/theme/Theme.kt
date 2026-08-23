package com.chronicle.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/** Rose / glass dark scheme — mirrors tokens.css [data-theme=dark]. */
private val DarkColorScheme = darkColorScheme(
    primary = DarkAccent,
    onPrimary = Color(0xFF2A0E12),
    primaryContainer = Color(0xFF7A1028),
    onPrimaryContainer = DarkInk,
    secondary = DarkDream,
    onSecondary = Color(0xFF2A0E12),
    secondaryContainer = Color(0xFF4A1828),
    onSecondaryContainer = DarkInk,
    tertiary = DarkReflection,
    onTertiary = Color(0xFF00382E),
    tertiaryContainer = Color(0xFF1F5C4E),
    onTertiaryContainer = Color(0xFFB8E6DA),
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkSurfaceSolid,
    onSurface = DarkInk,
    surfaceVariant = DarkSurfaceContainerHigh,
    onSurfaceVariant = DarkMuted,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkDanger,
    onError = Color(0xFF2A0E12),
    errorContainer = Color(0xFF5C1020),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Rose / glass light scheme — mirrors tokens.css :root. */
private val LightColorScheme = lightColorScheme(
    primary = LightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE4EA),
    onPrimaryContainer = Color(0xFF2A0E12),
    secondary = LightDream,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5D8E4),
    onSecondaryContainer = Color(0xFF2A0E12),
    tertiary = LightReflection,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8EBD6),
    onTertiaryContainer = Color(0xFF00201A),
    background = LightBg,
    onBackground = LightInk,
    surface = LightSurfaceSolid,
    onSurface = LightInk,
    surfaceVariant = LightSurfaceContainerHigh,
    onSurfaceVariant = LightMuted,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightDanger,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun MyApplicationTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    // Prefer shared rose/glass tokens so phone matches Mac; dynamic Material is opt-in.
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
