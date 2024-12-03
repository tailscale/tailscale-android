// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import android.Manifest
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import com.tailscale.ipn.R

object Permissions {
  /** Permissions to prompt for on MainView. */
  @OptIn(ExperimentalPermissionsApi::class)
  val prompt: List<Pair<Permission, PermissionState>>
    @Composable
    get() {
      val permissionStates = rememberMultiplePermissionsState(permissions = all.map { it.name })
      return all.zip(permissionStates.permissions).filter { (_, state) ->
        !state.status.isGranted && !state.status.shouldShowRationale
      }
    }

  /** All permissions with granted status. */
  @OptIn(ExperimentalPermissionsApi::class)
  val withGrantedStatus: List<Pair<Permission, Boolean>>
    @Composable
    get() {
      val permissionStates = rememberMultiplePermissionsState(permissions = all.map { it.name })
      val result = mutableListOf<Pair<Permission, Boolean>>()
      result.addAll(
          all.zip(permissionStates.permissions).map { (permission, state) ->
            Pair(permission, state.status.isGranted)
          })
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        // On Android versions prior to 13, we have to programmatically check if notifications are
        // being allowed.
        val notificationsEnabled =
            NotificationManagerCompat.from(LocalContext.current).areNotificationsEnabled()
        result.add(
            Pair(
                Permission(
                    "",
                    R.string.permission_post_notifications,
                    R.string.permission_post_notifications_needed),
                notificationsEnabled))
      }
      return result
    }

  /**
   * All permissions that Tailscale requires. MainView takes care of prompting for permissions, and
   * PermissionsView provides a list of permissions with corresponding statuses and a link to the
   * application settings.
   *
   * When new permissions are needed, just add them to this list and the necessary strings to
   * strings.xml and the rest should take care of itself.
   */
  private val all: List<Permission> by lazy {
    val result = mutableListOf<Permission>()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      result.add(
          Permission(
              Manifest.permission.WRITE_EXTERNAL_STORAGE,
              R.string.permission_write_external_storage,
              R.string.permission_write_external_storage_needed,
          ))
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      result.add(
          Permission(
              Manifest.permission.POST_NOTIFICATIONS,
              R.string.permission_post_notifications,
              R.string.permission_post_notifications_needed))
    }
    result
  }
}

data class Permission(
    val name: String,
    val title: Int,
    val description: Int,
)
