// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.dataSource

import com.tailscale.ipn.ui.model.Ipn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

interface ExitNodePrefsDataSource {
  val prefs: StateFlow<Ipn.Prefs?>

  fun editPrefs(
      scope: CoroutineScope,
      prefsOut: Ipn.MaskedPrefs,
      callback: (Result<Ipn.Prefs>) -> Unit,
  )
}
