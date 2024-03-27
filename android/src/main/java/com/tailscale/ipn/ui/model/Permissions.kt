// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import android.Manifest
import android.os.Build
import com.tailscale.ipn.R

object Permissions {
  /**
   * All permissions that Tailscale requires. MainView takes care of prompting for permissions, and
   * PermissionsView provides a list of permissions with corresponding statuses and a link to the
   * application settings.
   *
   * When new permissions are needed, just add them to this list and the necessary strings to
   * strings.xml and the rest should take care of itself.
   */
  val all: List<Permission>
    get() {
      val result = mutableListOf<Permission>()
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        result.add(
            Permission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                R.string.permission_write_external_storage,
                R.string.permission_write_external_storage_needed,
                R.string.permission_write_external_storage_granted,
            ))
      } else {
        result.add(
            Permission(
                Manifest.permission.POST_NOTIFICATIONS,
                R.string.permission_post_notifications,
                R.string.permission_post_notifications_needed,
                R.string.permission_post_notifications_granted))
      }
      return result
    }
}

data class Permission(
    val name: String,
    val title: Int,
    val neededDescription: Int,
    val grantedDescription: Int
)
