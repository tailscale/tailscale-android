// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import android.net.Uri

// Encapsulates a uri based file transfer for Taildrop.
data class FileTransfer(val filename: String, val size: Long, val uri: Uri)
