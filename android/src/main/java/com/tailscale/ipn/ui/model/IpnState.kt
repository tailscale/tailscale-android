// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.Serializable

class IpnLocal {
    @Serializable
    data class LoginProfile(
        var ID: String,
        val Name: String,
        val Key: String,
        val UserProfile: Tailcfg.UserProfile,
        val NetworkProfile: Tailcfg.NetworkProfile? = null,
        val LocalUserID: String,
    ) {
        fun isEmpty(): Boolean {
            return ID.isEmpty()
        }
    }
}
