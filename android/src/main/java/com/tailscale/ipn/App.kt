// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.DownloadManager
import android.app.Fragment
import android.app.FragmentTransaction
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.localapi.Request
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import libtailscale.Libtailscale
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.GeneralSecurityException
import java.util.Locale

class App : Application(), libtailscale.AppContext {
  val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  companion object {
    const val STATUS_CHANNEL_ID = "tailscale-status"
    const val STATUS_NOTIFICATION_ID = 1
    private const val PEER_TAG = "peer"
    private const val FILE_CHANNEL_ID = "tailscale-files"
    private const val FILE_NOTIFICATION_ID = 2
    private const val TAG = "App"
    private val networkConnectivityRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    private lateinit var appInstance: App

    @JvmStatic
    fun startActivityForResult(act: Activity, intent: Intent?, request: Int) {
      val f: Fragment = act.fragmentManager.findFragmentByTag(PEER_TAG)
      f.startActivityForResult(intent, request)
    }

    /**
     * Initializes the app (if necessary) and returns the singleton app instance. Always use this
     * function to obtain an App reference to make sure the app initializes.
     */
    @JvmStatic
    fun getApplication(): App {
      appInstance.initOnce()
      return appInstance
    }
  }

  val dns = DnsConfig()
  private lateinit var connectivityManager: ConnectivityManager
  private lateinit var app: libtailscale.Application

  override fun getPlatformDNSConfig(): String = dns.dnsConfigAsString

  override fun isPlayVersion(): Boolean = MaybeGoogle.isGoogle()

  override fun log(s: String, s1: String) {
    Log.d(s, s1)
  }

  override fun onCreate() {
    super.onCreate()
    appInstance = this
  }

  override fun onTerminate() {
    super.onTerminate()
    Notifier.stop()
    applicationScope.cancel()
  }

  var initialized = false

  @Synchronized
  private fun initOnce() {
    if (initialized) {
      return
    }
    initialized = true

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
    connectivityManager = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    setAndRegisterNetworkCallbacks()
    createNotificationChannel(
        STATUS_CHANNEL_ID, "VPN Status", NotificationManagerCompat.IMPORTANCE_LOW)
    createNotificationChannel(
        FILE_CHANNEL_ID, "File transfers", NotificationManagerCompat.IMPORTANCE_DEFAULT)
    applicationScope.launch {
      Notifier.state.collect { state ->
        val ableToStartVPN = state > Ipn.State.NeedsMachineAuth
        val vpnRunning = state == Ipn.State.Starting || state == Ipn.State.Running
        updateConnStatus(ableToStartVPN, vpnRunning)
        QuickToggleService.setVPNRunning(this@App, vpnRunning)
      }
    }
  }

  fun setWantRunning(wantRunning: Boolean) {
    val callback: (Result<Ipn.Prefs>) -> Unit = { result ->
      result.fold(
          onSuccess = {},
          onFailure = { error ->
            Log.d("TAG", "Set want running: failed to update preferences: ${error.message}")
          })
    }
    Client(applicationScope)
        .editPrefs(Ipn.MaskedPrefs().apply { WantRunning = wantRunning }, callback)
  }

  // requestNetwork attempts to find the best network that matches the passed NetworkRequest. It is
  // possible that this might return an unusuable network, eg a captive portal.
  private fun setAndRegisterNetworkCallbacks() {
    connectivityManager.requestNetwork(
        networkConnectivityRequest,
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            super.onAvailable(network)

            val sb = StringBuilder()
            val linkProperties: LinkProperties? = connectivityManager.getLinkProperties(network)
            val dnsList: MutableList<InetAddress> = linkProperties?.dnsServers ?: mutableListOf()
            for (ip in dnsList) {
              sb.append(ip.hostAddress).append(" ")
            }
            val searchDomains: String? = linkProperties?.domains
            if (searchDomains != null) {
              sb.append("\n")
              sb.append(searchDomains)
            }

            if (dns.updateDNSFromNetwork(sb.toString())) {
              Libtailscale.onDNSConfigChanged(linkProperties?.getInterfaceName())
            }
          }

          override fun onLost(network: Network) {
            super.onLost(network)
            if (dns.updateDNSFromNetwork("")) {
              Libtailscale.onDNSConfigChanged("")
            }
          }
        })
  }

  fun startVPN() {
    val intent = Intent(this, IPNService::class.java)
    intent.setAction(IPNService.ACTION_REQUEST_VPN)
    startService(intent)
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

  fun updateConnStatus(ableToStartVPN: Boolean, vpnRunning: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return
    }
    QuickToggleService.setAbleToStartVPN(this, ableToStartVPN)
    Log.d("App", "Set Tile Ready: $ableToStartVPN")
    val action =
        if (ableToStartVPN) IPNReceiver.INTENT_DISCONNECT_VPN else IPNReceiver.INTENT_CONNECT_VPN
    val intent = Intent(this, IPNReceiver::class.java).apply { this.action = action }
    val pendingIntent: PendingIntent =
        PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notificationMessage =
        if (vpnRunning) getString(R.string.connected) else getString(R.string.not_connected)
    notify(
        "Tailscale", notificationMessage, STATUS_CHANNEL_ID, pendingIntent, STATUS_NOTIFICATION_ID)
  }

  fun getHostname(): String {
    val userConfiguredDeviceName = getUserConfiguredDeviceName()
    if (!userConfiguredDeviceName.isNullOrEmpty()) return userConfiguredDeviceName

    return modelName
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

  // get user defined nickname from Settings
  // returns null if not available
  private fun getUserConfiguredDeviceName(): String? {
    val nameFromSystemDevice = Settings.Secure.getString(contentResolver, "device_name")
    if (!nameFromSystemDevice.isNullOrEmpty()) return nameFromSystemDevice
    return null
  }

  // attachPeer adds a Peer fragment for tracking the Activity
  // lifecycle.
  fun attachPeer(act: Activity) {
    act.runOnUiThread(
        Runnable {
          val ft: FragmentTransaction = act.fragmentManager.beginTransaction()
          ft.add(Peer(), PEER_TAG)
          ft.commit()
          act.fragmentManager.executePendingTransactions()
        })
  }

  override fun isChromeOS(): Boolean {
    return packageManager.hasSystemFeature("android.hardware.type.pc")
  }

  fun prepareVPN(act: Activity, reqCode: Int) {
    act.runOnUiThread(
        Runnable {
          val intent: Intent? = VpnService.prepare(act)
          if (intent == null) {
            startVPN()
          } else {
            startActivityForResult(act, intent, reqCode)
          }
        })
  }

  fun showURL(act: Activity, url: String?) {
    act.runOnUiThread(
        Runnable {
          val builder: CustomTabsIntent.Builder = CustomTabsIntent.Builder()
          val headerColor = -0xb69b6b
          builder.setToolbarColor(headerColor)
          val intent: CustomTabsIntent = builder.build()
          intent.launchUrl(act, Uri.parse(url))
        })
  }

  @get:Throws(Exception::class)
  val packageCertificate: ByteArray?
    // getPackageSignatureFingerprint returns the first package signing certificate, if any.
    get() {
      val info: PackageInfo
      info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
      for (signature in info.signatures) {
        return signature.toByteArray()
      }
      return null
    }

  @Throws(IOException::class)
  fun openUri(uri: String?, mode: String?): Int? {
    val resolver: ContentResolver = contentResolver
    return mode?.let { resolver.openFileDescriptor(Uri.parse(uri), it)?.detachFd() }
  }

  fun deleteUri(uri: String?) {
    val resolver: ContentResolver = contentResolver
    resolver.delete(Uri.parse(uri), null, null)
  }

  fun notifyFile(uri: String?, msg: String?) {
    val viewIntent: Intent
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
    } else {
      // uri is a file:// which is not allowed to be shared outside the app.
      viewIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
    }
    val pending: PendingIntent =
        PendingIntent.getActivity(this, 0, viewIntent, PendingIntent.FLAG_UPDATE_CURRENT)
    notify(
        getString(R.string.file_notification), msg, FILE_CHANNEL_ID, pending, FILE_NOTIFICATION_ID)
  }

  fun createNotificationChannel(id: String?, name: String?, importance: Int) {
    val channel = NotificationChannel(id, name, importance)
    val nm: NotificationManagerCompat = NotificationManagerCompat.from(this)
    nm.createNotificationChannel(channel)
  }

  fun notify(
      title: String?,
      message: String?,
      channel: String,
      intent: PendingIntent?,
      notificationID: Int
  ) {
    val builder: NotificationCompat.Builder =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
    val nm: NotificationManagerCompat = NotificationManagerCompat.from(this)
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
    nm.notify(notificationID, builder.build())
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

  fun isTV(): Boolean {
    val mm = getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
    return mm.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
  }

  fun prepareDownloadsFolder(): File {
    var downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    try {
      if (!downloads.exists()) {
        downloads.mkdirs()
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to create downloads folder: $e")
      downloads = File(this.filesDir, "Taildrop")
      try {
        if (!downloads.exists()) {
          downloads.mkdirs()
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed to create Taildrop folder: $e")
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
    return MDMSettings.allSettingsByKey[key]?.flow?.value?.toString()
        ?: run {
          Log.d("MDM", "$key is not defined on Android. Throwing NoSuchKeyException.")
          throw MDMSettings.NoSuchKeyException()
        }
  }

  @Throws(
      IOException::class, GeneralSecurityException::class, MDMSettings.NoSuchKeyException::class)
  override fun getSyspolicyStringArrayJSONValue(key: String): String {
    val list = MDMSettings.allSettingsByKey[key]?.flow?.value as? List<String>
    try {
      return Json.encodeToString(list)
    } catch (e: Exception) {
      Log.d("MDM", "$key is not defined on Android. Throwing NoSuchKeyException.")
      throw MDMSettings.NoSuchKeyException()
    }
  }
}
