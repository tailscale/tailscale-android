// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.MDMSettingsChangedReceiver
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.localapi.Request
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.HealthNotifier
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.ui.viewModel.VpnViewModel
import com.tailscale.ipn.ui.viewModel.VpnViewModelFactory
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.FeatureFlags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import libtailscale.Libtailscale
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.security.GeneralSecurityException
import java.util.Locale

class App : UninitializedApp(), libtailscale.AppContext, ViewModelStoreOwner {
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  companion object {
    private const val FILE_CHANNEL_ID = "tailscale-files"
    private const val TAG = "App"
    private lateinit var appInstance: App

    /**
     * Initializes the app (if necessary) and returns the singleton app instance. Always use this
     * function to obtain an App reference to make sure the app initializes.
     */
    @JvmStatic
    fun get(): App {
      appInstance.initOnce()
      return appInstance
    }
  }

  val dns = DnsConfig()
  private lateinit var connectivityManager: ConnectivityManager
  private lateinit var mdmChangeReceiver: MDMSettingsChangedReceiver
  private lateinit var app: libtailscale.Application

  override val viewModelStore: ViewModelStore
    get() = appViewModelStore

  private val appViewModelStore: ViewModelStore by lazy { ViewModelStore() }

  var healthNotifier: HealthNotifier? = null

  override fun getPlatformDNSConfig(): String = dns.dnsConfigAsString

  override fun getInstallSource(): String = AppSourceChecker.getInstallSource(this)

  override fun shouldUseGoogleDNSFallback(): Boolean = BuildConfig.USE_GOOGLE_DNS_FALLBACK

  override fun log(s: String, s1: String) {
    Log.d(s, s1)
  }

  fun getLibtailscaleApp(): libtailscale.Application {
    if (!isInitialized) {
      initOnce() // Calls the synchronized initialization logic
    }
    return app
  }

  override fun onCreate() {
    super.onCreate()
    appInstance = this
    setUnprotectedInstance(this)

    mdmChangeReceiver = MDMSettingsChangedReceiver()
    val filter = IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED)
    registerReceiver(mdmChangeReceiver, filter)

    createNotificationChannel(
        STATUS_CHANNEL_ID,
        getString(R.string.vpn_status),
        getString(R.string.optional_notifications_which_display_the_status_of_the_vpn_tunnel),
        NotificationManagerCompat.IMPORTANCE_MIN)
    createNotificationChannel(
        FILE_CHANNEL_ID,
        getString(R.string.taildrop_file_transfers),
        getString(R.string.notifications_delivered_when_a_file_is_received_using_taildrop),
        NotificationManagerCompat.IMPORTANCE_DEFAULT)
    createNotificationChannel(
        HealthNotifier.HEALTH_CHANNEL_ID,
        getString(R.string.health_channel_name),
        getString(R.string.health_channel_description),
        NotificationManagerCompat.IMPORTANCE_HIGH)
  }

  override fun onTerminate() {
    super.onTerminate()
    Notifier.stop()
    notificationManager.cancelAll()
    applicationScope.cancel()
    viewModelStore.clear()
    unregisterReceiver(mdmChangeReceiver)
  }

  @Volatile private var isInitialized = false

  @Synchronized
  private fun initOnce() {
    if (isInitialized) {
      return
    }

    initializeApp()
    isInitialized = true
  }

  private fun initializeApp() {
    val dataDir = this.filesDir.absolutePath

    // Set this to enable direct mode for taildrop whereby downloads will be saved directly
    // to the given folder.  We will preferentially use <shared>/Downloads and fallback to
    // an app local directory "Taildrop" if we cannot create that.  This mode does not support
    // user notifications for incoming files.
    val directFileDir = this.prepareDownloadsFolder()
    app = Libtailscale.start(dataDir, directFileDir.absolutePath, this)
    Request.setApp(app)
    Notifier.setApp(app)
    Notifier.start(applicationScope)
    healthNotifier = HealthNotifier(Notifier.health, Notifier.state, applicationScope)
    connectivityManager = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    NetworkChangeCallback.monitorDnsChanges(connectivityManager, dns)
    initViewModels()
    applicationScope.launch {
      Notifier.state.collect { _ ->
        combine(Notifier.state, MDMSettings.forceEnabled.flow) { state, forceEnabled ->
              Pair(state, forceEnabled)
            }
            .collect { (state, hideDisconnectAction) ->
              val ableToStartVPN = state > Ipn.State.NeedsMachineAuth
              // If VPN is stopped, show a disconnected notification. If it is running as a
              // foreground
              // service, IPNService will show a connected notification.
              if (state == Ipn.State.Stopped) {
                notifyStatus(vpnRunning = false, hideDisconnectAction = hideDisconnectAction.value)
              }

              val vpnRunning = state == Ipn.State.Starting || state == Ipn.State.Running
              updateConnStatus(ableToStartVPN)
              QuickToggleService.setVPNRunning(vpnRunning)

              // Update notification status when VPN is running
              if (vpnRunning) {
                notifyStatus(vpnRunning = true, hideDisconnectAction = hideDisconnectAction.value)
              }
            }
      }
    }
    applicationScope.launch {
      val hideDisconnectAction = MDMSettings.forceEnabled.flow.first()
    }
    TSLog.init(this)
    FeatureFlags.initialize(mapOf("enable_new_search" to false))
  }

  private fun initViewModels() {
    vpnViewModel = ViewModelProvider(this, VpnViewModelFactory(this)).get(VpnViewModel::class.java)
  }

  fun setWantRunning(wantRunning: Boolean, onSuccess: (() -> Unit)? = null) {
    val callback: (Result<Ipn.Prefs>) -> Unit = { result ->
      result.fold(
          onSuccess = { onSuccess?.invoke() },
          onFailure = { error ->
            TSLog.d("TAG", "Set want running: failed to update preferences: ${error.message}")
          })
    }
    Client(applicationScope)
        .editPrefs(Ipn.MaskedPrefs().apply { WantRunning = wantRunning }, callback)
  }

  // encryptToPref a byte array of data using the Jetpack Security
  // library and writes it to a global encrypted preference store.
  @Throws(IOException::class, GeneralSecurityException::class)
  override fun encryptToPref(prefKey: String?, plaintext: String?) {
    getEncryptedPrefs().edit().putString(prefKey, plaintext).commit()
  }

  // decryptFromPref decrypts a encrypted preference using the Jetpack Security
  // library and returns the plaintext.
  @Throws(IOException::class, GeneralSecurityException::class)
  override fun decryptFromPref(prefKey: String?): String? {
    return getEncryptedPrefs().getString(prefKey, null)
  }

  @Throws(IOException::class, GeneralSecurityException::class)
  fun getEncryptedPrefs(): SharedPreferences {
    val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    return EncryptedSharedPreferences.create(
        this,
        "secret_shared_prefs",
        key,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
  }

  /*
   * setAbleToStartVPN remembers whether or not we're able to start the VPN
   * by storing this in a shared preference. This allows us to check this
   * value without needing a fully initialized instance of the application.
   */
  private fun updateConnStatus(ableToStartVPN: Boolean) {
    setAbleToStartVPN(ableToStartVPN)
    QuickToggleService.updateTile()
    TSLog.d("App", "Set Tile Ready: $ableToStartVPN")
  }

  override fun getModelName(): String {
    val manu = Build.MANUFACTURER
    var model = Build.MODEL
    // Strip manufacturer from model.
    val idx = model.lowercase(Locale.getDefault()).indexOf(manu.lowercase(Locale.getDefault()))
    if (idx != -1) {
      model = model.substring(idx + manu.length).trim()
    }
    return "$manu $model"
  }

  override fun getOSVersion(): String = Build.VERSION.RELEASE

  override fun isChromeOS(): Boolean {
    return packageManager.hasSystemFeature("android.hardware.type.pc")
  }

  override fun getInterfacesAsString(): String {
    val interfaces: ArrayList<NetworkInterface> =
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces())

    val sb = StringBuilder()
    for (nif in interfaces) {
      try {
        sb.append(
            String.format(
                Locale.ROOT,
                "%s %d %d %b %b %b %b %b |",
                nif.name,
                nif.index,
                nif.mtu,
                nif.isUp,
                nif.supportsMulticast(),
                nif.isLoopback,
                nif.isPointToPoint,
                nif.supportsMulticast()))

        for (ia in nif.interfaceAddresses) {
          val parts = ia.toString().split("/", limit = 0)
          if (parts.size > 1) {
            sb.append(String.format(Locale.ROOT, "%s/%d ", parts[1], ia.networkPrefixLength))
          }
        }
      } catch (e: Exception) {
        continue
      }
      sb.append("\n")
    }

    return sb.toString()
  }

  private fun prepareDownloadsFolder(): File {
    var downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    try {
      if (!downloads.exists()) {
        downloads.mkdirs()
      }
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to create downloads folder: $e")
      downloads = File(this.filesDir, "Taildrop")
      try {
        if (!downloads.exists()) {
          downloads.mkdirs()
        }
      } catch (e: Exception) {
        TSLog.e(TAG, "Failed to create Taildrop folder: $e")
        downloads = File("")
      }
    }

    return downloads
  }

  @Throws(
      IOException::class, GeneralSecurityException::class, MDMSettings.NoSuchKeyException::class)
  override fun getSyspolicyBooleanValue(key: String): Boolean {
    return getSyspolicyStringValue(key) == "true"
  }

  @Throws(
      IOException::class, GeneralSecurityException::class, MDMSettings.NoSuchKeyException::class)
  override fun getSyspolicyStringValue(key: String): String {
    val setting = MDMSettings.allSettingsByKey[key]?.flow?.value
    if (setting?.isSet != true) {
      throw MDMSettings.NoSuchKeyException()
    }
    return setting.value?.toString() ?: ""
  }

  @Throws(
      IOException::class, GeneralSecurityException::class, MDMSettings.NoSuchKeyException::class)
  override fun getSyspolicyStringArrayJSONValue(key: String): String {
    val setting = MDMSettings.allSettingsByKey[key]?.flow?.value
    if (setting?.isSet != true) {
      throw MDMSettings.NoSuchKeyException()
    }
    try {
      val list = setting.value as? List<*>
      return Json.encodeToString(list)
    } catch (e: Exception) {
      TSLog.d("MDM", "$key value cannot be serialized to JSON. Throwing NoSuchKeyException.")
      throw MDMSettings.NoSuchKeyException()
    }
  }

  fun notifyPolicyChanged() {
    app.notifyPolicyChanged()
  }
}

/**
 * UninitializedApp contains all of the methods of App that can be used without having to initialize
 * the Go backend. This is useful when you want to access functions on the App without creating side
 * effects from starting the Go backend (such as launching the VPN).
 */
open class UninitializedApp : Application() {
  companion object {
    const val TAG = "UninitializedApp"

    const val STATUS_NOTIFICATION_ID = 1
    const val STATUS_EXIT_NODE_FAILURE_NOTIFICATION_ID = 2
    const val STATUS_CHANNEL_ID = "tailscale-status"

    // Key for shared preference that tracks whether or not we're able to start
    // the VPN (i.e. we're logged in and machine is authorized).
    private const val ABLE_TO_START_VPN_KEY = "ableToStartVPN"

    private const val DISALLOWED_APPS_KEY = "disallowedApps"

    // File for shared preferences that are not encrypted.
    private const val UNENCRYPTED_PREFERENCES = "unencrypted"

    private lateinit var appInstance: UninitializedApp
    lateinit var notificationManager: NotificationManagerCompat

    lateinit var vpnViewModel: VpnViewModel

    @JvmStatic
    fun get(): UninitializedApp {
      return appInstance
    }
  }

  protected fun setUnprotectedInstance(instance: UninitializedApp) {
    appInstance = instance
  }

  protected fun setAbleToStartVPN(rdy: Boolean) {
    getUnencryptedPrefs().edit().putBoolean(ABLE_TO_START_VPN_KEY, rdy).apply()
  }

  /** This function can be called without initializing the App. */
  fun isAbleToStartVPN(): Boolean {
    return getUnencryptedPrefs().getBoolean(ABLE_TO_START_VPN_KEY, false)
  }

  private fun getUnencryptedPrefs(): SharedPreferences {
    return getSharedPreferences(UNENCRYPTED_PREFERENCES, MODE_PRIVATE)
  }

  fun startVPN() {
    val intent = Intent(this, IPNService::class.java).apply { action = IPNService.ACTION_START_VPN }
    // FLAG_UPDATE_CURRENT ensures that if the intent is already pending, the existing intent will
    // be updated rather than creating multiple redundant instances.
    val pendingIntent =
        PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE // FLAG_IMMUTABLE for Android 12+
            )

    try {
      pendingIntent.send()
    } catch (foregroundServiceStartException: IllegalStateException) {
      TSLog.e(
          TAG,
          "startVPN hit ForegroundServiceStartNotAllowedException: $foregroundServiceStartException")
    } catch (securityException: SecurityException) {
      TSLog.e(TAG, "startVPN hit SecurityException: $securityException")
    } catch (e: Exception) {
      TSLog.e(TAG, "startVPN hit exception: $e")
    }
  }

  fun stopVPN() {
    val intent = Intent(this, IPNService::class.java).apply { action = IPNService.ACTION_STOP_VPN }
    try {
      startService(intent)
    } catch (illegalStateException: IllegalStateException) {
      TSLog.e(TAG, "stopVPN hit IllegalStateException in startService(): $illegalStateException")
    } catch (e: Exception) {
      TSLog.e(TAG, "stopVPN hit exception in startService(): $e")
    }
  }

  fun restartVPN() {
    // Register a receiver to listen for the completion of stopVPN
    val stopReceiver =
        object : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
            // Ensure stop intent is complete
            if (intent?.action == IPNService.ACTION_STOP_VPN) {
              // Unregister receiver after receiving the broadcast
              context?.unregisterReceiver(this)
              // Now start the VPN
              startVPN()
            }
          }
        }

    // Register the receiver before stopping VPN
    val intentFilter = IntentFilter(IPNService.ACTION_STOP_VPN)
    this.registerReceiver(stopReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)

    stopVPN()
  }

  fun createNotificationChannel(id: String, name: String, description: String, importance: Int) {
    val channel = NotificationChannel(id, name, importance)
    channel.description = description
    notificationManager = NotificationManagerCompat.from(this)
    notificationManager.createNotificationChannel(channel)
  }

  fun notifyStatus(vpnRunning: Boolean, hideDisconnectAction: Boolean) {
    notifyStatus(buildStatusNotification(vpnRunning, hideDisconnectAction))
  }

  fun notifyStatus(notification: Notification) {
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED) {
      // TODO: Consider calling
      //    ActivityCompat#requestPermissions
      // here to request the missing permissions, and then overriding
      //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
      //                                          int[] grantResults)
      // to handle the case where the user grants the permission. See the documentation
      // for ActivityCompat#requestPermissions for more details.
      return
    }
    notificationManager.notify(STATUS_NOTIFICATION_ID, notification)
  }

  fun buildStatusNotification(vpnRunning: Boolean, hideDisconnectAction: Boolean): Notification {
    val message = getString(if (vpnRunning) R.string.connected else R.string.not_connected)
    val icon = if (vpnRunning) R.drawable.ic_notification else R.drawable.ic_notification_disabled
    val action =
        if (vpnRunning) IPNReceiver.INTENT_DISCONNECT_VPN else IPNReceiver.INTENT_CONNECT_VPN
    val actionLabel = getString(if (vpnRunning) R.string.disconnect else R.string.connect)
    val buttonIntent = Intent(this, IPNReceiver::class.java).apply { this.action = action }
    val pendingButtonIntent: PendingIntent =
        PendingIntent.getBroadcast(
            this,
            0,
            buttonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val intent =
        Intent(this, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    val pendingIntent: PendingIntent =
        PendingIntent.getActivity(
            this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    val builder =
        NotificationCompat.Builder(this, STATUS_CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(message)
            .setAutoCancel(!vpnRunning)
            .setOnlyAlertOnce(!vpnRunning)
            .setOngoing(vpnRunning)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
    if (!vpnRunning || !hideDisconnectAction) {
      builder.addAction(
          NotificationCompat.Action.Builder(0, actionLabel, pendingButtonIntent).build())
    }
    return builder.build()
  }

  fun addUserDisallowedPackageName(packageName: String) {
    if (packageName.isEmpty()) {
      TSLog.e(TAG, "addUserDisallowedPackageName called with empty packageName")
      return
    }

    getUnencryptedPrefs()
        .edit()
        .putStringSet(
            DISALLOWED_APPS_KEY, disallowedPackageNames().toMutableSet().union(setOf(packageName)))
        .apply()

    this.restartVPN()
  }

  fun removeUserDisallowedPackageName(packageName: String) {
    if (packageName.isEmpty()) {
      TSLog.e(TAG, "removeUserDisallowedPackageName called with empty packageName")
      return
    }

    getUnencryptedPrefs()
        .edit()
        .putStringSet(
            DISALLOWED_APPS_KEY,
            disallowedPackageNames().toMutableSet().subtract(setOf(packageName)))
        .apply()

    this.restartVPN()
  }

  fun disallowedPackageNames(): List<String> {
    val mdmDisallowed =
        MDMSettings.excludedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()
    if (mdmDisallowed.isNotEmpty()) {
      TSLog.d(TAG, "Excluded application packages were set via MDM: $mdmDisallowed")
      return builtInDisallowedPackageNames + mdmDisallowed
    }
    val userDisallowed =
        getUnencryptedPrefs().getStringSet(DISALLOWED_APPS_KEY, emptySet())?.toList() ?: emptyList()
    return builtInDisallowedPackageNames + userDisallowed
  }

  fun getAppScopedViewModel(): VpnViewModel {
    return vpnViewModel
  }

  val builtInDisallowedPackageNames: List<String> =
      listOf(
          // RCS/Jibe https://github.com/tailscale/tailscale/issues/2322
          "com.google.android.apps.messaging",
          // Android Auto https://github.com/tailscale/tailscale/issues/3828
          "com.google.android.projection.gearhead",
          // GoPro https://github.com/tailscale/tailscale/issues/2554
          "com.gopro.smarty",
          // Sonos https://github.com/tailscale/tailscale/issues/2548
          "com.sonos.acr",
          "com.sonos.acr2",
          // Google Chromecast https://github.com/tailscale/tailscale/issues/3636
          "com.google.android.apps.chromecast.app",
          // Voicemail https://github.com/tailscale/tailscale/issues/13199
          "com.samsung.attvvm",
          "com.att.mobile.android.vvm",
          "com.tmobile.vvm.application",
          "com.metropcs.service.vvm",
          "com.mizmowireless.vvm",
          "com.vna.service.vvm",
          "com.dish.vvm",
          "com.comcast.modesto.vvm.client",
          // Android Connectivity Service https://github.com/tailscale/tailscale/issues/14128
          "com.google.android.apps.scone",
      )
}
