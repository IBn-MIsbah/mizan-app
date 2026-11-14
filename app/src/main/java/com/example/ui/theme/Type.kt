package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

val MizanDisplayFont = FontFamily(Font(R.font.plus_jakarta_sans, FontWeight.SemiBold))

val MizanBodyFont = FontFamily(Font(R.font.inter, FontWeight.Normal))

val MizanTypography =
  Typography(
    displaySmall =
      TextStyle(
        fontFamily = MizanDisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
      ),
    headlineMedium =
      TextStyle(
        fontFamily = MizanDisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.25).sp,
      ),
    headlineSmall =
      TextStyle(
        fontFamily = MizanDisplayFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
      ),
    titleLarge =
      TextStyle(
        fontFamily = MizanDisplayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp,
      ),
    titleMedium =
      TextStyle(
        fontFamily = MizanDisplayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp,
      ),
    bodyLarge =
      TextStyle(
        fontFamily = MizanBodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
      ),
    bodyMedium =
      TextStyle(
        fontFamily = MizanBodyFont,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.2.sp,
      ),
    labelLarge =
      TextStyle(
        fontFamily = MizanDisplayFont,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
      ),
    labelMedium =
      TextStyle(
        fontFamily = MizanBodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
      ),
    labelSmall =
      TextStyle(
        fontFamily = MizanBodyFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
      ),
  )
