package com.jk.mediaplayer.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Pure AMOLED Dark Palette
val DarkBackground = Color(0xFF000000)
val DarkBgGradientStart = Color(0xFF0A0A0A)
val DarkBgGradientEnd = Color(0xFF000000)
val DarkSurface = Color(0xFF0F0F0F)
val DarkSurfaceVariant = Color(0xFF1C1C1C)
val DarkBorder = Color(0xFF262626)

val DarkBackgroundBrush = Brush.verticalGradient(
    listOf(DarkBgGradientStart, DarkBgGradientEnd)
)

// Light Palette (White Mode)
val LightBackground = Color(0xFFF7F8FA)
val LightSurface = Color(0xFFFFFFFF)
val LightSurfaceVariant = Color(0xFFEEF0F5)
val LightBorder = Color(0xFFE0E3EB)
val LightTextPrimary = Color(0xFF14151A)
val LightTextSecondary = Color(0xFF6E7180)

// Music Brand Theme (Candy Crimson Red)
val MusicRed = Color(0xFFFA2D48)
val MusicRedDark = Color(0xFFD61834)
val MusicRedGlow = Color(0xFFFF5270)

// Video Brand Theme (Royal Violet-Indigo)
val VideoIndigo = Color(0xFF536DFE)
val VideoViolet = Color(0xFF6C63FF)
val VideoSurfaceDark = Color(0xFF14162B)

// Shared Tokens
val HiResGold = Color(0xFFFFD700)
val TextPrimary = Color(0xFFF2F4F8)
val TextSecondary = Color(0xFF9EA7B8)

/**
 * Album derived surface tint shared by the mini player card and the top of the player
 * background, so the colour does not shift while the player expands.
 */
fun albumSurfaceTint(dominant: Color, isDark: Boolean): Color {
    return if (isDark) {
        val lum = 0.2126f * dominant.red + 0.7152f * dominant.green + 0.0722f * dominant.blue
        val topFactor = if (lum > 0.35f) 0.35f / lum else 1f
        Color(
            red = (dominant.red * topFactor * 0.65f + 0.02f).coerceIn(0f, 1f),
            green = (dominant.green * topFactor * 0.65f + 0.02f).coerceIn(0f, 1f),
            blue = (dominant.blue * topFactor * 0.65f + 0.02f).coerceIn(0f, 1f)
        )
    } else {
        Color(
            red = (dominant.red * 0.28f + 0.72f).coerceIn(0f, 1f),
            green = (dominant.green * 0.28f + 0.72f).coerceIn(0f, 1f),
            blue = (dominant.blue * 0.28f + 0.72f).coerceIn(0f, 1f)
        )
    }
}