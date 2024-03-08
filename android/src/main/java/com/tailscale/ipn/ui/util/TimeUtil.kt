package com.tailscale.ipn.ui.util

class TimeUtil {
    fun keyExpiryFromGoTime(goTime: String?): String {
        // (jonathan) TODO: Turn these time strings into 'in 4 months', 'in 2 days', 'in 1 year', etc
        return goTime ?: "Never"
    }
}