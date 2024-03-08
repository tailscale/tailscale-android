// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mdm

enum class NetworkDevices(val value: String) {
  currentUser("current-user"),
  otherUsers("other-users"),
  taggedDevices("tagged-devices"),
}

class MDMSettings {
  val hiddenNetworkDevices: List<NetworkDevices> = emptyList()
}
