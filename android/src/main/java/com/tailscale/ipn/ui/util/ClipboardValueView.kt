// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R

@Composable
fun ClipboardValueView(value: String, title: String? = null, subtitle: String? = null) {
    val isFocused = remember { mutableStateOf(false) }
    val localClipboardManager = LocalClipboardManager.current
    val interactionSource = remember { MutableInteractionSource() }

    ListItem(
        modifier = Modifier
            .focusable(interactionSource = interactionSource) 
            .onFocusChanged { focusState -> isFocused.value = focusState.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current 
            ) { localClipboardManager.setText(AnnotatedString(value)) }
            .background(
                if (isFocused.value) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else Color.Transparent
            ),
        overlineContent = title?.let { { Text(it, style = MaterialTheme.typography.titleMedium) } },
        headlineContent = { Text(text = value, style = MaterialTheme.typography.bodyMedium) },
        supportingContent = subtitle?.let {
            { Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
        },
        trailingContent = {
            Icon(
                painterResource(R.drawable.clipboard),
                contentDescription = stringResource(R.string.copy_to_clipboard),
                modifier = Modifier.size(24.dp)
            )
        }
    )
}