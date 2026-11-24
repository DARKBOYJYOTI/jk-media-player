package com.jk.mediaplayer.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.jk.mediaplayer.data.PreferencesManager

@Composable
fun JKMediaPlayerTheme(
    isDark: Boolean = true,
    accentColor: Color = MusicRed,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val prefsManager = remember { PreferencesManager(context) }
    val settingsVersion = PreferencesManager.settingsVersion
    val useSystemFont = remember(settingsVersion) { prefsManager.isUseSystemFont() }
    val currentTypography = remember(useSystemFont) { getAppTypography(useSystemFont) }

    val colorScheme = if (isDark) {
        darkColorScheme(
            primary = accentColor,
            onPrimary = Color.White,
            primaryContainer = DarkSurfaceVariant,
            onPrimaryContainer = TextPrimary,
            secondary = accentColor,
            onSecondary = Color.White,
            background = DarkBackground,
            onBackground = TextPrimary,
            surface = DarkSurface,
            onSurface = TextPrimary,
            surfaceVariant = DarkSurfaceVariant,
            onSurfaceVariant = TextSecondary,
            outline = DarkBorder
        )
    } else {
        lightColorScheme(
            primary = accentColor,
            onPrimary = Color.White,
            primaryContainer = LightSurfaceVariant,
            onPrimaryContainer = LightTextPrimary,
            secondary = accentColor,
            onSecondary = Color.White,
            background = LightBackground,
            onBackground = LightTextPrimary,
            surface = LightSurface,
            onSurface = LightTextPrimary,
            surfaceVariant = LightSurfaceVariant,
            onSurfaceVariant = LightTextSecondary,
            outline = LightBorder
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        @Suppress("DEPRECATION")
        DisposableEffect(isDark) {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                    window.isStatusBarContrastEnforced = false
                }
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !isDark
                insetsController.isAppearanceLightNavigationBars = !isDark
            }
            onDispose { }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = currentTypography,
        content = content
    )
}
