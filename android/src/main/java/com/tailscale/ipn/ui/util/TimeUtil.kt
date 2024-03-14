// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Date


class TimeUtil {
    fun keyExpiryFromGoTime(goTime: String?): String {

        val time = goTime ?: return ""
        val expTime = epochMillisFromGoTime(time)
        val now = Instant.now().toEpochMilli()

        val diff = (expTime - now) / 1000

        if (diff < 0) {
            return "expired"
        }

        // (jonathan) TODO: This is incorrect in a couple of ways
        // - It needs to be in a composable so we can use stringResource
        // - The string resources need to be proper plurals
        return when (diff) {
            in 0..60 -> "under a minute"
            in 61..3600 -> "in ${diff / 60} minutes"
            in 3601..86400 -> "in ${diff / 3600} hours"
            in 86401..2592000 -> "in ${diff / 86400} days"
            in 2592001..31536000 -> "in ${diff / 2592000} months"
            else -> "in ${diff / 31536000} years"
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
}

