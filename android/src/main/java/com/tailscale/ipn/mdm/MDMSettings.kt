// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mdm

import android.content.RestrictionsManager
import com.tailscale.ipn.App

class MDMSettings(private val restrictionsManager: RestrictionsManager? = null) {
  fun get(setting: BooleanSetting): Boolean {
    restrictionsManager?.let {
      if (it.applicationRestrictions.containsKey(setting.key)) {
        return it.applicationRestrictions.getBoolean(setting.key)
      }
    }
    return App.getApplication().getEncryptedPrefs().getBoolean(setting.key, false)
  }

  fun get(setting: StringSetting): String? {
    return restrictionsManager?.applicationRestrictions?.getString(setting.key)
        ?: App.getApplication().getEncryptedPrefs().getString(setting.key, null)
  }

  fun get(setting: AlwaysNeverUserDecidesSetting): AlwaysNeverUserDecidesValue {
    val storedString: String =
        restrictionsManager?.applicationRestrictions?.getString(setting.key)
            ?: App.getApplication().getEncryptedPrefs().getString(setting.key, null)
            ?: "user-decides"
    return when (storedString) {
      "always" -> {
        AlwaysNeverUserDecidesValue.Always
      }
      "never" -> {
        AlwaysNeverUserDecidesValue.Never
      }
      else -> {
        AlwaysNeverUserDecidesValue.UserDecides
      }
    }
  }

  fun get(setting: ShowHideSetting): ShowHideValue {
    val storedString: String =
        restrictionsManager?.applicationRestrictions?.getString(setting.key)
            ?: App.getApplication().getEncryptedPrefs().getString(setting.key, null)
            ?: "show"
    return when (storedString) {
      "hide" -> {
        ShowHideValue.Hide
      }
      else -> {
        ShowHideValue.Show
      }
    }
  }

  fun get(setting: StringArraySetting): Array<String>? {
    restrictionsManager?.let {
      if (it.applicationRestrictions.containsKey(setting.key)) {
        return it.applicationRestrictions.getStringArray(setting.key)
      }
    }
    return App.getApplication()
            .getEncryptedPrefs()
        .getStringSet(setting.key, HashSet<String>())
        ?.toTypedArray()
        ?.sortedArray()
  }
}
