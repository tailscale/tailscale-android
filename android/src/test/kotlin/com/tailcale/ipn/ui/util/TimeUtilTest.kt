// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailcale.ipn.ui.util

import com.tailscale.ipn.ui.util.TimeUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class TimeUtilTest {

  @Test
  fun durationInvalidMsUnits() {
    val input = "5s10ms"
    val actual = TimeUtil.duration(input)
    assertNull("Should return null", actual)
  }

  @Test
  fun durationInvalidUsUnits() {
    val input = "5s10us"
    val actual = TimeUtil.duration(input)
    assertNull("Should return null", actual)
  }

  @Test
  fun durationTestHappyPath() {
    val input = arrayOf("1.0y1.0w1.0d1.0h1.0m1.0s", "1s", "1m", "1h", "1d", "1w", "1y")
    val expectedSeconds =
        arrayOf((31536000 + 604800 + 86400 + 3600 + 60 + 1), 1, 60, 3600, 86400, 604800, 31536000)
    val expected = expectedSeconds.map { Duration.ofSeconds(it.toLong()) }
    val actual = input.map { TimeUtil.duration(it) }
    assertEquals("Incorrect conversion", expected, actual)
  }

  @Test
  fun testBadDurationString() {
    val input = "1..0y1.0w1.0d1.0h1.0m1.0s"
    val actual = TimeUtil.duration(input)
    assertNull("Should return null", actual)
  }

  @Test
  fun testBadDInputString() {
    val input = "1.0yy1.0w1.0d1.0h1.0m1.0s"
    val actual = TimeUtil.duration(input)
    assertNull("Should return null", actual)
  }

  @Test
  fun testIgnoreFractionalSeconds() {
    val input = "10.9s"
    val expectedSeconds = 10
    val expected = Duration.ofSeconds(expectedSeconds.toLong())
    val actual = TimeUtil.duration(input)
    assertEquals("Should return $expectedSeconds seconds", expected, actual)
  }
}
