package com.chronicle.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.chronicle.app.R

/**
 * Type scale mirroring tokens.css:
 * --font-serif: Lora; --font-sans: Source Sans 3
 * --type-brand 1.35rem · title 1.15rem · body 0.95rem · small 0.8rem · tiny 0.72rem
 *
 * Self-hosted OFL fonts in res/font/.
 */
val JournalSerif =
    FontFamily(
        Font(R.font.lora_regular, FontWeight.Normal),
        Font(R.font.lora_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.lora_semibold, FontWeight.SemiBold),
    )

val ChronicleSans =
    FontFamily(
        Font(R.font.source_sans_3_regular, FontWeight.Normal),
        Font(R.font.source_sans_3_medium, FontWeight.Medium),
        Font(R.font.source_sans_3_semibold, FontWeight.SemiBold),
        Font(R.font.source_sans_3_semibold, FontWeight.Bold),
    )

val Typography =
    Typography(
        displaySmall =
            TextStyle(
                fontFamily = JournalSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp, // ~brand 1.35rem
                lineHeight = 28.sp,
                letterSpacing = (-0.2).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = JournalSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = ChronicleSans,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp, // ~title 1.15rem
                lineHeight = 24.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = ChronicleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = ChronicleSans,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = ChronicleSans,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = JournalSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp, // ~body 0.95rem
                lineHeight = 24.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = ChronicleSans,
                fontWeight = FontWeight.Normal,
                fontSize = 13.sp, // ~small 0.8rem
                lineHeight = 18.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = ChronicleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = ChronicleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp, // ~tiny/small
                lineHeight = 16.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = ChronicleSans,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp, // ~tiny 0.72rem
                lineHeight = 14.sp,
            ),
    )
