// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mdm

import android.content.SharedPreferences
import android.os.Bundle
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class SettingState<T>(val value: T, val isSet: Boolean)

abstract class MDMSetting<T>(defaultValue: T, val key: String, val localizedTitle: String) {
  val defaultValue = defaultValue
  val flow = MutableStateFlow(SettingState(defaultValue, false))

  fun setFrom(bundle: Bundle?, prefs: Lazy<SharedPreferences>) {
    val v: T? = getFrom(bundle, prefs)
    flow.set(SettingState(v ?: defaultValue, v != null))
  }

  fun getFrom(bundle: Bundle?, prefs: Lazy<SharedPreferences>): T? {
    return when {
      bundle != null -> bundle.takeIf { it.containsKey(key) }?.let { getFromBundle(it) }
      else -> prefs.value.takeIf { it.contains(key) }?.let { getFromPrefs(it) }
    }
  }

  protected abstract fun getFromBundle(bundle: Bundle): T
  protected abstract fun getFromPrefs(prefs: SharedPreferences): T
}

class BooleanMDMSetting(key: String, localizedTitle: String) :
    MDMSetting<Boolean>(false, key, localizedTitle) {
  override fun getFromBundle(bundle: Bundle) = bundle.getBoolean(key)
  override fun getFromPrefs(prefs: SharedPreferences) = prefs.getBoolean(key, false)
}

class StringMDMSetting(key: String, localizedTitle: String) :
    MDMSetting<String?>(null, key, localizedTitle) {
  override fun getFromBundle(bundle: Bundle) = bundle.getString(key)
  override fun getFromPrefs(prefs: SharedPreferences) = prefs.getString(key, null)
}

class StringArrayListMDMSetting(key: String, localizedTitle: String) :
    MDMSetting<List<String>?>(null, key, localizedTitle) {
  override fun getFromBundle(bundle: Bundle): List<String>? {
    // Try to retrieve the value as a String[] first
    val stringArray = bundle.getStringArray(key)
    if (stringArray != null) {
        return stringArray.toList()
    }
    
    // Optionally, handle other types if necessary
    val stringArrayList = bundle.getStringArrayList(key)
    if (stringArrayList != null) {
        return stringArrayList
    }

    // If neither String[] nor ArrayList<String> is found, return null
    return null
  }

  override fun getFromPrefs(prefs: SharedPreferences): List<String>? {
      return prefs.getStringSet(key, HashSet<String>())?.toList()
  }
}

class AlwaysNeverUserDecidesMDMSetting(key: String, localizedTitle: String) :
    MDMSetting<AlwaysNeverUserDecides>(AlwaysNeverUserDecides.UserDecides, key, localizedTitle) {
  override fun getFromBundle(bundle: Bundle) =
      AlwaysNeverUserDecides.fromString(bundle.getString(key))
  override fun getFromPrefs(prefs: SharedPreferences) =
      AlwaysNeverUserDecides.fromString(prefs.getString(key, null))
}

class ShowHideMDMSetting(key: String, localizedTitle: String) :
    MDMSetting<ShowHide>(ShowHide.Show, key, localizedTitle) {
  override fun getFromBundle(bundle: Bundle) =
      ShowHide.fromString(bundle.getString(key))
  override fun getFromPrefs(prefs: SharedPreferences) =
      ShowHide.fromString(prefs.getString(key, null))
}

enum class AlwaysNeverUserDecides(val value: String) {
  Always("always"),
  Never("never"),
  UserDecides("user-decides");

  val hiddenFromUser: Boolean
    get() {
      return this != UserDecides
    }

  override fun toString(): String {
    return value
  }

  companion object {
    fun fromString(value: String?): AlwaysNeverUserDecides {
      return values().find { it.value == value } ?: UserDecides
    }
  }
}

enum class ShowHide(val value: String) {
  Show("show"),
  Hide("hide");

  override fun toString(): String {
    return value
  }

  companion object {
    fun fromString(value: String?): ShowHide {
      return ShowHide.values().find { it.value == value } ?: Show
    }
  }
}
