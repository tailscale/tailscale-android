// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import com.tailscale.ipn.R
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.ErrorDialogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UserSwitcherViewModel : IpnViewModel() {

  // Set to a non-null value to show the appropriate error dialog
  val showDialog: StateFlow<ErrorDialogType?> = MutableStateFlow(null)

  val loginSetting =
      Setting(
          title = ComposableStringFormatter(R.string.reauthenticate),
          type = SettingType.NAV,
          onClick = { login {} })

  val logoutSetting =
      Setting(
          title = ComposableStringFormatter(R.string.log_out),
          destructive = true,
          type = SettingType.TEXT,
          onClick = {
            logout {
              if (it.isFailure) {
                showDialog.set(ErrorDialogType.LOGOUT_FAILED)
              }
            }
          })

  val addProfileSetting =
      Setting(
          title = ComposableStringFormatter(R.string.add_account),
          type = SettingType.NAV,
          onClick = {
            addProfile {
              if (it.isFailure) {
                showDialog.set(ErrorDialogType.ADD_PROFILE_FAILED)
              }
            }
          })
}
