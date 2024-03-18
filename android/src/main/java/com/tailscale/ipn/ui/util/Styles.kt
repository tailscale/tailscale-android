// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun settingsRowModifier(): Modifier {
    return Modifier
            .clip(shape = RoundedCornerShape(8.dp))
            .background(color = MaterialTheme.colorScheme.secondaryContainer)
            .fillMaxWidth()
}

@Composable
fun defaultPaddingModifier(): Modifier {
    return Modifier.padding(8.dp)
}

@Composable
fun Header(@StringRes title: Int) {
    Text(
            text = stringResource(id = title),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleMedium
    )
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
fun LoadingIndicator(size: Int = 32) {
    CircularProgressIndicator(
            modifier = Modifier.width(size.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.secondary,
    )
}
