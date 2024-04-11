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
import com.tailscale.ipn.ui.theme.AppTheme


enum class ErrorDialogType {
  INVALID_CUSTOM_URL,
  LOGOUT_FAILED,
  SWITCH_USER_FAILED,
  ADD_PROFILE_FAILED,
  SHARE_DEVICE_NOT_CONNECTED,
  SHARE_FAILED;

  val message: Int
    get() {
      return when (this) {
        INVALID_CUSTOM_URL -> R.string.invalidCustomUrl
        LOGOUT_FAILED -> R.string.logout_failed
        SWITCH_USER_FAILED -> R.string.switch_user_failed
        ADD_PROFILE_FAILED -> R.string.add_profile_failed
        SHARE_DEVICE_NOT_CONNECTED -> R.string.share_device_not_connected
        SHARE_FAILED -> R.string.taildrop_share_failed
      }
    }

  val title: Int
    get() {
      return when (this) {
        INVALID_CUSTOM_URL -> R.string.invalidCustomURLTitle
        LOGOUT_FAILED -> R.string.logout_failed_title
        SWITCH_USER_FAILED -> R.string.switch_user_failed_title
        ADD_PROFILE_FAILED -> R.string.add_profile_failed_title
        SHARE_DEVICE_NOT_CONNECTED -> R.string.share_device_not_connected_title
        SHARE_FAILED -> R.string.taildrop_share_failed_title
      }
    }

  val buttonText: Int = R.string.ok
}

@Composable
fun ErrorDialog(type: ErrorDialogType, action: () -> Unit = {}) {
  ErrorDialog(
      title = type.title, message = type.message, buttonText = type.buttonText, onDismiss = action)
}

@Composable
fun ErrorDialog(
    @StringRes title: Int = R.string.error,
    @StringRes message: Int,
    @StringRes buttonText: Int = R.string.ok,
    onDismiss: () -> Unit = {}
) {
  AppTheme {
    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(text = stringResource(id = title)) },
      text = { Text(text = stringResource(id = message)) },
      confirmButton = {
        PrimaryActionButton(onClick = onDismiss) { Text(text = stringResource(id = buttonText)) }
      })
  }
}

@Preview
@Composable
fun ErrorDialogPreview() {
  ErrorDialog(ErrorDialogType.LOGOUT_FAILED)
}
