package com.guixing.teleprompter

import androidx.compose.ui.text.font.FontFamily

fun TeleprompterFontFamily.toComposeFontFamily(): FontFamily = when (this) {
    TeleprompterFontFamily.SansSerif -> FontFamily.SansSerif
    TeleprompterFontFamily.Serif -> FontFamily.Serif
}
