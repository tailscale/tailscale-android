// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import com.tailscale.ipn.ui.model.Ipn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UseExitNodeWorkerTest {
  @Test
  fun callbackBridgeWaitsForCallback() = runTest {
    lateinit var callback: (Result<String>) -> Unit
    val result = async { awaitSingleCallback<String> { callback = it } }

    runCurrent()
    assertFalse(result.isCompleted)

    callback(Result.success("done"))
    runCurrent()
    assertEquals("done", result.await().getOrThrow())
  }

  @Test
  fun callbackBridgeReturnsFailure() = runTest {
    lateinit var callback: (Result<String>) -> Unit
    val error = IllegalStateException("failed")
    val result = async { awaitSingleCallback<String> { callback = it } }

    runCurrent()
    callback(Result.failure(error))
    runCurrent()

    assertSame(error, result.await().exceptionOrNull())
  }

  @Test
  fun callbackBridgeCanBeBoundedByTimeout() = runTest {
    val result = withTimeoutOrNull(100) { awaitSingleCallback<String> {} }

    assertNull(result)
  }

  @Test
  fun parentCancellationPropagates() = runTest {
    lateinit var callback: (Result<String>) -> Unit
    val job = launch { awaitSingleCallback<String> { callback = it } }

    runCurrent()
    job.cancel()
    runCurrent()
    assertTrue(job.isCancelled)

    callback(Result.success("late"))
    runCurrent()
    assertTrue(job.isCancelled)
  }

  @Test
  fun duplicateCallbackUsesFirstResult() = runTest {
    lateinit var callback: (Result<String>) -> Unit
    val result = async { awaitSingleCallback<String> { callback = it } }

    runCurrent()
    callback(Result.success("first"))
    callback(Result.success("second"))
    runCurrent()

    assertEquals("first", result.await().getOrThrow())
  }

  @Test
  fun buildExitNodePrefsSetsExactMask() {
    val prefs = buildExitNodePrefs("node-1", true)

    assertEquals("node-1", prefs.ExitNodeID)
    assertEquals(true, prefs.ExitNodeIDSet)
    assertEquals(true, prefs.ExitNodeAllowLANAccess)
    assertEquals(true, prefs.ExitNodeAllowLANAccessSet)
  }

  @Test
  fun buildExitNodePrefsClearsWithSetBits() {
    val prefs = buildExitNodePrefs(null, false)

    assertNull(prefs.ExitNodeID)
    assertEquals(true, prefs.ExitNodeIDSet)
    assertEquals(false, prefs.ExitNodeAllowLANAccess)
    assertEquals(true, prefs.ExitNodeAllowLANAccessSet)
  }

  @Test
  fun returnedPrefsMustMatchRequestedValues() {
    val applied = Ipn.Prefs(ExitNodeID = "node-1", ExitNodeAllowLANAccess = true)

    assertTrue(exitNodePrefsMatch(applied, "node-1", true))
    assertFalse(exitNodePrefsMatch(applied, "node-2", true))
    assertFalse(exitNodePrefsMatch(applied, "node-1", false))
  }

  @Test
  fun returnedPrefsTreatsEmptyExitNodeAsCleared() {
    val applied = Ipn.Prefs(ExitNodeID = "", ExitNodeAllowLANAccess = false)

    assertTrue(exitNodePrefsMatch(applied, null, false))
  }
}
