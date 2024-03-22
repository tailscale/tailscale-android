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
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.RestrictionsManager
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
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tailscale.ipn.mdm.AlwaysNeverUserDecidesSetting
import com.tailscale.ipn.mdm.BooleanSetting
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.mdm.ShowHideSetting
import com.tailscale.ipn.mdm.StringSetting
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.localapi.Request
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.notifier.Notifier
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.GeneralSecurityException
import java.util.Locale
import java.util.Objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import libtailscale.Libtailscale

class App : Application(), libtailscale.AppContext {
  private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  companion object {
    const val STATUS_CHANNEL_ID = "tailscale-status"
    const val STATUS_NOTIFICATION_ID = 1
    const val NOTIFY_CHANNEL_ID = "tailscale-notify"
    const val NOTIFY_NOTIFICATION_ID = 2
    private const val PEER_TAG = "peer"
    private const val FILE_CHANNEL_ID = "tailscale-files"
    private const val FILE_NOTIFICATION_ID = 3
    private const val TAG = "App"
    private val networkConnectivityRequest =
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    lateinit var appInstance: App

    @JvmStatic
    fun startActivityForResult(act: Activity, intent: Intent?, request: Int) {
      val f: Fragment = act.fragmentManager.findFragmentByTag(PEER_TAG)
      f.startActivityForResult(intent, request)
    }

    @JvmStatic
    fun getApplication(): App {
      return appInstance
    }
  }

  val dns = DnsConfig()
  var autoConnect = false
  var vpnReady = false
  private lateinit var connectivityManager: ConnectivityManager
  private lateinit var app: libtailscale.Application

  override fun getPlatformDNSConfig(): String = dns.dnsConfigAsString

  override fun isPlayVersion(): Boolean = MaybeGoogle.isGoogle()

  override fun log(s: String, s1: String) {
    Log.d(s, s1)
  }

  override fun onCreate() {
    super.onCreate()
    val dataDir = this.filesDir.absolutePath
    app = Libtailscale.start(dataDir, this)
    Request.setApp(app)
    Notifier.setApp(app)
    connectivityManager = this.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    setAndRegisterNetworkCallbacks()
    createNotificationChannel(
        NOTIFY_CHANNEL_ID, "Notifications", NotificationManagerCompat.IMPORTANCE_DEFAULT)
    createNotificationChannel(
        STATUS_CHANNEL_ID, "VPN Status", NotificationManagerCompat.IMPORTANCE_LOW)
    createNotificationChannel(
        FILE_CHANNEL_ID, "File transfers", NotificationManagerCompat.IMPORTANCE_DEFAULT)
    appInstance = this
    applicationScope.launch {
      Notifier.tileReady.collect { isTileReady -> setTileReady(isTileReady) }
    }
  }

  override fun onTerminate() {
    super.onTerminate()
    Notifier.stop()
    applicationScope.cancel()
  }

  fun setWantRunning(wantRunning: Boolean) {
    val callback: (Result<Ipn.Prefs>) -> Unit = { result ->
      result.exceptionOrNull()?.let { error ->
        Log.e(TAG, "Set want running: failed to update preferences: ${error.message}")
      }
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
              Libtailscale.onDnsConfigChanged()
            }
          }

          override fun onLost(network: Network) {
            super.onLost(network)
            if (dns.updateDNSFromNetwork("")) {
              Libtailscale.onDnsConfigChanged()
            }
          }
        })
  }

  fun startVPN() {
    val intent = Intent(this, IPNService::class.java)
    intent.setAction(IPNService.ACTION_REQUEST_VPN)
    startService(intent)
  }

  fun stopVPN() {
    val intent = Intent(this, IPNService::class.java)
    intent.setAction(IPNService.ACTION_STOP_VPN)
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

  fun setTileReady(ready: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return
    }
    QuickToggleService.setReady(this, ready)
    Log.d("App", "Set Tile Ready: $ready $autoConnect")
    vpnReady = ready
    if (ready && autoConnect) {
      startVPN()
    }
  }

  fun setTileStatus(status: Boolean) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
      return
    }
    QuickToggleService.setStatus(this, status)
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
  fun insertMedia(name: String?, mimeType: String): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val resolver: ContentResolver = contentResolver
      val contentValues = ContentValues()
      contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      if ("" != mimeType) {
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
      }
      val root: Uri = MediaStore.Files.getContentUri("external")
      resolver.insert(root, contentValues).toString()
    } else {
      val dir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
      dir.mkdirs()
      val f = File(dir, name)
      Uri.fromFile(f).toString()
    }
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
    val builder: NotificationCompat.Builder =
        NotificationCompat.Builder(this, FILE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("File received")
            .setContentText(msg)
            .setContentIntent(pending)
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
    nm.notify(FILE_NOTIFICATION_ID, builder.build())
  }

  fun createNotificationChannel(id: String?, name: String?, importance: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return
    }
    val channel = NotificationChannel(id, name, importance)
    val nm: NotificationManagerCompat = NotificationManagerCompat.from(this)
    nm.createNotificationChannel(channel)
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

  /*
  The following methods are called by the syspolicy handler from Go via JNI.
   */
  fun getSyspolicyBooleanValue(key: String?): Boolean? {
    val manager: RestrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    val mdmSettings = MDMSettings(manager)
    val setting: BooleanSetting? = key?.let { BooleanSetting.valueOf(it) }
    return setting?.let { mdmSettings.get(it) }
  }

  fun getSyspolicyStringValue(key: String): String {
    val manager: RestrictionsManager =
        this.getSystemService(Context.RESTRICTIONS_SERVICE) as RestrictionsManager
    val mdmSettings = MDMSettings(manager)

    // Before looking for a StringSetting matching the given key, Go could also be
    // asking us for either a AlwaysNeverUserDecidesSetting or a ShowHideSetting.
    // Check the enum cases for these two before looking for a StringSetting.
    return try {
      val anuSetting: AlwaysNeverUserDecidesSetting = AlwaysNeverUserDecidesSetting.valueOf(key)
      mdmSettings.get(anuSetting).value
    } catch (eanu: IllegalArgumentException) { // AlwaysNeverUserDecidesSetting does not exist
      try {
        val showHideSetting: ShowHideSetting = ShowHideSetting.valueOf(key)
        mdmSettings.get(showHideSetting).value
      } catch (esh: IllegalArgumentException) {
        try {
          val stringSetting: StringSetting = StringSetting.valueOf(key)
          val value: String? = mdmSettings.get(stringSetting)
          Objects.requireNonNullElse<String>(value, "")
        } catch (estr: IllegalArgumentException) {
          Log.d("MDM", "$key is not defined on Android. Returning empty.")
          ""
        }
      }
    }
  }
}
