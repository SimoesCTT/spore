package com.spore.torrent.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val SporeTeal = Color(0xFF009688)
private val SporeTealLight = Color(0xFF80CBC4)
private val SporeCyan = Color(0xFF00ACC1)
private val SporeDarkBg = Color(0xFF001F1C)
private val SporeAmoledBg = Color(0xFF000000)

private val LightColors = lightColorScheme(
    primary = SporeTeal,
    secondary = SporeCyan,
    tertiary = SporeTealLight,
    background = Color(0xFFF5FAF9),
    surface = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = SporeTealLight,
    secondary = SporeCyan,
    tertiary = SporeTeal,
    background = SporeDarkBg,
    surface = Color(0xFF002E2A)
)

private val AmoledColors = darkColorScheme(
    primary = SporeTealLight,
    secondary = SporeCyan,
    tertiary = SporeTeal,
    background = SporeAmoledBg,
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF0A0A0A)
)

@Composable
fun SporeTheme(
    themeMode: String = "SYSTEM",
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val colors = when (themeMode) {
        "LIGHT" -> LightColors
        "DARK" -> DarkColors
        "AMOLED" -> AmoledColors
        else -> if (systemDark) DarkColors else LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = (themeMode == "LIGHT") ||
                (themeMode == "SYSTEM" && !systemDark)
        }
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
