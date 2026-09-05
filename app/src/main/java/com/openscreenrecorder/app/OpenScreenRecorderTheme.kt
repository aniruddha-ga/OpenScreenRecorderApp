package com.openscreenrecorder.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.google.android.material.color.DynamicColors

private val LightColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE5E5E5),
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF2C2C2C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0F0F0),
    onSecondaryContainer = Color.Black,
    tertiary = Color(0xFF404040),
    onTertiary = Color.White,
    error = Color(0xFFD32F2F),
    onError = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF666666),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFFAFAFA),
    surfaceContainerHighest = Color(0xFFF0F0F0),
    outline = Color(0xFFCCCCCC),
    outlineVariant = Color(0xFFE0E0E0)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF2C2C2C),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFFE5E5E5),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1E1E1E),
    onSecondaryContainer = Color.White,
    tertiary = Color(0xFFCCCCCC),
    onTertiary = Color.Black,
    error = Color(0xFFFF5252),
    onError = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1E1E1E),
    onSurfaceVariant = Color(0xFFA0A0A0),
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color(0xFF121212),
    surfaceContainerHighest = Color(0xFF181818),
    outline = Color(0xFF444444),
    outlineVariant = Color(0xFF2C2C2C)
)

private val AppTypography = Typography(
    displayLarge = Typography().displayLarge.copy(fontFamily = FontFamily.Default),
    displayMedium = Typography().displayMedium.copy(fontFamily = FontFamily.Default),
    displaySmall = Typography().displaySmall.copy(fontFamily = FontFamily.Default),
    headlineLarge = Typography().headlineLarge.copy(fontFamily = FontFamily.Default),
    headlineMedium = Typography().headlineMedium.copy(fontFamily = FontFamily.Default),
    headlineSmall = Typography().headlineSmall.copy(fontFamily = FontFamily.Default),
    titleLarge = Typography().titleLarge.copy(fontFamily = FontFamily.Default),
    titleMedium = Typography().titleMedium.copy(fontFamily = FontFamily.Default),
    titleSmall = Typography().titleSmall.copy(fontFamily = FontFamily.Default),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = FontFamily.Default),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = FontFamily.Default),
    bodySmall = Typography().bodySmall.copy(fontFamily = FontFamily.Default),
    labelLarge = Typography().labelLarge.copy(fontFamily = FontFamily.Default),
    labelMedium = Typography().labelMedium.copy(fontFamily = FontFamily.Default),
    labelSmall = Typography().labelSmall.copy(fontFamily = FontFamily.Default)
)

@Composable
fun OpenScreenRecorderTheme(
    themeMode: String = ConfigManager(LocalContext.current).themeMode,
    isDynamicColorEnabled: Boolean = ConfigManager(LocalContext.current).isDynamicColorsEnabled,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val darkTheme = when (themeMode) {
        ConfigManager.THEME_LIGHT -> false
        ConfigManager.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }

    val useDynamic = isDynamicColorEnabled && DynamicColors.isDynamicColorAvailable()

    val colorScheme = when {
        useDynamic -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
