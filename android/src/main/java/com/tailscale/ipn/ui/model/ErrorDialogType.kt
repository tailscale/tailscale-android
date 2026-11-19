// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import com.tailscale.ipn.R

enum class ErrorDialogType {
  INVALID_CUSTOM_URL,
  LOGOUT_FAILED,
  SWITCH_USER_FAILED,
  ADD_PROFILE_FAILED,
  SHARE_DEVICE_NOT_CONNECTED,
  SHARE_FAILED,
  INVALID_AUTH_KEY;

  val message: Int
    get() {
      return when (this) {
        INVALID_CUSTOM_URL -> R.string.invalidCustomUrl
        LOGOUT_FAILED -> R.string.logout_failed
        SWITCH_USER_FAILED -> R.string.switch_user_failed
        ADD_PROFILE_FAILED -> R.string.add_profile_failed
        SHARE_DEVICE_NOT_CONNECTED -> R.string.share_device_not_connected
        SHARE_FAILED -> R.string.taildrop_share_failed
        INVALID_AUTH_KEY -> R.string.invalidAuthKey
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
        INVALID_AUTH_KEY -> R.string.invalidAuthKeyTitle
      }
    }

  val buttonText: Int = R.string.ok
}
