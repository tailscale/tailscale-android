package com.tailscale.ipn.mdm

import android.content.RestrictionsManager
import com.tailscale.ipn.App

class MDMSettings(private val restrictionsManager: RestrictionsManager? = null) {
    // TODO(angott): implement a typed enum string array type
    val hiddenNetworkDevices: List<NetworkDevices> = emptyList()

    fun get(setting: BooleanSetting): Boolean {
        restrictionsManager?.let  { restrictionsManager ->
            restrictionsManager.applicationRestrictions.containsKey(setting.key)
            return restrictionsManager.applicationRestrictions.getBoolean(setting.key)
        } ?: run {
            return App.getApplication().encryptedPrefs.getBoolean(setting.key, false)
        }
    }

    fun get(setting: StringSetting): String? {
        return App.getApplication().encryptedPrefs.getString(setting.key, null)
    }

    fun get(setting: AlwaysNeverUserDecidesSetting): AlwaysNeverUserDecidesValue {
        val storedString = App.getApplication().encryptedPrefs.getString(setting.key, "user-decides")
                ?: "user-decides"
        return AlwaysNeverUserDecidesValue.valueOf(storedString)
    }

    fun get(setting: ShowHideSetting): ShowHideValue {
        val storedString = App.getApplication().encryptedPrefs.getString(setting.key, "show")
                ?: "show"
        return ShowHideValue.valueOf(storedString)
    }
}