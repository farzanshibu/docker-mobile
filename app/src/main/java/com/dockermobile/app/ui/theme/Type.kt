package com.dockermobile.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Apple's text-style ladder (large title → caption) expressed in the platform
 * system font. Sizes, leading and the slightly negative tracking come straight
 * from the HIG type scale; the mobile body default is 17pt, which is why the
 * smaller styles here are larger than Material's defaults.
 *
 * Everything is in sp, so the whole ladder scales with the system font-size
 * setting — the platform equivalent of Dynamic Type.
 */
private val Sf = FontFamily.SansSerif

private fun style(
    size: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    tracking: Double = 0.0,
) = TextStyle(
    fontFamily = Sf,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = weight,
    letterSpacing = tracking.sp,
)

val AppleTypography = Typography(
    // Large title — collapsing screen titles.
    displayLarge = style(34, 41, FontWeight.Bold, -0.6),
    displayMedium = style(34, 41, FontWeight.Bold, -0.6),
    displaySmall = style(34, 41, FontWeight.Bold, -0.6),

    // Title 1 / 2 / 3.
    headlineLarge = style(28, 34, FontWeight.Bold, -0.5),
    headlineMedium = style(22, 28, FontWeight.SemiBold, -0.4),
    headlineSmall = style(20, 25, FontWeight.SemiBold, -0.4),

    // Inline nav title, list-row titles, grouped-section headers.
    titleLarge = style(17, 22, FontWeight.SemiBold, -0.4),
    titleMedium = style(17, 22, FontWeight.SemiBold, -0.4),
    titleSmall = style(13, 18, FontWeight.SemiBold, 0.0),

    // Body / callout / subheadline.
    bodyLarge = style(17, 22, FontWeight.Normal, -0.4),
    bodyMedium = style(16, 21, FontWeight.Normal, -0.3),
    bodySmall = style(15, 20, FontWeight.Normal, -0.2),

    // Buttons, footnote, caption.
    labelLarge = style(17, 22, FontWeight.SemiBold, -0.4),
    labelMedium = style(13, 18, FontWeight.Medium, 0.0),
    labelSmall = style(12, 16, FontWeight.Medium, 0.0),
)
