// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitTunnelAppOrderingTest {
  private val apps =
      listOf(
          InstalledApp("2ГИС beta", "ru.dublgis.dgismobile4preview"),
          InstalledApp("AIMP", "com.aimp.player"),
          InstalledApp("Яндекс Музыка", "ru.yandex.music"),
          InstalledApp("Airbnb", "com.airbnb.android"),
          InstalledApp("百度地图", "com.baidu.BaiduMap"),
          InstalledApp("Chrome", "com.android.chrome"),
      )

  @Test
  fun unselectedAppsAreOrderedWithEnglishNamesBeforeLocalizedNames() {
    val ordered = orderedSplitTunnelApps(apps, selectedPackageNames = emptySet(), query = "")

    assertEquals(
        listOf(
            "com.aimp.player",
            "com.airbnb.android",
            "com.android.chrome",
            "ru.dublgis.dgismobile4preview",
            "ru.yandex.music",
            "com.baidu.BaiduMap",
        ),
        ordered.map { it.packageName },
    )
  }

  @Test
  fun selectedAppsStayAboveUnselectedAppsAndAreAlsoEnglishFirst() {
    val ordered =
        orderedSplitTunnelApps(
            apps,
            selectedPackageNames =
                setOf("ru.yandex.music", "com.android.chrome", "ru.dublgis.dgismobile4preview"),
            query = "",
        )

    assertEquals(
        listOf(
            "com.android.chrome",
            "ru.dublgis.dgismobile4preview",
            "ru.yandex.music",
            "com.aimp.player",
            "com.airbnb.android",
            "com.baidu.BaiduMap",
        ),
        ordered.map { it.packageName },
    )
  }

  @Test
  fun searchMatchesAppNameCaseInsensitivelyAndKeepsOrdering() {
    val ordered = orderedSplitTunnelApps(apps, selectedPackageNames = emptySet(), query = "AIR")

    assertEquals(listOf("com.airbnb.android"), ordered.map { it.packageName })
  }

  @Test
  fun searchMatchesPackageNameCaseInsensitively() {
    val ordered = orderedSplitTunnelApps(apps, selectedPackageNames = emptySet(), query = "YANDEX")

    assertEquals(listOf("ru.yandex.music"), ordered.map { it.packageName })
  }

  @Test
  fun localizedQueryMatchesLocalizedAppName() {
    val ordered = orderedSplitTunnelApps(apps, selectedPackageNames = emptySet(), query = "музыка")

    assertEquals(listOf("ru.yandex.music"), ordered.map { it.packageName })
  }

  @Test
  fun namesStartingWithDigitsUseTheFirstLetterForLanguageGroup() {
    val ordered =
        orderedSplitTunnelApps(
            listOf(
                InstalledApp("2ГИС beta", "ru.dublgis.dgismobile4preview"),
                InstalledApp("2GIS", "ru.dublgis.dgismobile"),
            ),
            selectedPackageNames = emptySet(),
            query = "",
        )

    assertEquals(
        listOf("ru.dublgis.dgismobile", "ru.dublgis.dgismobile4preview"),
        ordered.map { it.packageName },
    )
  }

  @Test
  fun orderWithinEnglishAndLocalizedGroupsStaysStable() {
    val ordered =
        orderedSplitTunnelApps(
            listOf(
                InstalledApp("Zoo", "com.example.zoo"),
                InstalledApp("Alpha", "com.example.alpha"),
                InstalledApp("Яндекс", "ru.yandex"),
                InstalledApp("Альфа", "ru.alpha"),
                InstalledApp("Beta", "com.example.beta"),
            ),
            selectedPackageNames = emptySet(),
            query = "",
        )

    assertEquals(
        listOf("com.example.zoo", "com.example.alpha", "com.example.beta", "ru.yandex", "ru.alpha"),
        ordered.map { it.packageName },
    )
  }

  @Test
  fun selectedPackageNamesThatAreNotInTheAppListAreIgnored() {
    val ordered =
        orderedSplitTunnelApps(
            apps,
            selectedPackageNames = setOf("missing.package", "com.airbnb.android"),
            query = "",
        )

    assertEquals("com.airbnb.android", ordered.first().packageName)
    assertEquals(apps.size, ordered.size)
  }

  @Test
  fun blankSearchQueryBehavesLikeNoSearch() {
    val noQuery = orderedSplitTunnelApps(apps, selectedPackageNames = emptySet(), query = "")
    val blankQuery = orderedSplitTunnelApps(apps, selectedPackageNames = emptySet(), query = "   ")

    assertEquals(noQuery, blankQuery)
  }

  @Test
  fun searchQueryIsTrimmedBeforeMatching() {
    val ordered =
        orderedSplitTunnelApps(apps, selectedPackageNames = emptySet(), query = "  chrome  ")

    assertEquals(listOf("com.android.chrome"), ordered.map { it.packageName })
  }

  @Test
  fun searchWithNoMatchesReturnsEmptyList() {
    val ordered =
        orderedSplitTunnelApps(apps, selectedPackageNames = emptySet(), query = "not-installed")

    assertTrue(ordered.isEmpty())
  }

  @Test
  fun searchResultStillKeepsSelectedAppsAboveUnselectedApps() {
    val ordered =
        orderedSplitTunnelApps(
            apps,
            selectedPackageNames = setOf("com.baidu.BaiduMap"),
            query = "com.",
        )

    assertEquals(
        listOf(
            "com.baidu.BaiduMap",
            "com.aimp.player",
            "com.airbnb.android",
            "com.android.chrome",
        ),
        ordered.map { it.packageName },
    )
  }

  @Test
  fun nonLetterPrefixesAreTreatedAsLocalizedWhenNoLetterExists() {
    val ordered =
        orderedSplitTunnelApps(
            listOf(
                InstalledApp("!!!", "punctuation.only"),
                InstalledApp("Alpha", "latin.alpha"),
                InstalledApp("123", "digits.only"),
            ),
            selectedPackageNames = emptySet(),
            query = "",
        )

    assertEquals(
        listOf("latin.alpha", "punctuation.only", "digits.only"), ordered.map { it.packageName })
  }

  @Test
  fun volumeOrderingHandlesManyApps() {
    val englishApps =
        (0 until 5000).map { index ->
          InstalledApp("English App $index", "com.example.english.$index")
        }
    val localizedApps =
        (0 until 5000).map { index -> InstalledApp("Приложение $index", "ru.example.$index") }
    val volumeApps = localizedApps.take(2500) + englishApps + localizedApps.drop(2500)
    val selectedPackages =
        setOf(
            "ru.example.4999",
            "com.example.english.4999",
            "ru.example.1",
            "com.example.english.1",
        )

    val ordered = orderedSplitTunnelApps(volumeApps, selectedPackages, query = "")

    assertEquals(10000, ordered.size)
    assertEquals(
        listOf(
            "com.example.english.1",
            "com.example.english.4999",
            "ru.example.1",
            "ru.example.4999",
        ),
        ordered.take(4).map { it.packageName },
    )
    assertEquals("com.example.english.0", ordered[4].packageName)
    assertEquals("com.example.english.4998", ordered[5001].packageName)
    assertEquals("ru.example.0", ordered[5002].packageName)
    assertEquals("ru.example.4998", ordered.last().packageName)
  }

  @Test
  fun volumeSearchFiltersManyAppsBeforeOrdering() {
    val volumeApps =
        (0 until 10_000).map { index ->
          val name = if (index % 2 == 0) "English App $index" else "Приложение $index"
          InstalledApp(name, "com.example.app.$index")
        }

    val ordered =
        orderedSplitTunnelApps(
            volumeApps,
            selectedPackageNames = setOf("com.example.app.1999"),
            query = "1999",
        )

    assertEquals(listOf("com.example.app.1999"), ordered.map { it.packageName })
  }
}
