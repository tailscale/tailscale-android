// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.flow.StateFlow

interface LoginQRDataSource {
  val browseToURL: StateFlow<String?>
}

class LoginQRDataSourceImpl : LoginQRDataSource {
  override val browseToURL = Notifier.browseToURL
}
