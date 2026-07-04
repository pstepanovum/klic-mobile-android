package com.klic.mobile.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.klic.mobile.app.R

// TikTok Sans (curated subset) lives in res/font.
val TikTokSans = FontFamily(
    Font(R.font.tiktoksans_light, FontWeight.Light),
    Font(R.font.tiktoksans_regular, FontWeight.Normal),
    Font(R.font.tiktoksans_medium, FontWeight.Medium),
    Font(R.font.tiktoksans_semibold, FontWeight.SemiBold),
    Font(R.font.tiktoksans_bold, FontWeight.Bold),
    Font(R.font.tiktoksans_black, FontWeight.Black),
)

// Bangers — a bold display face used for the brand tagline.
val Bangers = FontFamily(Font(R.font.bangers_regular, FontWeight.Normal))

// TikTok Sans, 24pt-optical-size Expanded cut — the auth pages' big-title/CTA face
// (Welcome "Get Started", Login/Sign Up titles and primary buttons).
val TikTokSansExpanded = FontFamily(
    Font(R.font.tiktoksans_24pt_expanded_regular, FontWeight.Normal),
    Font(R.font.tiktoksans_24pt_expanded_medium, FontWeight.Medium),
    Font(R.font.tiktoksans_24pt_expanded_bold, FontWeight.Bold),
)

val KlicTypography = Typography(
    displayLarge = TextStyle(fontFamily = TikTokSans, fontWeight = FontWeight.Black, fontSize = 40.sp),
    // §13.2: top-level page titles (Chats, Friends, Calls, Settings and sibling root
    // pages) render in TikTok Sans 24pt Expanded Regular — size/spacing unchanged.
    headlineMedium = TextStyle(fontFamily = TikTokSansExpanded, fontWeight = FontWeight.Normal, fontSize = 28.sp),
    titleLarge = TextStyle(fontFamily = TikTokSans, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = TikTokSans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = TikTokSans, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = TikTokSans, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = TikTokSans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    labelSmall = TextStyle(fontFamily = TikTokSans, fontWeight = FontWeight.Light, fontSize = 12.sp),
)
