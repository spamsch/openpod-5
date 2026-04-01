package com.openpod.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Hero glucose text style used for the primary glucose reading on the
 * dashboard. Not part of the Material Typography scale — import directly.
 */
val HeroGlucoseStyle = TextStyle(
    fontSize = 72.sp,
    lineHeight = 76.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
)

val OpenPodTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 56.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 60.sp,
    ),
    displayMedium = TextStyle(
        fontSize = 44.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 52.sp,
    ),
    displaySmall = TextStyle(
        fontSize = 32.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 40.sp,
    ),
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
    ),
)
