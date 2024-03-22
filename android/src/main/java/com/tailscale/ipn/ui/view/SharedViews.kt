// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

data class BackNavigation(
    val onBack: () -> Unit,
)

// Header view for all secondary screens
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Header(@StringRes title: Int, onBack: (() -> Unit)? = null) {
  TopAppBar(
      colors =
          TopAppBarDefaults.topAppBarColors(
              containerColor = MaterialTheme.colorScheme.surfaceContainer,
              titleContentColor = MaterialTheme.colorScheme.primary,
          ),
      title = { Text(stringResource(title)) },
      navigationIcon = { onBack?.let { BackArrow(action = it) } },
  )
}

@Composable
fun BackArrow(action: () -> Unit) {
  Icon(
      Icons.AutoMirrored.Filled.ArrowBack,
      null,
      modifier = Modifier.clickable { action() }.padding(start = 15.dp, end = 20.dp))
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
