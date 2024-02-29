// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.model

import kotlinx.serialization.Serializable

typealias Addr = String
typealias Prefix = String
typealias NodeID = Long
typealias KeyNodePublic = String
typealias MachineKey = String
typealias UserID = Long
typealias Time = String
typealias StableNodeID = String
typealias BugReportID = String

// Represents and empty message with a single 'property' field.
class Empty {
    @Serializable 
    data class Message(val property: String)
}

// Parsable errors returned by localApiService
class Errors {
    @Serializable
    data class GenericError(val error: String) 
}

// Returned on successful operations with no explicit response body
class Success {
    @Serializable
    data class GenericSuccess(val message: String)
}