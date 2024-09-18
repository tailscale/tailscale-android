// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import com.tailscale.ipn.R
import com.tailscale.ipn.util.TSLog
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date

object TimeUtil {
  val TAG = "TimeUtil"

  fun keyExpiryFromGoTime(goTime: String?): ComposableStringFormatter {

    val time = goTime ?: return ComposableStringFormatter(R.string.empty)
    val expTime = epochMillisFromGoTime(time)
    val now = Instant.now().toEpochMilli()

    var diff = (expTime - now) / 1000

    // Rather than use plurals here, we'll just use the singular form for everything and
    // double the minimum.  "in 70 minutes" instead of "in 1 hour".  121 minutes becomes
    // 2 hours, as does 179 minutes... Close enough for what this is used for.

    // Key is already expired (x minutes ago)
    if (diff < 0) {
      diff = -diff
      return when (diff) {
        in 0..60 -> ComposableStringFormatter(R.string.under_a_minute)
        in 61..7200 ->
            ComposableStringFormatter(R.string.ago_x_minutes, diff / 60) // 1 minute to 1 hour
        in 7201..172800 ->
            ComposableStringFormatter(R.string.ago_x_hours, diff / 3600) // 2 hours to 24 hours
        in 172801..5184000 ->
            ComposableStringFormatter(R.string.ago_x_days, diff / 86400) // 2 Days to 60 days
        in 5184001..124416000 ->
            ComposableStringFormatter(R.string.ago_x_months, diff / 2592000) // ~2 months to 2 years
        else ->
            ComposableStringFormatter(
                R.string.ago_x_years,
                diff.toDouble() / 31536000.0) // 2 years to n years (in decimal)
      }
    }

    // Key is not expired (in x minutes)
    return when (diff) {
      in 0..60 -> ComposableStringFormatter(R.string.under_a_minute)
      in 61..7200 ->
          ComposableStringFormatter(R.string.in_x_minutes, diff / 60) // 1 minute to 1 hour
      in 7201..172800 ->
          ComposableStringFormatter(R.string.in_x_hours, diff / 3600) // 2 hours to 24 hours
      in 172801..5184000 ->
          ComposableStringFormatter(R.string.in_x_days, diff / 86400) // 2 Days to 60 days
      in 5184001..124416000 ->
          ComposableStringFormatter(R.string.in_x_months, diff / 2592000) // ~2 months to 2 years
      else ->
          ComposableStringFormatter(
              R.string.in_x_years, diff.toDouble() / 31536000.0) // 2 years to n years (in decimal)
    }
  }

  fun epochMillisFromGoTime(goTime: String): Long {
    val ta = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(goTime)
    val i = Instant.from(ta)
    return i.toEpochMilli()
  }

  fun dateFromGoString(goTime: String): Date {
    val ta = DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(goTime)
    val i = Instant.from(ta)
    return Date.from(i)
  }

  // Returns true if the given Go time string is in the past, or will occur within the given
  // duration from now.
  fun isWithinExpiryNotificationWindow(window: Duration, goTime: String): Boolean {
    val expTime = epochMillisFromGoTime(goTime)
    val now = Instant.now().toEpochMilli()
    return (expTime - now) / 1000 < window.seconds
  }

  // Parses a Go duration string (e.g. "2h3.2m4s") and returns a Java Duration object.
  // Returns null if the input string is not a valid Go duration or contains
  // units other than y,w,d,h,m,s (ms and us are explicitly not supported).
  fun duration(goDuration: String): Duration? {
    if (goDuration.contains("ms") || goDuration.contains("us")) {
      return null
    }

    var duration = 0.0
    var valStr = ""
    for (c in goDuration) {
      // Scan digits and decimal points
      if (c.isDigit() || c == '.') {
        valStr += c
      } else {
        try {
          val durationFragment = valStr.toDouble()
          duration +=
              when (c) {
                'y' -> durationFragment * 31536000.0 // 365 days
                'w' -> durationFragment * 604800.0
                'd' -> durationFragment * 86400.0
                'h' -> durationFragment * 3600.0
                'm' -> durationFragment * 60.0
                's' -> durationFragment
                else -> {
                  TSLog.e(TAG, "Invalid duration string: $goDuration")
                  return null
                }
              }
        } catch (e: NumberFormatException) {
          TSLog.e(TAG, "Invalid duration string: $goDuration")
          return null
        }
        valStr = ""
      }
    }
    return Duration.ofSeconds(duration.toLong())
  }
}
