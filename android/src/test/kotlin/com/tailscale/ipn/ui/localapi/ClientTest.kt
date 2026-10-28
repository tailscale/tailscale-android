// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.localapi

import android.util.Log
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito
import org.mockito.kotlin.mock

class ClientTest {
  private lateinit var androidLog: MockedStatic<Log>
  private lateinit var originalLogWrapper: TSLog.LibtailscaleWrapper

  @Before
  fun setUp() {
    androidLog = Mockito.mockStatic(Log::class.java)
    originalLogWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper = mock()
  }

  @After
  fun tearDown() {
    TSLog.libtailscaleWrapper = originalLogWrapper
    androidLog.close()
  }

  @Test
  fun constructingClientDoesNotResolveApp() = runTest {
    var calls = 0
    Client(this) {
      calls++
      throw IllegalStateException("Unexpected initialization")
    }
    assertEquals(0, calls)
  }

  @Test
  fun providerFailureIsDeliveredToCallback() = runBlocking {
    val failure = IllegalStateException("Backend initialization failed")
    val response = CompletableDeferred<Result<IpnState.Status>>()
    val client = Client(this) { throw failure }

    client.status { response.complete(it) }

    val result = withTimeout(5_000) { response.await() }
    assertSame(failure, result.exceptionOrNull())
  }

  @Test
  fun providerIsResolvedForEachRequest() = runBlocking {
    val failures =
        listOf(
            IllegalStateException("First backend"),
            IllegalStateException("Second backend"),
        )
    var calls = 0
    val client = Client(this) { throw failures[calls++] }

    suspend fun request(): Result<IpnState.Status> {
      val response = CompletableDeferred<Result<IpnState.Status>>()
      client.status { response.complete(it) }
      return withTimeout(5_000) { response.await() }
    }

    assertSame(failures[0], request().exceptionOrNull())
    assertSame(failures[1], request().exceptionOrNull())
    assertEquals(2, calls)
  }
}
