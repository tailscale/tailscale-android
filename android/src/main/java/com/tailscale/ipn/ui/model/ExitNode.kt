// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.coroutines.flow.StateFlow

data class ExitNode(
    val id: StableNodeID? = null,
    val label: String,
    val online: StateFlow<Boolean>,
    val selected: Boolean,
    val mullvad: Boolean = false,
    val priority: Int = 0,
    val countryCode: String = "",
    val country: String = "",
    val city: String = "",
)
