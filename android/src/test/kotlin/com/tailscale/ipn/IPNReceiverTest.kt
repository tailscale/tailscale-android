// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import android.content.Intent
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Below Android 12, WorkManager runs expedited work as a foreground service and first asks the
// worker for ForegroundInfo, so every intent IPNReceiver expedites must survive that path.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = IPNReceiverTest.TestApp::class)
class IPNReceiverTest {
  // App.onCreate needs libtailscale; the workers only need UninitializedApp.get().
  class TestApp : UninitializedApp() {
    override fun onCreate() {
      super.onCreate()
      setUnprotectedInstance(this)
    }
  }

  @Test fun connectVpn() = assertRunsAsForegroundWork(IPNReceiver.INTENT_CONNECT_VPN)

  @Test fun disconnectVpn() = assertRunsAsForegroundWork(IPNReceiver.INTENT_DISCONNECT_VPN)

  @Test fun useExitNode() = assertRunsAsForegroundWork("com.tailscale.ipn.USE_EXIT_NODE")

  private fun assertRunsAsForegroundWork(action: String) {
    val app = RuntimeEnvironment.getApplication()
    WorkManagerTestInitHelper.initializeTestWorkManager(
        app,
        Configuration.Builder()
            .setExecutor(SynchronousExecutor())
            .setTaskExecutor(SynchronousExecutor())
            .build(),
    )

    // A CoroutineWorker without getForegroundInfo throws on a background thread, which kills the
    // app on a device.
    val crashes = CopyOnWriteArrayList<Throwable>()
    val prev = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { _, e -> crashes += e }
    try {
      IPNReceiver().onReceive(app, Intent(action).putExtra("exitNode", "exit-node"))

      val workManager = WorkManager.getInstance(app)
      val all = WorkQuery.fromStates(WorkInfo.State.entries)
      fun finished() =
          workManager.getWorkInfos(all).get().let { infos ->
            infos.isNotEmpty() && infos.all { it.state.isFinished }
          }
      val deadline = System.currentTimeMillis() + 5_000
      while (!finished()) {
        assertTrue("work did not finish", System.currentTimeMillis() < deadline)
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
      }
    } finally {
      Thread.setDefaultUncaughtExceptionHandler(prev)
    }

    assertEquals(emptyList<Throwable>(), crashes.toList())
    val started =
        generateSequence { shadowOf(app).nextStartedService }
            .map { it.component?.className }
            .toList()
    assertTrue(
        "WorkManager foreground service not started; started: $started",
        "androidx.work.impl.foreground.SystemForegroundService" in started,
    )
  }
}
