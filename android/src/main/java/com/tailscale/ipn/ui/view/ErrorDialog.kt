// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.ErrorDialogType
import com.tailscale.ipn.ui.theme.AppTheme

@Composable
fun ErrorDialog(type: ErrorDialogType, action: () -> Unit = {}) {
  ErrorDialog(
      title = type.title,
      message = stringResource(id = type.message),
      buttonText = type.buttonText,
      onDismiss = action,
  )
}

@Composable
fun ErrorDialog(
    @StringRes title: Int = R.string.error,
    @StringRes message: Int,
    @StringRes buttonText: Int = R.string.ok,
    onDismiss: () -> Unit = {},
) {
  ErrorDialog(
      title = title,
      message = stringResource(id = message),
      buttonText = buttonText,
      onDismiss = onDismiss,
  )
}

@Composable
fun ErrorDialog(
    @StringRes title: Int = R.string.error,
    message: String,
    @StringRes buttonText: Int = R.string.ok,
    onDismiss: () -> Unit = {},
) {
  AppTheme {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = title)) },
        text = { Text(text = message) },
        confirmButton = {
          PrimaryActionButton(onClick = onDismiss) { Text(text = stringResource(id = buttonText)) }
        },
    )
  }
}

@Preview
@Composable
fun ErrorDialogPreview() {
  ErrorDialog(ErrorDialogType.LOGOUT_FAILED)
}
