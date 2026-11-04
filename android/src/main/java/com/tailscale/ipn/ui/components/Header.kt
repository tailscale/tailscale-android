// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.ui.theme.topAppBar
import com.tailscale.ipn.util.TSLog

val TAG = "SharedViews"

// Header view for all secondary screens
// @see TopAppBar actions for additional actions (usually a row of icons)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Header(
    @StringRes titleRes: Int = 0,
    title: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    onBack: (() -> Unit)? = null,
    isTv: Boolean,
) {
  val focusRequester = remember { FocusRequester() }

  if (isTv) {
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
                color = MaterialTheme.colorScheme.onSurface,
            )
      },
      colors = MaterialTheme.colorScheme.topAppBar,
      actions = actions,
      navigationIcon = {
        onBack?.let {
          BackArrow(
              action = it,
              focusRequester = focusRequester,
              isTv = isTv,
          )
        }
      },
  )
}
