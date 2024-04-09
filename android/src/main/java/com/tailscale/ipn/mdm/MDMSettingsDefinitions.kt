// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mdm

import android.os.Bundle
import android.util.Log
import com.tailscale.ipn.App
import com.tailscale.ipn.ui.util.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Don't keep track of the key if shouldRegisterKey is false - shouldRegisterKey should be false when creating an MDMSetting to check whether a key has been registered with that setting.
abstract class MDMSetting<T>(defaultValue: T, val key: String, val localizedTitle: String, private val shouldRegisterKey: Boolean = true) {
  private val keys = mutableSetOf<String>()

    init {
      if (shouldRegisterKey) { registerKey(key)
    }
  }
  val flow: StateFlow<T> = MutableStateFlow<T>(defaultValue)

  private fun registerKey(key: String) {
    if (!keyExists(key)) {
        keys.add(key)
    }
}

fun keyExists(key: String): Boolean {
    return keys.contains(key)
}

  fun setFrom(bundle: Bundle?, app: App) {
    val v = getFrom(bundle, app)
    flow.set(v)
  }

  abstract fun getFrom(bundle: Bundle?, app: App): T
}

class BooleanMDMSetting(key: String, localizedTitle: String) :
    MDMSetting<Boolean>(false, key, localizedTitle) {
  override fun getFrom(bundle: Bundle?, app: App) =
      bundle?.getBoolean(key) ?: app.getEncryptedPrefs().getBoolean(key, false)
}

class StringMDMSetting(key: String, localizedTitle: String, shouldRegisterKey: Boolean = true) :
    MDMSetting<String?>(null, key, localizedTitle, shouldRegisterKey) {
  override fun getFrom(bundle: Bundle?, app: App) =
      bundle?.getString(key) ?: app.getEncryptedPrefs().getString(key, null)
}

class StringArrayListMDMSetting(key: String, localizedTitle: String) :
    MDMSetting<List<String>?>(null, key, localizedTitle) {
  override fun getFrom(bundle: Bundle?, app: App) =
      bundle?.getStringArrayList(key)
          ?: app.getEncryptedPrefs().getStringSet(key, HashSet<String>())?.toList()
}

class AlwaysNeverUserDecidesMDMSetting(key: String, localizedTitle: String, shouldRegisterKey: Boolean = true) :
    MDMSetting<AlwaysNeverUserDecides>(AlwaysNeverUserDecides.UserDecides, key, localizedTitle, shouldRegisterKey) {
  override fun getFrom(bundle: Bundle?, app: App): AlwaysNeverUserDecides {
    val storedString =
        bundle?.getString(key)
            ?: App.getApplication().getEncryptedPrefs().getString(key, null)
            ?: "user-decides"
    return when (storedString) {
      "always" -> {
        AlwaysNeverUserDecides.Always
      }
      "never" -> {
        AlwaysNeverUserDecides.Never
      }
      else -> {
        AlwaysNeverUserDecides.UserDecides
      }
    }
  }
}

class ShowHideMDMSetting(key: String, localizedTitle: String, shouldRegisterKey: Boolean = true) :
    MDMSetting<ShowHide>(ShowHide.Show, key, localizedTitle, shouldRegisterKey) {
  override fun getFrom(bundle: Bundle?, app: App): ShowHide {
    val storedString =
        bundle?.getString(key)
            ?: App.getApplication().getEncryptedPrefs().getString(key, null)
            ?: "show"
    return when (storedString) {
      "hide" -> {
        ShowHide.Hide
      }
      else -> {
        ShowHide.Show
      }
    }
  }
}

enum class AlwaysNeverUserDecides(val value: String) {
  Always("always"),
  Never("never"),
  UserDecides("user-decides")
}

enum class ShowHide(val value: String) {
  Show("show"),
  Hide("hide")
}
