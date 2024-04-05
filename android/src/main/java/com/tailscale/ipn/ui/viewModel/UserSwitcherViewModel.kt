// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.viewModelScope
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.ui.view.ErrorDialogType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class UserSwitcherViewModel : IpnViewModel() {

  // Set to a non-null value to show the appropriate error dialog
  val errorDialog: StateFlow<ErrorDialogType?> = MutableStateFlow(null)

  // True if we should render the kebab menu
  val showHeaderMenu: StateFlow<Boolean> = MutableStateFlow(false)

  // Sets the custom control URL and immediatly invokes the login flow
  fun setControlURL(urlStr: String) {
    // Some basic checks that the entered URL is "reasonable".  The underlying
    // localAPIClient will use the default server if we give it a broken URL,
    // but we can make sure we can construct a URL from the input string and
    // ensure it has an http/https scheme

    when (urlStr.startsWith("http") && urlStr.contains("://") && urlStr.length > 7) {
      false -> errorDialog.set(ErrorDialogType.INVALID_CUSTOM_URL)
      true -> {
        showHeaderMenu.set(false)

        // We need to have the current prefs to set them back with the new control URL
        val prefs = Notifier.prefs.value
        if (prefs == null) {
          errorDialog.set(ErrorDialogType.ADD_PROFILE_FAILED)
          return
        }

        // The basic flow for logging in with a custom control URL is to add a profile,
        // call start with prefs that include the control URL pref, then
        // start an interactive login.

        val fail: (Throwable) -> Unit = { errorDialog.set(ErrorDialogType.ADD_PROFILE_FAILED) }

        val login = {
          Client(viewModelScope).startLoginInteractive { startLogin -> startLogin.onFailure(fail) }
        }

        val start = {
          prefs.ControlURL = urlStr
          val options = Ipn.Options(Prefs = prefs)

          Client(viewModelScope).start(options) { start ->
            start.onFailure(fail).onSuccess { login() }
          }
        }

        Client(viewModelScope).addProfile { addProfile ->
          addProfile.onFailure(fail).onSuccess { start() }
        }
      }
    }
  }
}
