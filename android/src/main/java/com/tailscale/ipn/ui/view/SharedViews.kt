// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.theme.topAppBar
import com.tailscale.ipn.ui.theme.ts_color_light_blue

typealias BackNavigation = () -> Unit

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
      navigationIcon = { onBack?.let { BackArrow(action = it) } },
  )
}

@Composable
fun BackArrow(action: () -> Unit) {
  Box(modifier = Modifier.padding(start = 8.dp, end = 8.dp)) {
    Icon(
        Icons.AutoMirrored.Filled.ArrowBack,
        contentDescription = "Go back to the previous screen",
        modifier =
            Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = rememberRipple(bounded = false),
                onClick = { action() }))
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
      progress = { progress.toFloat() },
      modifier = Modifier.width(size.dp),
      color = ts_color_light_blue,
      trackColor = MaterialTheme.colorScheme.secondary,
  )
}
