// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

// The cause of the last disconnect. A successfully configured tunnel clears this reason.
sealed interface DisconnectReason {
  data object UserRequested : DisconnectReason

  data class BackendFailure(val error: String) : DisconnectReason
}
