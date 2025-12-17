package com.example.voiceassistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Strawberry-inspired colors
private val StrawberryRed = Color(0xFFE53935)        // Primary red
private val StrawberryLight = Color(0xFFFF6F60)      // Lighter red
private val StrawberryDark = Color(0xFFAB000D)       // Darker red
private val StrawberryPink = Color(0xFFFFCDD2)       // Light pink accent
private val StrawberryCream = Color(0xFFFFF5F5)      // Cream background
private val LeafGreen = Color(0xFF4CAF50)            // Green accent for tertiary

// Dark theme colors
private val StrawberryRedDark = Color(0xFFFF8A80)    // Softer red for dark mode
private val StrawberryPinkDark = Color(0xFFFF5252)   // Pink accent dark
private val DarkSurface = Color(0xFF1A1A1A)          // Dark background
private val DarkSurfaceContainer = Color(0xFF2D2D2D) // Slightly lighter container

private val DarkColorScheme = darkColorScheme(
    primary = StrawberryRedDark,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF93000A),
    onPrimaryContainer = StrawberryPink,
    secondary = StrawberryPinkDark,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF5C3A3A),
    onSecondaryContainer = Color(0xFFFFDAD6),
    tertiary = Color(0xFF81C784),
    surface = DarkSurface,
    surfaceContainer = DarkSurfaceContainer,
    background = DarkSurface,
    error = Color(0xFFFFB4AB)
)

private val LightColorScheme = lightColorScheme(
    primary = StrawberryRed,
    onPrimary = Color.White,
    primaryContainer = StrawberryPink,
    onPrimaryContainer = StrawberryDark,
    secondary = StrawberryLight,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE5E5),
    onSecondaryContainer = Color(0xFF5C1A1A),
    tertiary = LeafGreen,
    surface = StrawberryCream,
    background = StrawberryCream,
    error = Color(0xFFBA1A1A)
)

@Composable
fun StrawberryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
