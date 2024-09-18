// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.util

import android.util.Log
import libtailscale.Libtailscale

object ExtendedLog {
  var libtailscaleWrapper = LibtailscaleWrapper()

  fun d(tag: String?, message: String) {
    Log.d(tag, message)
    libtailscaleWrapper.sendLog(tag, message)
  }

  fun w(tag: String, message: String) {
    Log.w(tag, message)
    libtailscaleWrapper.sendLog(tag, message)
  }

  // Overloaded function without Throwable because Java does not support default parameters
  @JvmStatic
  fun e(tag: String?, message: String) {
    Log.e(tag, message)
    libtailscaleWrapper.sendLog(tag, message)
  }

  fun e(tag: String?, message: String, throwable: Throwable? = null) {
    if (throwable == null) {
      Log.e(tag, message)
      libtailscaleWrapper.sendLog(tag, message)
    } else {
      Log.e(tag, message, throwable)
      libtailscaleWrapper.sendLog(tag, "$message ${throwable?.localizedMessage}")
    }
  }

  class LibtailscaleWrapper {
    public fun sendLog(tag: String?, message: String) {
      val logTag = tag ?: ""
      Libtailscale.sendLog(logTag + ": " + message)
    }
  }
}
