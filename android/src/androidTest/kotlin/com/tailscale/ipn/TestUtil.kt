// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.util.Log
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val defaultTimeout = 10.seconds

private val threadLocalTimeout = ThreadLocal<Duration>()

/**
 * Wait until the specified timeout for the given selector and return the matching UiObject2.
 * Timeout defaults to 10 seconds.
 *
 * @throws Exception if selector is not found within timeout.
 */
fun UiDevice.find(
    selector: BySelector,
    timeout: Duration = threadLocalTimeout.get() ?: defaultTimeout
): UiObject2 {
  wait(Until.findObject(selector), timeout.inWholeMilliseconds)?.let {
    return it
  } ?: run { throw Exception("not found") }
}

/**
 * Wait until the specified timeout for the given selector and return the matching UiObject. Timeout
 * defaults to 10 seconds.
 *
 * @throws Exception if selector is not found within timeout.
 */
fun UiDevice.find(
    selector: UiSelector,
    timeout: Duration = threadLocalTimeout.get() ?: defaultTimeout
): UiObject {
  val obj = findObject(selector)
  if (!obj.waitForExists(timeout.inWholeMilliseconds)) {
    throw Exception("not found")
  }
  return obj
}

/**
 * Execute an ordered collection of steps as necessary. If an earlier step fails but a subsequent
 * step succeeds, this skips the earlier step. This is useful for interruptible sequences like
 * logging in that may resume in an intermediate state.
 */
fun asNecessary(timeout: Duration, vararg steps: () -> Unit) {
  val interval = 250.milliseconds
  // Use a short timeout to avoid waiting on actions that can be skipped
  threadLocalTimeout.set(interval)
  try {
    val start = System.currentTimeMillis()
    var furthestSuccessful = -1
    while (System.currentTimeMillis() - start < timeout.inWholeMilliseconds) {
      for (i in furthestSuccessful + 1 ..< steps.size) {
        val step = steps[i]
        try {
          step()
          furthestSuccessful = i
          Log.d("TestUtil.asNecessary", "SUCCESS!")
          // Going forward, use the normal timeout on the assumption that subsequent steps will
          // succeed.
          threadLocalTimeout.remove()
        } catch (t: Throwable) {
          Log.d("TestUtil.asNecessary", t.toString())
          // Going forward, use a short timeout to avoid waiting on actions that can be skipped
          threadLocalTimeout.set(interval)
        }
      }
      if (furthestSuccessful == steps.size - 1) {
        // All steps have completed successfully
        return
      }
      // Still some steps left to run
      Thread.sleep(interval.inWholeMilliseconds)
    }
    throw Exception("failed to complete within timeout")
  } finally {
    threadLocalTimeout.remove()
  }
}
