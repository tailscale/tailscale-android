// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause


package com.tailscale.ipn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable


private val LightColors = lightColorScheme(
    primary = ts_color_light_primary,
    onPrimary = ts_color_light_background,
    secondary = ts_color_light_secondary,
    onSecondary = ts_color_light_background,
    secondaryContainer = ts_color_light_tintedBackground,
    surface = ts_color_light_background,
)

private val DarkColors = darkColorScheme(
    primary = ts_color_dark_primary,
    onPrimary = ts_color_dark_background,
    secondary = ts_color_dark_secondary,
    onSecondary = ts_color_dark_background,
    secondaryContainer = ts_color_dark_tintedBackground,
    surface = ts_color_dark_background,
)

@Composable
fun AppTheme(
    useDarkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable() () -> Unit
) {
    val colors = if (!useDarkTheme) {
        LightColors
    } else {
        DarkColors
    }

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}