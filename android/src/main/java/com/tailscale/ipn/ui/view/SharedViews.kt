// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.theme.topAppBar
import com.tailscale.ipn.ui.theme.ts_color_light_blue
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.util.TSLog

typealias BackNavigation = () -> Unit

val TAG = "SharedViews"

// Header view for all secondary screens
// @see TopAppBar actions for additional actions (usually a row of icons)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Header(
    @StringRes titleRes: Int = 0,
    title: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onBack: (() -> Unit)? = null
) {
  val focusRequester = remember { FocusRequester() }

  if (isAndroidTV()) {
    LaunchedEffect(focusRequester) {
      try {
        focusRequester.requestFocus()
      } catch (e: Exception) {
        TSLog.d(TAG, "Focus request failed")
      }
    }
  }

  TopAppBar(
      title = {
        title?.let { title() }
            ?: Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface)
      },
      colors = MaterialTheme.colorScheme.topAppBar,
      actions = actions,
      navigationIcon = { onBack?.let { BackArrow(action = it, focusRequester = focusRequester) } },
  )
}

@Composable
fun BackArrow(action: () -> Unit, focusRequester: FocusRequester) {
  val isFocused = remember { mutableStateOf(false) }

  val boxModifier =
      if (isAndroidTV()) {
        Modifier.focusRequester(focusRequester)
            .clip(CircleShape) // Ensure both the focus and click area are circular
            .background(
                if (isFocused.value) MaterialTheme.colorScheme.surface else Color.Transparent,
            )
            .onFocusChanged { focusState -> isFocused.value = focusState.isFocused }
            .focusable()
      } else {
        Modifier
      }

  val iconModifier =
      Modifier.clickable(
          interactionSource = remember { MutableInteractionSource() },
          indication = ripple(bounded = false, radius = 24.dp),
          onClick = action)

  Box(modifier = boxModifier.padding(start = 8.dp, end = 8.dp)) {
    Icon(
        Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Go back to the previous screen",
        modifier = iconModifier)
  }
}

@Composable
fun CheckedIndicator() {
  Icon(Icons.Default.CheckCircle, null, tint = ts_color_light_blue)
}

@Composable
fun SimpleActivityIndicator(size: Int = 32) {
  CircularProgressIndicator(
      modifier = Modifier.width(size.dp),
  )
}

@Composable
fun ActivityIndicator(progress: Double, size: Int = 32) {
  LinearProgressIndicator(
      progress = progress.toFloat(),
      modifier = Modifier.width(size.dp),
      color = ts_color_light_blue,
      trackColor = MaterialTheme.colorScheme.secondary,
  )
}
