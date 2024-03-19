// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Header view for all secondary screens
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Header(@StringRes title: Int) {
  TopAppBar(
      colors =
          TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainer,
              titleContentColor = MaterialTheme.colorScheme.primary,
          ),
      title = { Text(stringResource(title)) })
}

@Composable
fun ChevronRight() {
  Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
}

@Composable
fun CheckedIndicator() {
  Icon(Icons.Default.CheckCircle, null)
}

@Composable
fun SimpleActivityIndicator(size: Int = 32) {
  CircularProgressIndicator(
      modifier = Modifier.width(size.dp),
      color = MaterialTheme.colorScheme.primary,
      trackColor = MaterialTheme.colorScheme.secondary,
  )
}
