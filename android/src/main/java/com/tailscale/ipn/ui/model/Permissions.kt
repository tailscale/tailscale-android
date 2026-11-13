// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.tailscale.ipn.R

object Permissions {
  /** Permissions to prompt for on MainView. */
  val prompt: List<Pair<Permission, () -> Unit>>
    @Composable
    get() {
      val context = LocalContext.current
      val activity = LocalActivity.current

      return all.mapNotNull { permission ->
        fun shouldPrompt(): Boolean =
            activity != null &&
                ContextCompat.checkSelfPermission(context, permission.name) !=
                    PackageManager.PERMISSION_GRANTED &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    permission.name,
                )

        val showPrompt =
            remember(context, activity, permission.name) { mutableStateOf(shouldPrompt()) }

        val launcher =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
              showPrompt.value = shouldPrompt()
            }

        LifecycleResumeEffect(context, activity, permission.name) {
          showPrompt.value = shouldPrompt()
          onPauseOrDispose {}
        }

        if (showPrompt.value) {
          permission to { launcher.launch(permission.name) }
        } else {
          null
        }
      }
    }

  /** All permissions with granted status. */
  val withGrantedStatus: List<Pair<Permission, Boolean>>
    @Composable
    get() {
      val context = LocalContext.current

      fun readStatuses(): List<Pair<Permission, Boolean>> {
        val result =
            all.map { permission ->
                  permission to
                      (ContextCompat.checkSelfPermission(context, permission.name) ==
                          PackageManager.PERMISSION_GRANTED)
                }
                .toMutableList()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
          result.add(
              Permission(
                  "",
                  R.string.permission_post_notifications,
                  R.string.permission_post_notifications_needed,
              ) to NotificationManagerCompat.from(context).areNotificationsEnabled()
          )
        }

        return result
      }

      val statuses = remember(context) { mutableStateOf(readStatuses()) }

      LifecycleResumeEffect(context) {
        statuses.value = readStatuses()
        onPauseOrDispose {}
      }

      return statuses.value
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
          )
      )
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      result.add(
          Permission(
              Manifest.permission.POST_NOTIFICATIONS,
              R.string.permission_post_notifications,
              R.string.permission_post_notifications_needed,
          )
      )
    }
    result
  }
}

data class Permission(
    val name: String,
    val title: Int,
    val description: Int,
)
