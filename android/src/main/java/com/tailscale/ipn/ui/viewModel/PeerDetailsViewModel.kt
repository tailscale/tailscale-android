// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.viewModel

import androidx.lifecycle.ViewModel
import com.tailscale.ipn.ui.service.IpnModel

class PeerDetailsViewModel(val model: IpnModel, val nodeId: String): ViewModel() {

}