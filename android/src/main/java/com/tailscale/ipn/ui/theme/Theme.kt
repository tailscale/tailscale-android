// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

@Composable
fun AppTheme(useDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable() () -> Unit) {
  val colors =
      if (!useDarkTheme) {
        LightColors
      } else {
        DarkColors
      }

  val typography =
      Typography(
          titleMedium =
              MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
          bodyMedium =
              MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp, lineHeight = 26.sp))

  MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF4B70CC), // blue-500
        onPrimary = Color(0xFFFFFFFF), // white
        primaryContainer = Color(0xFFF0F5FF), // blue-0
        onPrimaryContainer = Color(0xFF3E5DB3), // blue-600
        error = Color(0xFFB22C30), // red-500
        onError = Color(0xFFFFFFFF), // white
        errorContainer = Color(0xFFFEF6F3), // red-0
        onErrorContainer = Color(0xFF930921), // red-600
        surfaceDim = Color(0xFFF7F5F4), // gray-100
        surface = Color(0xFFF7F5F4), // gray-100
        background = Color(0xFFF7F5F4), // gray-100
        surfaceBright = Color(0xFFFFFFFF), // white
        surfaceContainerLowest = Color(0xFFFFFFFF), // white
        surfaceContainerLow = Color(0xFFF7F5F4), // gray-100
        surfaceContainer = Color(0xFFF7F5F4), // gray-100
        surfaceContainerHigh = Color(0xFFF7F5F4), // gray-100
        surfaceContainerHighest = Color(0xFFF7F5F4), // gray-100
        onSurface = Color(0xFF232222), // gray-800
        onSurfaceVariant = Color(0xFF706E6D), // gray-500
        outline = Color(0xFFD9D6D5), // gray-300
        outlineVariant = Color(0xFFEDEBEA), // gray-200
        inverseSurface = Color(0xFF232222), // gray-800
        inverseOnSurface = Color(0xFFFFFFFF), // white
        scrim = Color(0xFF000000), // black
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFFAF9F8),
        onPrimary = Color(0xFFAFACAB),
        secondary = Color(0xFF232222),
        onSecondary = Color(0xFF2E2D2D),
        secondaryContainer = Color(0xFF4B70CC),
        surface = Color(0xFF33C27F),
    )

val ColorScheme.warning: Color
  get() = Color(0xFFD97916) // yellow-300

val ColorScheme.onWarning: Color
  get() = Color(0xFFFFFFFF) // white

val ColorScheme.warningContainer: Color
  get() = Color(0xFFFFFAEE) // orange-0

val ColorScheme.onWarningContainer: Color
  get() = Color(0xFF7E1E22) // orange-600

val ColorScheme.success: Color
  get() = Color(0xFF0A825D) // green-400

val ColorScheme.onSuccess: Color
  get() = Color(0xFFFFFFFF) // white

val ColorScheme.successContainer: Color
  get() = Color(0xFFEFFEEC) // green-0

val ColorScheme.onSuccessContainer: Color
  get() = Color(0xFF0E4B3B) // green-600
