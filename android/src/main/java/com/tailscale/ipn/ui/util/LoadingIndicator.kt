// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                Box(
                    Modifier
                        .matchParentSize()
                        .background(Color.Gray.copy(alpha = 0.5f))
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }

            }
        }
    }
}