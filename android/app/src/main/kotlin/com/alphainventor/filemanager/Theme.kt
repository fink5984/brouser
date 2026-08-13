package com.alphainventor.filemanager

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val BrowserBlue = Color(0xFF1A73E8)
private val BrowserBlueDark = Color(0xFF8AB4F8)

private val LightColors = lightColorScheme(
    primary = BrowserBlue,
    onPrimary = Color.White,
    secondary = Color(0xFF5F6368),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFF7F8FA),
    surfaceVariant = Color(0xFFECEEF1),
    onSurface = Color(0xFF1F1F1F),
)

private val DarkColors = darkColorScheme(
    primary = BrowserBlueDark,
    onPrimary = Color(0xFF002E69),
    secondary = Color(0xFF9AA0A6),
    background = Color(0xFF131314),
    surface = Color(0xFF1E1F20),
    surfaceVariant = Color(0xFF2B2C2E),
    onSurface = Color(0xFFE3E3E3),
)

@Composable
fun ManagedBrowserTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            window.navigationBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(colorScheme = colorScheme, typography = BrowserTypography, content = content)
}
