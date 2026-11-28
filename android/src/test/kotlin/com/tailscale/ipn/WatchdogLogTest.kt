// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchdogLogTest {
  private fun tempFile(): File = Files.createTempFile("watchdog", ".log").toFile().also { it.delete() }

  @Test
  fun appendsOneLinePerDecision() {
    val f = tempFile()
    val log = WatchdogLog(f, maxBytes = 1024) { 0L }
    log.append("healthy")
    log.append("acting: rebind")
    val lines = f.readLines()
    assertEquals(2, lines.size)
    assertTrue(lines[0].endsWith(" healthy"))
    assertTrue(lines[1].endsWith(" acting: rebind"))
  }

  @Test
  fun rotatesOncePastTheLimitAndKeepsOnePreviousFile() {
    val f = tempFile()
    // A tiny limit so a couple of lines trip it.
    val log = WatchdogLog(f, maxBytes = 40) { 0L }
    repeat(5) { log.append("a line long enough to exceed the limit") }
    val previous = File(f.parentFile, f.name + ".1")
    assertTrue("previous file should exist after rotation", previous.exists())
    assertTrue("current file should be small after rotation", f.length() <= 40 + 64)
    previous.delete()
    f.delete()
  }

  @Test
  fun aFailingSinkNeverThrows() {
    // A directory where a file is expected makes appendText fail; the watchdog
    // must keep running rather than die on a logging error.
    val dir = Files.createTempDirectory("watchdog-dir").toFile()
    val log = WatchdogLog(dir, maxBytes = 1024) { 0L }
    log.append("this must not throw")
    dir.delete()
  }
}
