// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mdm

import android.content.Context
import android.os.Bundle
import com.tailscale.ipn.util.TSLog
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Loads developer-defined policy overrides from a JSON file embedded in the APK, for use when no
 * real MDM provider is available. See the documentation at the top of [MDMSettings] for the file
 * format and for instructions on creating it.
 *
 * This is the debug build variant of this object. The release variant, in `src/release`, is a stub
 * which always reports no overrides, so neither this parser nor the policy file itself is present
 * in release builds.
 */
object DebugPolicies {
  private const val TAG = "syspolicy"
  private const val ASSET_NAME = "debug_policies.json"

  private val _isActive = MutableStateFlow(false)

  /**
   * Whether the most recent call to [overrideBundle] returned developer-defined policies. Used to
   * warn in the debug UI that the displayed values do not come from a real MDM provider.
   */
  val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

  private val json = Json { isLenient = true }

  /**
   * Returns a restrictions [Bundle] synthesized from the embedded policy file, in the same shape
   * that `RestrictionsManager` would have produced, or null if there are no overrides to apply.
   *
   * A non-null result replaces the real restrictions entirely: policies absent from the file read
   * as unset, even if an MDM provider has configured them.
   */
  fun overrideBundle(context: Context): Bundle? {
    val bundle = load(context)
    _isActive.value = bundle != null
    return bundle
  }

  private fun load(context: Context): Bundle? {
    val text =
        try {
          context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (_: FileNotFoundException) {
          return null
        } catch (e: Exception) {
          TSLog.e(TAG, "Could not read assets/$ASSET_NAME: $e")
          return null
        }

    val root =
        try {
          json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
          TSLog.e(TAG, "assets/$ASSET_NAME is not a valid JSON object: $e")
          return null
        }

    if (root["enabled"]?.asPrimitive()?.booleanOrNull == false) {
      TSLog.d(TAG, "assets/$ASSET_NAME is present but disabled. Using real MDM policies.")
      return null
    }

    val policies = root["policies"] as? JsonObject
    if (policies == null) {
      TSLog.e(TAG, "assets/$ASSET_NAME has no \"policies\" object. Using real MDM policies.")
      return null
    }

    TSLog.w(
        TAG,
        "Reading developer-defined policies from assets/$ASSET_NAME. " +
            "Policies from a real MDM provider, if any, are being ignored.",
    )

    val bundle = Bundle()
    var applied = 0
    for ((key, element) in policies) {
      // Keys starting with an underscore are comments, since JSON has none.
      if (key.startsWith("_")) continue
      val setting = MDMSettings.allSettingsByKey[key]
      if (setting == null) {
        TSLog.e(TAG, "assets/$ASSET_NAME: unknown policy key \"$key\". Ignoring it.")
        continue
      }
      if (put(bundle, setting, element)) {
        applied++
        TSLog.d(TAG, "assets/$ASSET_NAME: $key = $element")
      } else if (element is JsonNull) {
        TSLog.d(TAG, "assets/$ASSET_NAME: $key = null, leaving it unset")
      }
    }

    TSLog.w(TAG, "Using $applied developer-defined policy setting(s) from assets/$ASSET_NAME.")
    return bundle
  }

  /**
   * Coerces [element] to the type [setting] was declared with and stores it in [bundle]. Returns
   * whether a value was stored.
   */
  private fun put(bundle: Bundle, setting: MDMSetting<*>, element: JsonElement): Boolean {
    val key = setting.key
    if (element is JsonNull) {
      // An explicit null means "leave this policy unset", which is useful for temporarily
      // disabling a single entry without deleting it.
      return false
    }

    when (setting) {
      is BooleanMDMSetting -> {
        val value =
            element.asPrimitive()?.let { it.booleanOrNull ?: it.content.toBooleanStrictOrNull() }
                ?: return typeError(key, "true or false", element)
        bundle.putBoolean(key, value)
      }

      is StringMDMSetting -> {
        val value = element.asString() ?: return typeError(key, "a string", element)
        bundle.putString(key, value)
      }

      is StringArrayListMDMSetting -> {
        val array = element as? JsonArray ?: return typeError(key, "an array of strings", element)
        val values = array.map {
          it.asString() ?: return typeError(key, "an array of strings", element)
        }
        bundle.putStringArray(key, values.toTypedArray())
      }

      is AlwaysNeverUserDecidesMDMSetting -> {
        val value = element.asString() ?: return typeError(key, "a string", element)
        val allowed = AlwaysNeverUserDecides.values().map { it.value }
        if (value !in allowed) return valueError(key, value, allowed)
        bundle.putString(key, value)
      }

      is ShowHideMDMSetting -> {
        val value = element.asString() ?: return typeError(key, "a string", element)
        val allowed = ShowHide.values().map { it.value }
        if (value !in allowed) return valueError(key, value, allowed)
        bundle.putString(key, value)
      }

      else -> {
        TSLog.e(TAG, "assets/$ASSET_NAME: policy \"$key\" has an unsupported type. Ignoring it.")
        return false
      }
    }
    return true
  }

  private fun JsonElement.asPrimitive(): JsonPrimitive? =
      (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

  /**
   * Returns the contents of a JSON string, or null for anything else. Unquoted values are rejected
   * rather than stringified, so that a mistake like `"ExitNodeID": 123` is reported instead of
   * silently becoming "123".
   */
  private fun JsonElement.asString(): String? = asPrimitive()?.takeIf { it.isString }?.content

  private fun typeError(key: String, expected: String, element: JsonElement): Boolean {
    TSLog.e(
        TAG,
        "assets/$ASSET_NAME: policy \"$key\" must be $expected, got $element. Ignoring it.",
    )
    return false
  }

  private fun valueError(key: String, value: String, allowed: List<String>): Boolean {
    TSLog.e(
        TAG,
        "assets/$ASSET_NAME: policy \"$key\" must be one of ${allowed.joinToString(", ")}, " +
            "got \"$value\". Ignoring it.",
    )
    return false
  }
}
