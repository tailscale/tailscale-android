// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Network
import android.net.TrafficStats
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.system.OsConstants
import com.tailscale.ipn.mdm.MDMSettings
import com.tailscale.ipn.ui.localapi.Client
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.notifier.Notifier
import com.tailscale.ipn.util.TSLog
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import libtailscale.Libtailscale

open class IPNService : VpnService(), libtailscale.IPNService {
  private val TAG = "IPNService"
  private val randomID: String = UUID.randomUUID().toString()
  private lateinit var app: App
  val scope = CoroutineScope(Dispatchers.IO)
  private var closed = false
  private var watchdog: TunnelWatchdog? = null

  override fun id(): String {
    return randomID
  }

  override fun updateVpnStatus(status: Boolean) {
    app.getAppScopedViewModel().setVpnActive(status)
  }

  override fun onCreate() {
    super.onCreate()
    // grab app to make sure it initializes
    app = App.get()

    NetworkChangeCallback.setUnderlyingNetworkListener { network ->
      updateUnderlyingNetwork(network)
    }

    watchdog =
        TunnelWatchdog(
                scope = scope,
                isEnabled = { UninitializedApp.get().isTunnelWatchdogEnabled() },
                currentState = { Notifier.state.value },
                bytesTransferred = { appBytesTransferred() },
                probe = { probeTunnel() },
                act = { action -> performRecycleAction(action) },
                onGiveUp = { UninitializedApp.get().notifyWatchdogGaveUp() },
                currentDerpView = {
                  fetchStatus()?.let { s ->
                    DerpView(
                        homeRelay = s.Self?.Relay,
                        peerRelays =
                            s.Peer?.values.orEmpty()
                                .filter { it.Online }
                                .mapNotNull { it.Relay },
                    )
                  }
                },
                snapshot = { diagnosticSnapshot() },
                logSink = { message ->
                  WatchdogLog(File(filesDir, "watchdog.log")).append(message)
                },
            )
            .also { it.start() }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
      when (intent?.action) {
        ACTION_STOP_VPN -> {
          app.setWantRunning(false)
          close()
          START_NOT_STICKY
        }
        ACTION_RESTART_VPN -> {
          app.setWantRunning(false) {
            close()
            app.startVPN()
          }
          START_NOT_STICKY
        }
        ACTION_START_FOREGROUND_ONLY -> {
          // Start the foreground service notification without creating a VPN tunnel.
          // This is used during interactive login so that Android does not freeze the process
          // or restrict network access while the user completes auth in the browser.
          showForegroundNotification()
          START_NOT_STICKY
        }
        ACTION_START_VPN -> {
          showForegroundNotification()
          app.setWantRunning(true)
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        "android.net.VpnService" -> {
          // This means we were started by Android due to Always On VPN.
          // We show a non-foreground notification because we weren't
          // started as a foreground service.
          scope.launch {
            // Collect the first value of hideDisconnectAction asynchronously.
            val hideDisconnectAction = MDMSettings.forceEnabled.flow.first()
            val exitNodeName =
                UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
            app.notifyStatus(true, hideDisconnectAction.value, exitNodeName)
          }
          app.setWantRunning(true)
          Libtailscale.requestVPN(this)
          START_STICKY
        }
        else -> {
          // This means that we were restarted after the service was killed
          // (potentially due to OOM).
          if (UninitializedApp.get().isAbleToStartVPN()) {
            showForegroundNotification()
            App.get()
            Libtailscale.requestVPN(this)
            START_STICKY
          } else {
            START_NOT_STICKY
          }
        }
      }

  override fun close() {
    if (closed) return
    closed = true
    Notifier.setState(Ipn.State.Stopping)
    disconnectVPN()
    Libtailscale.serviceDisconnect(this)
  }

  override fun disconnectVPN() {
    stopSelf()
  }

  override fun onDestroy() {
    watchdog?.stop()
    watchdog = null
    NetworkChangeCallback.setUnderlyingNetworkListener(null)
    close()
    updateVpnStatus(false)
    super.onDestroy()
  }

  override fun onRevoke() {
    // VPN permission was granted to another app, so tell the Go backend and then set prepared to be
    // false so that when user attempts to connect again, VpnService.prepare() is called
    app.setWantRunning(false)
    setVpnPrepared(false)
    close()
    updateVpnStatus(false)
    super.onRevoke()
  }

  private fun setVpnPrepared(isPrepared: Boolean) {
    app.getAppScopedViewModel().setVpnPrepared(isPrepared)
  }

  private fun updateUnderlyingNetwork(network: Network?) {
    val networks = network?.let { arrayOf(it) } ?: emptyArray()
    if (!setUnderlyingNetworks(networks)) {
      TSLog.w(TAG, "Failed to set underlying network: $network")
    } else {
      TSLog.d(TAG, "Set underlying network: $network")
    }
  }

  private fun showForegroundNotification(
      hideDisconnectAction: Boolean,
      exitNodeName: String? = null,
  ) {
    try {
      startForeground(
          UninitializedApp.STATUS_NOTIFICATION_ID,
          UninitializedApp.get().buildStatusNotification(true, hideDisconnectAction, exitNodeName),
      )
    } catch (e: Exception) {
      TSLog.e(TAG, "Failed to start foreground service: $e")
    }
  }

  private fun showForegroundNotification() {
    val hideDisconnectAction = MDMSettings.forceEnabled.flow.value.value
    val exitNodeName = UninitializedApp.getExitNodeName(Notifier.prefs.value, Notifier.netmap.value)
    showForegroundNotification(hideDisconnectAction, exitNodeName)
  }

  private fun configIntent(): PendingIntent {
    return PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun allowApp(b: Builder, name: String) {
    try {
      b.addAllowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add allowed application: $e")
    }
  }

  private fun disallowApp(b: Builder, name: String) {
    try {
      b.addDisallowedApplication(name)
    } catch (e: PackageManager.NameNotFoundException) {
      TSLog.e(TAG, "Failed to add disallowed application: $e")
    }
  }

  override fun newBuilder(): VPNServiceBuilder {
    val b: Builder =
        Builder()
            .setConfigureIntent(configIntent())
            .allowFamily(OsConstants.AF_INET)
            .allowFamily(OsConstants.AF_INET6)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      b.setMetered(false) // Inherit the metered status from the underlying networks.
    }

    val underlyingNetwork = NetworkChangeCallback.cachedDefaultNetwork
    b.setUnderlyingNetworks(underlyingNetwork?.let { arrayOf(it) } ?: emptyArray())

    val mdmAllowed =
        MDMSettings.includedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()
    val mdmDisallowed =
        MDMSettings.excludedPackages.flow.value.value?.split(",")?.map { it.trim() } ?: emptyList()

    var packagesList: List<String>
    var allowPackages: Boolean
    if (mdmAllowed.isNotEmpty()) {
      // An admin defined a list of packages that are exclusively allowed to be used via
      // Tailscale, so only allow those.
      packagesList = mdmAllowed
      allowPackages = true
      TSLog.d(TAG, "Included application packages were set via MDM: $mdmAllowed")
    } else if (mdmDisallowed.isNotEmpty()) {
      // An admin defined a list of packages that are excluded from accessing Tailscale,
      // so ignore user definitions and only exclude those
      packagesList = mdmDisallowed
      allowPackages = false
      TSLog.d(TAG, "Excluded application packages were set via MDM: $mdmDisallowed")
    } else {
      // Otherwise, prevent user manually disallowed apps from getting their traffic + DNS routed
      // via Tailscale
      packagesList = UninitializedApp.get().selectedPackageNames()
      allowPackages = UninitializedApp.get().allowSelectedPackages()
      TSLog.d(TAG, "Application packages were set by user: $packagesList")
    }

    packagesList =
        packagesForVpnBuilder(
            packagesList = packagesList,
            allowPackages = allowPackages,
            tailscalePackageName = UninitializedApp.get().packageName,
            builtInDisallowedPackages = UninitializedApp.get().builtInDisallowedPackageNames,
        )

    if (allowPackages) {
      for (packageName in packagesList) {
        TSLog.d(TAG, "Including app: $packageName")
        allowApp(b, packageName)
      }
    } else {
      for (packageName in packagesList) {
        TSLog.d(TAG, "Disallowing app: $packageName")
        disallowApp(b, packageName)
      }
    }

    return VPNServiceBuilder(b)
  }

  /**
   * Total bytes this app's UID has moved, used by the watchdog's traffic gate. Returns 0 when the
   * platform does not report per-UID stats, which makes the gate treat the app as silent and fall
   * through to the probe — the probe is the authoritative signal either way.
   */
  private fun appBytesTransferred(): Long {
    val uid = Process.myUid()
    val rx = TrafficStats.getUidRxBytes(uid)
    val tx = TrafficStats.getUidTxBytes(uid)
    if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
      return 0L
    }
    return rx + tx
  }

  /**
   * Probes a tailnet peer (through the tunnel) and the control plane (from the app's own sockets).
   * The peer probe is the tunnel-liveness signal; the control-plane probe tells us whether the app
   * has any egress at all, which separates "tunnel dead" from "app blackholed".
   */
  private suspend fun probeTunnel(): ProbeOutcome {
    val peers =
        Notifier.netmap.value?.Peers.orEmpty()
            .filter { !it.primaryIPv4Address.isNullOrEmpty() }
            .take(MAX_PROBE_PEERS)
    var peersOk = 0
    for (peer in peers) {
      if (pingPeer(peer)) peersOk++
    }
    val controlOk = probeControlPlane()
    return ProbeOutcome(peersProbed = peers.size, peersOk = peersOk, controlOk = controlOk)
  }

  private suspend fun pingPeer(peer: Tailcfg.Node): Boolean =
      withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
              Client(scope).ping(peer) { result ->
                val ok = result.getOrNull()?.Err?.isEmpty() == true
                if (cont.isActive) cont.resume(ok)
              }
            }
          } ?: false

  /**
   * Everything the app can see about the tunnel, captured at the moment it
   * stopped passing traffic. This is the evidence we have never had: the state
   * at failure, not a reconstruction afterwards.
   */
  private suspend fun diagnosticSnapshot(): String {
    val status = fetchStatus()
    val peers = status?.Peer?.values.orEmpty()
    val online = peers.count { it.Online }
    val direct = peers.count { it.Online && !it.CurAddr.isNullOrEmpty() }
    val relays = peers.filter { it.Online }.mapNotNull { it.Relay }.groupingBy { it }.eachCount()
    val health = Notifier.health.value?.Warnings?.keys?.sorted() ?: emptyList()
    val prefs = Notifier.prefs.value
    return buildString {
      appendLine("ipn.State=${Notifier.state.value}")
      appendLine("homeRelay=${status?.Self?.Relay ?: "?"}")
      appendLine("peers online=$online direct=$direct relays=$relays")
      appendLine("health=$health")
      appendLine("wantRunning=${prefs?.WantRunning} exitNode=${prefs?.ExitNodeID}")
      appendLine(
          "underlyingNet=${NetworkChangeCallback.cachedDefaultNetwork} " +
              "iface=${NetworkChangeCallback.cachedDefaultInterfaceName ?: "none"}"
      )
      appendLine("uidBytes=${appBytesTransferred()}")
    }
  }

  /** Fetches the engine status once, for the home-relay check. */
  private suspend fun fetchStatus(): IpnState.Status? =
      withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
            suspendCancellableCoroutine { cont ->
              Client(scope).status { result ->
                if (cont.isActive) cont.resume(result.getOrNull())
              }
            }
          }

  private suspend fun probeControlPlane(): Boolean =
      withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) {
              try {
                val address = InetAddress.getByName(CONTROL_PLANE_HOST)
                Socket().use { socket ->
                  socket.connect(
                      InetSocketAddress(address, CONTROL_PLANE_PORT),
                      PROBE_TIMEOUT_MILLIS.toInt(),
                  )
                }
                true
              } catch (e: Exception) {
                false
              }
            }
          } ?: false

  private suspend fun performRecycleAction(action: RecycleAction) {
    when (action) {
      RecycleAction.RESTART_VPN -> app.restartVPN()
      else -> Client(scope).debugAction(action.wireName)
    }
  }

  companion object {
    const val ACTION_START_VPN = "com.tailscale.ipn.START_VPN"
    const val ACTION_STOP_VPN = "com.tailscale.ipn.STOP_VPN"
    const val ACTION_RESTART_VPN = "com.tailscale.ipn.RESTART_VPN"
    const val ACTION_START_FOREGROUND_ONLY = "com.tailscale.ipn.START_FOREGROUND_ONLY"

    // Two peers is the smallest number that lets one sleeping peer be outvoted by another.
    private const val MAX_PROBE_PEERS = 2

    // Matches the 2 s timeout the existing disco ping uses in Client.ping.
    private const val PROBE_TIMEOUT_MILLIS = 2000L

    private const val CONTROL_PLANE_HOST = "controlplane.tailscale.com"
    private const val CONTROL_PLANE_PORT = 443
  }
}

internal fun packagesForVpnBuilder(
    packagesList: List<String>,
    allowPackages: Boolean,
    tailscalePackageName: String,
    builtInDisallowedPackages: List<String>,
): List<String> =
    if (allowPackages) {
      if (packagesList.isEmpty()) {
        emptyList()
      } else {
        (packagesList + tailscalePackageName).distinct()
      }
    } else {
      (packagesList + builtInDisallowedPackages).distinct()
    }
