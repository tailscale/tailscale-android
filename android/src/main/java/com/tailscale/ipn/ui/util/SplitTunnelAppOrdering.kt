// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

fun orderedSplitTunnelApps(
    apps: List<InstalledApp>,
    selectedPackageNames: Set<String>,
    query: String,
): List<InstalledApp> {
  val filteredApps =
      if (query.isBlank()) {
        apps
      } else {
        val normalizedQuery = query.trim()
        apps.filter { app ->
          app.name.contains(normalizedQuery, ignoreCase = true) ||
              app.packageName.contains(normalizedQuery, ignoreCase = true)
        }
      }

  val (selectedApps, unselectedApps) =
      filteredApps.partition { selectedPackageNames.contains(it.packageName) }
  return selectedApps.englishNamesFirst() + unselectedApps.englishNamesFirst()
}

private fun List<InstalledApp>.englishNamesFirst(): List<InstalledApp> {
  val (englishApps, localizedApps) = partition { it.name.startsWithLatinLetter() }
  return englishApps + localizedApps
}

private fun String.startsWithLatinLetter(): Boolean {
  val firstLetter = firstOrNull { it.isLetter() } ?: return false
  return Character.UnicodeScript.of(firstLetter.code) == Character.UnicodeScript.LATIN
}
