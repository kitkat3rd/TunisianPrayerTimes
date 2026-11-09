package com.tunisianprayertimes.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.tunisianprayertimes.R

/**
 * The app's one UI font on every screen: IBM Plex Sans Arabic (SIL Open Font License,
 * assets/font-licenses/IBMPlexSansArabic-OFL.txt), chosen by the owner on 2026-11-08 from docs/font-proposals.html.
 * Its width is close to the system font the tabs used before, and it draws its own Western digits, all the same width.
 *
 * The four weights the code asks for; Light falls back to Regular and Black to Bold.
 * Text built from a fresh `TextStyle(...)` does not inherit the theme, so it must name this family itself.
 */
internal val AppFontFamily = FontFamily(
    Font(R.font.ibm_plex_sans_arabic_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_arabic_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_arabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibm_plex_sans_arabic_bold, FontWeight.Bold),
)
