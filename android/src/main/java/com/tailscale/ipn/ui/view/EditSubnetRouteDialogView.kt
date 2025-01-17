// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * EditSubnetRouteDialogView is the content of the dialog that allows the user to add or edit a subnet route.
 */
@Composable
fun EditSubnetRouteDialogView(
    valueFlow: MutableStateFlow<String>,
    isValueValidFlow: StateFlow<Boolean>,
    onValueChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit
) {
    val value by valueFlow.collectAsState()
    val isValueValid by isValueValidFlow.collectAsState()
    val focusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier.padding(16.dp),
    ) {
        Text(text = stringResource(R.string.enter_valid_route))

        Text(
            text = stringResource(R.string.route_help_text),
            color = MaterialTheme.colorScheme.secondary,
            fontSize = MaterialTheme.typography.bodySmall.fontSize
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextField(
            value = value,
            onValueChange = { onValueChange(it) },
            singleLine = true,
            isError = !isValueValid,
            modifier = Modifier.focusRequester(focusRequester)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.align(Alignment.End)
        ) {
            Button(colors = ButtonDefaults.outlinedButtonColors(), onClick = {
                onCancel()
            }) {
                Text(stringResource(R.string.cancel))
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(onClick = {
                onCommit(value)
            }, enabled = value.isNotEmpty() && isValueValid) {
                Text(stringResource(R.string.ok))
            }
        }
    }

    // When the dialog is opened, focus on the text field to present the keyboard auto-magically.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { isWindowFocused ->
            if (isWindowFocused) {
                focusRequester.requestFocus()
            }
        }
    }
}