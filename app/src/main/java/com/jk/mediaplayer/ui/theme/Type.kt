package com.jk.mediaplayer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jk.mediaplayer.R

/**
 * Inter, instanced to a static file per weight.
 *
 * The app previously pointed a single variable font at six weights. Android then interpolated the
 * weight axis and Skia rasterised every new glyph from that interpolated outline on the raster
 * thread, which cost enough time to miss frames while the library scrolled. Static instances keep
 * the same shapes with a plain outline, so the look is identical and the raster work disappears.
 *
 * The files also keep the `tnum` feature, which the first subset dropped. Numbers that change while
 * the user watches (elapsed time, volume percent) then advance by the same amount for every digit,
 * so the text keeps its width instead of wobbling between `0:11` and `0:12`.
 */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
    Font(R.font.inter_black, FontWeight.Black)
)

/** Font feature string that makes digits advance by a fixed width. */
const val TABULAR_FIGURES = "tnum"

/**
 * Application type ramp, expressed in sp.
 *
 * The scale pairs larger display sizes with tighter tracking and smaller text sizes with near
 * neutral tracking, so headings read as headings while list rows and body text stay calm. Inter is
 * metrically close to the system sans, which keeps the same rhythm across devices. Large titles
 * stay 700, section headers 600, list rows and body text 400, and the tab bar 500 at 10 sp.
 */
fun getAppTypography(useSystemFont: Boolean = false): Typography {
    val family = if (useSystemFont) FontFamily.Default else InterFamily

    fun style(
        size: Float,
        weight: FontWeight,
        lineHeight: Float,
        tracking: Float
    ) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.sp
    )

    return Typography(
        // Large Title 34 / Title 1 28 / Hero 24
        displayLarge = style(34f, FontWeight.Bold, 37f, 0.37f),
        displayMedium = style(28f, FontWeight.Bold, 32f, 0.36f),
        displaySmall = style(24f, FontWeight.Bold, 29f, -0.2f),
        // Title 2 22 / Title 3 20 / Now Playing 18
        headlineLarge = style(22f, FontWeight.Bold, 26f, -0.26f),
        headlineMedium = style(20f, FontWeight.SemiBold, 24f, -0.15f),
        headlineSmall = style(18f, FontWeight.SemiBold, 23f, -0.1f),
        // Headline 17 / Body 17 / Subheadline 15
        titleLarge = style(17f, FontWeight.SemiBold, 21f, -0.43f),
        titleMedium = style(17f, FontWeight.Normal, 25f, -0.43f),
        titleSmall = style(15f, FontWeight.Normal, 20f, -0.24f),
        bodyLarge = style(17f, FontWeight.Normal, 25f, -0.43f),
        bodyMedium = style(15f, FontWeight.Normal, 20f, -0.24f),
        // Footnote 13
        bodySmall = style(13f, FontWeight.Normal, 17f, -0.08f),
        // Button 17 / Caption 1 12 / Tab 10
        labelLarge = style(17f, FontWeight.SemiBold, 17f, -0.43f),
        labelMedium = style(12f, FontWeight.Normal, 16f, 0f),
        labelSmall = style(10f, FontWeight.Medium, 10f, -0.24f)
    )
}

val AppTypography = getAppTypography(false)
