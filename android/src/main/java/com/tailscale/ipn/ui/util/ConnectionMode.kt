// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.on

sealed class ConnectionMode {
  class NotConnected : ConnectionMode()

  class Derp(val relayName: String) : ConnectionMode()

  class Direct : ConnectionMode()

  class PeerRelay : ConnectionMode()

  @Composable
  fun titleString(): String {
    return when (this) {
      is NotConnected -> stringResource(id = R.string.not_connected)
      is Derp -> stringResource(R.string.relayed_connection, relayName)
      is Direct -> stringResource(R.string.direct_connection)
      is PeerRelay -> stringResource(R.string.peer_relayed_connection)
    }
  }

  fun contentKey(): String {
    return when (this) {
      is NotConnected -> "NotConnected"
      is Derp -> "Derp($relayName)"
      is Direct -> "Direct"
      is PeerRelay -> "PeerRelay"
    }
  }

  fun iconDrawable(): Int {
    return when (this) {
      is NotConnected -> R.drawable.xmark_circle
      is Derp -> R.drawable.link_off
      is Direct -> R.drawable.link
      is PeerRelay -> R.drawable.link_off
    }
  }

  @Composable
  fun color(): Color {
    return when (this) {
      is NotConnected -> MaterialTheme.colorScheme.onPrimary
      is Derp -> MaterialTheme.colorScheme.error
      is Direct -> MaterialTheme.colorScheme.on
      is PeerRelay -> MaterialTheme.colorScheme.on
    }
  }
}
