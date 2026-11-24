package com.heliossentinel.companion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = SunAmber,
    onPrimary = SlateDark,
    secondary = SkyBlue,
    background = SlateDark,
    surface = SlateMedium,
    error = DangerRed
)

private val LightColors = lightColorScheme(
    primary = SunAmberDark,
    onPrimary = SlateDark,
    secondary = SkyBlue,
    background = SlateLight,
    surface = Color.White,
    error = DangerRed
)

@Composable
fun HeliosSentinelTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = HeliosTypography,
        content = content
    )
}
