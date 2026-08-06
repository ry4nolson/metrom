package com.metrom.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.metrom.app.R

val SyneFamily = FontFamily(
    Font(R.font.syne_variable, weight = FontWeight.Normal),
    Font(R.font.syne_variable, weight = FontWeight.Medium),
    Font(R.font.syne_variable, weight = FontWeight.SemiBold),
    Font(R.font.syne_variable, weight = FontWeight.Bold),
    Font(R.font.syne_variable, weight = FontWeight.ExtraBold),
)

val DmSansFamily = FontFamily(
    Font(R.font.dmsans_variable, weight = FontWeight.Normal),
    Font(R.font.dmsans_variable, weight = FontWeight.Medium),
    Font(R.font.dmsans_variable, weight = FontWeight.SemiBold),
    Font(R.font.dmsans_variable, weight = FontWeight.Bold),
)

val MetromTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SyneFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 96.sp,
        lineHeight = 96.sp,
        letterSpacing = (-2).sp
    ),
    displayMedium = TextStyle(
        fontFamily = SyneFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        letterSpacing = (-1).sp
    ),
    headlineLarge = TextStyle(
        fontFamily = SyneFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SyneFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp
    ),
    titleLarge = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp
    ),
    titleMedium = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 1.2.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp
    ),
    labelLarge = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        letterSpacing = 0.8.sp
    ),
    labelMedium = TextStyle(
        fontFamily = DmSansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        letterSpacing = 1.sp
    )
)
