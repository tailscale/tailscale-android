// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view.main

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.model.Favorites
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.Tailcfg
import kotlinx.coroutines.launch

@Composable
internal fun DeviceDropdownMenu(
    favorites: Favorites?,
    peer: Tailcfg.Node,
    netmap: Netmap.NetworkMap?,
    hidePeerDropdownMenu: () -> Unit,
    startPing: (Tailcfg.Node) -> Unit,
    togglePin: (Tailcfg.Node) -> Unit,
) {
  val localClipboardManager = LocalClipboard.current
  val scope = rememberCoroutineScope()
  val isFavorite = favorites?.isFavoriteDevice(peer.StableID) == true

  DropdownMenu(
      expanded = true,
      onDismissRequest = hidePeerDropdownMenu,
  ) {
    netmap?.let { netMap ->
      if (!peer.isSelfNode(netMap)) {
        DropdownMenuItem(
            leadingIcon = {
              Icon(
                  painter = painterResource(R.drawable.sensors_24),
                  contentDescription = null,
              )
            },
            text = { Text(text = stringResource(R.string.ping)) },
            onClick = {
              hidePeerDropdownMenu()
              startPing(peer)
            },
        )
      }
    }
    DropdownMenuItem(
        leadingIcon = {
          Icon(
              painter = painterResource(R.drawable.clipboard),
              contentDescription = null,
          )
        },
        text = { Text(text = stringResource(R.string.copy_magic_dns_address)) },
        onClick = {
          scope.launch { peer.copyMagicDNSAddress(localClipboardManager) }
          hidePeerDropdownMenu()
        },
    )
    DropdownMenuItem(
        leadingIcon = {
          Icon(
              painter = painterResource(R.drawable.clipboard),
              contentDescription = null,
          )
        },
        text = { Text(text = stringResource(R.string.copy_ipv4_address)) },
        onClick = {
          scope.launch { peer.copyIPV4Address(localClipboardManager) }
          hidePeerDropdownMenu()
        },
    )
    DropdownMenuItem(
        leadingIcon = {
          Icon(
              painter = painterResource(R.drawable.clipboard),
              contentDescription = null,
          )
        },
        text = { Text(text = stringResource(R.string.copy_ipv6_address)) },
        onClick = {
          scope.launch { peer.copyIPV6Address(localClipboardManager) }
          hidePeerDropdownMenu()
        },
    )

    HorizontalDivider()

    DropdownMenuItem(
        leadingIcon = {
          Icon(
              painter = painterResource(if (isFavorite) R.drawable.unpin_24 else R.drawable.pin_24),
              contentDescription = null,
          )
        },
        text = {
          Text(
              text = stringResource(if (isFavorite) R.string.unpin_device else R.string.pin_device),
          )
        },
        onClick = {
          togglePin(peer)
          hidePeerDropdownMenu()
        },
    )
  }
}

@Preview
@Composable
private fun DeviceDropdownMenuPreview() {
  DeviceDropdownMenu(
      favorites = Favorites(),
      netmap =
          Netmap.NetworkMap(
              SelfNode = Tailcfg.Node(),
              Domain = "tailscale.com",
              UserProfiles = emptyMap(),
              TKAEnabled = false,
          ),
      peer = Tailcfg.Node(),
      hidePeerDropdownMenu = {},
      startPing = {},
      togglePin = {},
  )
}
