// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.google.accompanist.systemuicontroller.rememberSystemUiController

@Composable
fun AppTheme(useDarkTheme: Boolean = isSystemInDarkTheme(), content: @Composable() () -> Unit) {
  val colors =
      if (useDarkTheme) {
        DarkColors
      } else {
        LightColors
      }

  val typography =
      Typography(
          // titleMedium is styled to be slightly larger than bodyMedium for emphasis
          titleMedium =
              MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 26.sp),
          // bodyMedium is styled to use same line height as titleMedium to ensure even vertical
          // margins in list items.
          bodyMedium = MaterialTheme.typography.bodyMedium.copy(fontSize = 16.sp))

  val systemUiController = rememberSystemUiController()

  DisposableEffect(systemUiController, useDarkTheme) {
    systemUiController.setStatusBarColor(color = colors.surfaceContainer)
    systemUiController.setNavigationBarColor(color = Color.Black)
    onDispose {}
  }

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
        surface = Color(0xFFFFFFFF), // white,
        background = Color(0xFFF7F5F4), // gray-100
        surfaceBright = Color(0xFFFFFFFF), // white
        surfaceContainerLowest = Color(0xFFFFFFFF), // white
        surfaceContainerLow = Color(0xFFF7F5F4), // gray-100
        surfaceContainer = Color(0xFFF7F5F4), // gray-100
        surfaceContainerHigh = Color(0xFFF7F5F4), // gray-100
        surfaceContainerHighest = Color(0xFFF7F5F4), // gray-100
        surfaceVariant = Color(0xFFF7F5F4), // gray-100,
        onSurface = Color(0xFF232222), // gray-800
        onSurfaceVariant = Color(0xFF706E6D), // gray-500
        outline = Color(0xFF706E6D), // gray-500
        outlineVariant = Color(0xFFEDEBEA), // gray-200
        inverseSurface = Color(0xFF232222), // gray-800
        inverseOnSurface = Color(0xFFFFFFFF), // white
        scrim = Color(0xAA000000), // black
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFF3f5eb3), // blue-600
        onPrimary = Color(0xFFFFFFFF), // white
        primaryContainer = Color(0xFFf0f5ff), // blue-0
        onPrimaryContainer = Color(0xFF3f5eb3), // blue-600
        error = Color(0xFF940822), // red-600
        onError = Color(0xFFFFFFFF), // white
        errorContainer = Color(0xFFfff6f4), // red-0
        onErrorContainer = Color(0xFF940822), // red-600
        surfaceDim = Color(0xFF1f1e1e), // gray-900
        surface = Color(0xFF232222), // gray-800
        background = Color(0xFF1f1e1e), // gray-900
        surfaceBright = Color(0xFF444342), // gray-600
        surfaceContainerLowest = Color(0xFF232222), // gray-800
        surfaceContainerLow = Color(0xFF2e2d2d), // gray-700
        surfaceContainer = Color(0xFF2e2d2d), // gray-700
        surfaceContainerHigh = Color(0xFF2e2d2d), // gray-700
        surfaceContainerHighest = Color(0xFF444342), // gray-600
        surfaceVariant = Color(0xFF1f1e1e), // gray-900
        onSurface = Color(0xFFfaf9f8), // gray-0
        onSurfaceVariant = Color(0xFFafacab), // gray-400
        outline = Color(0xFF706E6D), // gray-500
        outlineVariant = Color(0xFF444342), // gray-600
        inverseSurface = Color(0xFFEDEBEA), // gray-200
        inverseOnSurface = Color(0xFF000000), // black
        scrim = Color(0xAA000000), // black
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

val ColorScheme.on: Color
  get() = Color(0xFF1CA672) // green-300

val ColorScheme.off: Color
  @Composable
  get() =
      if (isSystemInDarkTheme()) {
        Color(0xFFAFACAB) // gray-400
      } else {
        Color(0xFFD9D6D5) // gray-300
      }

val ColorScheme.link: Color
  get() = onPrimaryContainer

/**
 * Main color scheme for list items, uses onPrimaryContainer color for leading and trailing icons.
 */
val ColorScheme.listItem: ListItemColors
  @Composable
  get() {
    val default = ListItemDefaults.colors()
    return ListItemColors(
        containerColor = default.containerColor,
        headlineColor = default.headlineColor,
        leadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        overlineColor = default.overlineColor,
        supportingTextColor = default.supportingTextColor,
        trailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        disabledHeadlineColor = default.disabledHeadlineColor,
        disabledLeadingIconColor = default.disabledLeadingIconColor,
        disabledTrailingIconColor = default.disabledTrailingIconColor)
  }

/** Like listItem, but with the overline content using the onSurface color. */
val ColorScheme.titledListItem: ListItemColors
  @Composable
  get() {
    val default = listItem
    return ListItemColors(
        containerColor = default.containerColor,
        headlineColor = default.headlineColor,
        leadingIconColor = default.leadingIconColor,
        overlineColor = MaterialTheme.colorScheme.onSurface,
        supportingTextColor = default.supportingTextColor,
        trailingIconColor = default.trailingIconColor,
        disabledHeadlineColor = default.disabledHeadlineColor,
        disabledLeadingIconColor = default.disabledLeadingIconColor,
        disabledTrailingIconColor = default.disabledTrailingIconColor)
  }

/** Color scheme for disabled list items. */
val ColorScheme.disabledListItem: ListItemColors
  @Composable
  get() {
    val default = ListItemDefaults.colors()
    return ListItemColors(
        containerColor = default.containerColor,
        headlineColor = MaterialTheme.colorScheme.disabled,
        leadingIconColor = default.leadingIconColor,
        overlineColor = default.overlineColor,
        supportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        trailingIconColor = default.trailingIconColor,
        disabledHeadlineColor = default.disabledHeadlineColor,
        disabledLeadingIconColor = default.disabledLeadingIconColor,
        disabledTrailingIconColor = default.disabledTrailingIconColor)
  }

/** Color scheme for list items that should be styled as a surface container. */
val ColorScheme.surfaceContainerListItem: ListItemColors
  @Composable
  get() {
    val default = ListItemDefaults.colors()
    return ListItemColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        headlineColor = MaterialTheme.colorScheme.onSurface,
        leadingIconColor = MaterialTheme.colorScheme.onSurface,
        overlineColor = MaterialTheme.colorScheme.onSurfaceVariant,
        supportingTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        trailingIconColor = MaterialTheme.colorScheme.onSurface,
        disabledHeadlineColor = default.disabledHeadlineColor,
        disabledLeadingIconColor = default.disabledLeadingIconColor,
        disabledTrailingIconColor = default.disabledTrailingIconColor)
  }

/** Color scheme for list items that should be styled as a primary item. */
val ColorScheme.primaryListItem: ListItemColors
  @Composable
  get() {
    val default = ListItemDefaults.colors()
    return ListItemColors(
        containerColor = MaterialTheme.colorScheme.primary,
        headlineColor = MaterialTheme.colorScheme.onPrimary,
        leadingIconColor = MaterialTheme.colorScheme.onPrimary,
        overlineColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
        supportingTextColor = MaterialTheme.colorScheme.onPrimary,
        trailingIconColor = MaterialTheme.colorScheme.onPrimary,
        disabledHeadlineColor = default.disabledHeadlineColor,
        disabledLeadingIconColor = default.disabledLeadingIconColor,
        disabledTrailingIconColor = default.disabledTrailingIconColor)
  }

/** Color scheme for list items that should be styled as a warning item. */
val ColorScheme.warningListItem: ListItemColors
  @Composable
  get() {
    val default = ListItemDefaults.colors()
    return ListItemColors(
        containerColor = MaterialTheme.colorScheme.warning,
        headlineColor = MaterialTheme.colorScheme.onPrimary,
        leadingIconColor = MaterialTheme.colorScheme.onPrimary,
        overlineColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
        supportingTextColor = MaterialTheme.colorScheme.onPrimary,
        trailingIconColor = MaterialTheme.colorScheme.onPrimary,
        disabledHeadlineColor = default.disabledHeadlineColor,
        disabledLeadingIconColor = default.disabledLeadingIconColor,
        disabledTrailingIconColor = default.disabledTrailingIconColor)
  }

/** Main color scheme for top app bar, styles it as a surface container. */
@OptIn(ExperimentalMaterial3Api::class)
val ColorScheme.topAppBar: TopAppBarColors
  @Composable
  get() =
      TopAppBarDefaults.topAppBarColors()
          .copy(
              containerColor = MaterialTheme.colorScheme.surfaceContainer,
              navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
              titleContentColor = MaterialTheme.colorScheme.onSurface,
          )

val ColorScheme.secondaryButton: ButtonColors
  @Composable
  get() {
    val defaults = ButtonDefaults.buttonColors()
    return ButtonColors(
        containerColor = Color(0xFF6D94EC), // blue-400
        contentColor = Color(0xFFFFFFFF), // white
        disabledContainerColor = defaults.disabledContainerColor,
        disabledContentColor = defaults.disabledContentColor)
  }

val ColorScheme.defaultTextColor: Color
  @Composable
  get() =
      if (isSystemInDarkTheme()) {
        Color.White
      } else {
        Color.Black
      }

val ColorScheme.logoBackground: Color
  @Composable
  get() =
      if (isSystemInDarkTheme()) {
        Color(0xFFFFFFFF) // white
      } else {
        Color(0xFF1F1E1E)
      }

val ColorScheme.standaloneLogoDotEnabled: Color
  @Composable
  get() =
      if (isSystemInDarkTheme()) {
        Color(0xFFFFFFFF)
      } else {
        Color(0xFF000000)
      }

val ColorScheme.standaloneLogoDotDisabled: Color
  @Composable
  get() =
      if (isSystemInDarkTheme()) {
        Color(0x66FFFFFF)
      } else {
        Color(0x661F1E1E)
      }

val ColorScheme.onBackgroundLogoDotEnabled: Color
  @Composable
  get() =
      if (isSystemInDarkTheme()) {
        Color(0xFF141414)
      } else {
        Color(0xFFFFFFFF)
      }

val ColorScheme.onBackgroundLogoDotDisabled: Color
  @Composable
  get() =
      if (isSystemInDarkTheme()) {
        Color(0x66141414)
      } else {
        Color(0x66FFFFFF)
      }

val ColorScheme.disabled: Color
  get() = Color(0xFFAFACAB) // gray-400

@OptIn(ExperimentalMaterial3Api::class)
val ColorScheme.searchBarColors: TextFieldColors
  @Composable
  get() {
    val defaults = OutlinedTextFieldDefaults.colors()
    return OutlinedTextFieldDefaults.colors(
        focusedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurface,
        focusedTextColor = MaterialTheme.colorScheme.onSurface,
        unfocusedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        disabledTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        focusedBorderColor = Color.Transparent,
        unfocusedBorderColor = Color.Transparent)
  }

val TextStyle.short: TextStyle
  get() = copy(lineHeight = 20.sp)

val Typography.minTextSize: TextUnit
  get() = 10.sp
