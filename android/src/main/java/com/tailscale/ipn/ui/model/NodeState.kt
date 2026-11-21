// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

enum class NodeState {
  NONE,
  ACTIVE_AND_RUNNING,
  // Last selected exit node is active but is not being used.
  ACTIVE_NOT_RUNNING,
  // Last selected exit node is currently offline.
  OFFLINE_ENABLED,
  // Last selected exit node has been de-selected and is currently offline.
  OFFLINE_DISABLED,
  // Exit node selection is managed by an administrator, and last selected exit node is currently
  // offline
  OFFLINE_MDM,
  RUNNING_AS_EXIT_NODE,
}
