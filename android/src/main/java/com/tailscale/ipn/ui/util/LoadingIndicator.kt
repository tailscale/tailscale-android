// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.view.TailscaleLogoView
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

object LoadingIndicator {
  private val loading = MutableStateFlow(false)

  fun start() {
    loading.value = true
  }

  fun stop() {
    loading.value = false
  }

  @Composable
  fun Wrap(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
      content()
      val isLoading = loading.collectAsState().value
      if (isLoading) {
        Box(Modifier.clickable {}.matchParentSize().background(Color.Gray.copy(alpha = 0.0f)))

        val showSpinner: State<Boolean> =
            produceState(initialValue = false) {
              delay(300)
              value = true
            }

        if (showSpinner.value) {
          Column(
              modifier = Modifier.fillMaxWidth(),
              horizontalAlignment = Alignment.CenterHorizontally) {
                TailscaleLogoView(true, usesOnBackgroundColors = false, Modifier.size(72.dp).alpha(0.4f))
              }
        }
      }
    }
  }
}
