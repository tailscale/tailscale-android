// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn.ui.localapi

// Go-like result type with an optional value and an optional Error
// This guarantees that only one of the two is non-null
class Result<T> {
    val success: T?
    val error: Error?
    
    constructor(success: T?, error: Error?) {
        if (success != null && error != null) {
            throw IllegalArgumentException("Result cannot have both a success and an error")
        }
        if (success == null && error == null) {
            throw IllegalArgumentException("Result must have either a success or an error")
        }

        this.success = success
        this.error = error
    }
    
    constructor(success: T) : this(success, null) {}
    constructor(error: Error) : this(null, error) {}

    var successful: Boolean = false
        get() = success != null
        
    var failed: Boolean = false
        get() = error != null
}