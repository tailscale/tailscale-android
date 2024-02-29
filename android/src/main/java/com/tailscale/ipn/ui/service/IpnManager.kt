// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.service

import android.util.Log
import com.tailscale.ipn.ui.localapi.LocalApiClient
import com.tailscale.ipn.ui.notifier.Notifier

class IpnManager {
    var notifier = Notifier()
    var apiClient = LocalApiClient()
    val model: IpnModel
    
    constructor() {
        model = IpnModel(notifier, apiClient)   
    }

    // We share a single instance of the IPNManager across the entire application.
    companion object {
        @Volatile
        private var instance: IpnManager? = null

        fun getInstance() =
            instance ?: synchronized(this) {
                instance ?: IpnManager().also { instance = it }
            }
    }
}
