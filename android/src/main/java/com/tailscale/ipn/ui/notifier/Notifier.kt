// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.App
import com.tailscale.ipn.BuildConfig
import com.tailscale.ipn.ui.model.Empty
import com.tailscale.ipn.ui.model.Health
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.model.Ipn.Notify
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.Netmap
import com.tailscale.ipn.ui.model.NodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.ui.util.set
import com.tailscale.ipn.util.TSLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

// When set to true, the Notifier will inject fake health warnings for testing purposes
val INJECT_FAKE_HEALTH_WARNINGS = false

// Notifier is a wrapper around the IPN Bus notifier.  It provides a way to watch
// for changes in various parts of the Tailscale engine.  You will typically only use
// a single Notifier per instance of your application which lasts for the lifetime of
// the process.
//
// The primary entry point here is watchIPNBus which will start a watcher on the IPN bus
// and return you the session Id.  When you are done with your watcher, you must call
// unwatchIPNBus with the sessionId.
object Notifier {
  private val TAG = Notifier::class.simpleName
  private val decoder = Json { ignoreUnknownKeys = true }

  // General IPN Bus State
  private val _state = MutableStateFlow(Ipn.State.NoState)
  val state: StateFlow<Ipn.State> = _state
  private val _netmap = MutableStateFlow<Netmap.NetworkMap?>(null)
  val netmap: StateFlow<Netmap.NetworkMap?> = _netmap
  val prefs: StateFlow<Ipn.Prefs?> = MutableStateFlow(null)
  val engineStatus: StateFlow<Ipn.EngineStatus?> = MutableStateFlow(null)
  val tailFSShares: StateFlow<Map<String, String>?> = MutableStateFlow(null)
  val browseToURL: StateFlow<String?> = MutableStateFlow(null)
  val loginFinished: StateFlow<String?> = MutableStateFlow(null)
  val version: StateFlow<String?> = MutableStateFlow(null)
  val health: StateFlow<Health.State?> = MutableStateFlow(null)

  // Taildrop-specific State
  val outgoingFiles: StateFlow<List<Ipn.OutgoingFile>?> = MutableStateFlow(null)
  val incomingFiles: StateFlow<List<Ipn.PartialFile>?> = MutableStateFlow(null)
  val filesWaiting: StateFlow<Empty.Message?> = MutableStateFlow(null)

  private val userProfiles = mutableMapOf<String, Tailcfg.UserProfile>()

  private lateinit var app: libtailscale.Application
  private var manager: libtailscale.NotificationManager? = null

  @Synchronized
  @JvmStatic
  fun setApp(newApp: libtailscale.Application) {
    app = newApp
  }

  @Synchronized
  @OptIn(ExperimentalSerializationApi::class)
  fun start(scope: CoroutineScope) {
    TSLog.d(TAG, "Starting Notifier")
    if (!::app.isInitialized) {
      App.get()
    }
    scope.launch(Dispatchers.IO) {
      val mask =
          NotifyWatchOpt.Prefs.value or
              NotifyWatchOpt.InitialState.value or
              NotifyWatchOpt.PeerChanges.value or
              NotifyWatchOpt.NoNetmap.value or
              NotifyWatchOpt.InitialStatus.value or
              NotifyWatchOpt.InitialHealthState.value
      manager =
          app.watchNotifications(mask.toLong()) { notification ->
            runCatching {
                  val notify = decoder.decodeFromStream<Notify>(notification.inputStream())
                  notify.State?.let { state.set(Ipn.State.fromInt(it)) }
                  if (BuildConfig.DEBUG) {
                    notify.InitialStatus?.let {
                      TSLog.d(
                          TAG,
                          "received initial status: peers=${it.Peer?.size ?: 0}, users=${it.User?.size ?: 0}")
                    }
                    val peersChanged = notify.PeersChanged?.size ?: 0
                    val peersRemoved = notify.PeersRemoved?.size ?: 0
                    val users = notify.UserProfiles?.size ?: 0
                    if (notify.SelfChange != null ||
                        peersChanged > 0 ||
                        peersRemoved > 0 ||
                        users > 0) {
                      TSLog.d(
                          TAG,
                          "received bus update: self=${notify.SelfChange != null}, peersChanged=$peersChanged, peersRemoved=$peersRemoved, users=$users")
                    }
                  }
                  updateNetworkMap(notify)
                  notify.Prefs?.let(prefs::set)
                  notify.Engine?.let(engineStatus::set)
                  notify.TailFSShares?.let(tailFSShares::set)
                  notify.BrowseToURL?.let(browseToURL::set)
                  notify.LoginFinished?.let { loginFinished.set(it.property) }
                  notify.Version?.let(version::set)
                  notify.OutgoingFiles?.let(outgoingFiles::set)
                  notify.FilesWaiting?.let(filesWaiting::set)
                  notify.IncomingFiles?.let(incomingFiles::set)
                  notify.Health?.let {
                    if (INJECT_FAKE_HEALTH_WARNINGS) {
                      injectFakeHealthState()
                    } else {
                      health.set(it)
                    }
                  }
                }
                .onFailure { TSLog.e(TAG, "failed to process IPN notification", it) }
          }
    }
  }

  fun stop() {
    TSLog.d(TAG, "Stopping Notifier")
    manager?.let {
      it.stop()
      manager = null
    }
  }

  // NotifyWatchOpt is a bitmask of options supplied to the notifier to specify which
  // what we want to see on the Notify bus
  private enum class NotifyWatchOpt(val value: Int) {
    EngineUpdates(1),
    InitialState(2),
    Prefs(4),
    NoPrivateKey(16),
    InitialTailFSShares(32),
    InitialOutgoingFiles(64),
    InitialHealthState(128),
    PeerChanges(4096),
    NoNetmap(8192),
    InitialStatus(16384),
  }

  fun setState(newState: Ipn.State) {
    _state.value = newState
  }

  @Synchronized
  private fun updateNetworkMap(notify: Notify) {
    val initial = notify.InitialStatus
    if (initial != null) {
      userProfiles.clear()
      initial.User?.let { userProfiles.putAll(it) }
    }

    val cur = _netmap.value
    var next = initial?.toNetworkMapOrNull(userProfiles.toMap()) ?: cur

    if (initial != null && next == null) {
      _netmap.set(null)
      if (BuildConfig.DEBUG) {
        TSLog.d(TAG, "received empty initial status; cleared network map")
      }
      return
    }

    if (next == null) {
      return
    }

    val self = notify.SelfChange
    if (initial == null && self != null && !next.isSameSelf(self)) {
      next = self.toEmptyNetworkMap(userProfiles.toMap())
      if (BuildConfig.DEBUG) {
        TSLog.d(
            TAG, "received self change for a different node; cleared peers before applying deltas")
      }
    }

    notify.UserProfiles?.let { userProfiles.putAll(it) }

    if (userProfiles.isNotEmpty() || next.UserProfiles.isNotEmpty()) {
      next = next.copy(UserProfiles = userProfiles.toMap())
    }
    if (self != null) {
      next =
          next.copy(
              SelfNode = self.withDisplayNames(next.magicDNSSuffix()),
              AllCaps = self.capabilities())
    }
    val removed = notify.PeersRemoved
    if (!removed.isNullOrEmpty()) {
      val removedIDs = removed.toSet()
      next = next.copy(Peers = next.Peers?.filterNot { removedIDs.contains(it.ID) })
    }
    val changed = notify.PeersChanged
    if (!changed.isNullOrEmpty()) {
      val suffix = next.magicDNSSuffix()
      next = next.copy(Peers = mergePeers(next.Peers, changed.map { it.withDisplayNames(suffix) }))
    }
    if (next != cur) {
      _netmap.set(next)
    }
    if (initial != null) {
      if (BuildConfig.DEBUG) {
        TSLog.d(
            TAG,
            "updated network map from initial status: peers=${next.Peers?.size ?: 0}, users=${next.UserProfiles.size}, selfUser=${next.SelfNode.User}")
      }
    }
  }

  private fun mergePeers(
      peers: List<Tailcfg.Node>?,
      changed: List<Tailcfg.Node>
  ): List<Tailcfg.Node> {
    val byID = linkedMapOf<NodeID, Tailcfg.Node>()
    peers.orEmpty().forEach { byID[it.ID] = it }
    changed.forEach { byID[it.ID] = it }
    return byID.values.toList()
  }

  private fun IpnState.Status.toNetworkMapOrNull(
      profiles: Map<String, Tailcfg.UserProfile>
  ): Netmap.NetworkMap? {
    val self = Self?.toNode(this) ?: return null
    return Netmap.NetworkMap(
        SelfNode = self,
        Peers = Peer?.values?.map { it.toNode(this) }.orEmpty(),
        Domain = CurrentTailnet?.Name.orEmpty(),
        UserProfiles = profiles,
        TKAEnabled = false,
        DNS = null,
        AllCaps = self.capabilities())
  }

  private fun Tailcfg.Node.capabilities() = CapMap?.keys?.toList() ?: Capabilities.orEmpty()

  private fun Tailcfg.Node.toEmptyNetworkMap(
      profiles: Map<String, Tailcfg.UserProfile>
  ): Netmap.NetworkMap {
    val suffix = magicDNSSuffix()
    val self = withDisplayNames(suffix)
    return Netmap.NetworkMap(
        SelfNode = self,
        Peers = emptyList(),
        Domain = suffix,
        UserProfiles = profiles,
        TKAEnabled = false,
        DNS = null,
        AllCaps = self.capabilities())
  }

  private fun IpnState.PeerStatus.toNode(status: IpnState.Status): Tailcfg.Node {
    val computedName = displayName(status)
    val computedNameWithHost =
        if (HostName.isNotEmpty() && !computedName.equals(HostName, ignoreCase = true)) {
          "$computedName ($HostName)"
        } else {
          computedName
        }
    val allowedIPs =
        AllowedIPs
            ?: buildList {
              TailscaleIPs?.let { addAll(it) }
              PrimaryRoutes?.let { addAll(it) }
              if (ExitNodeOption) {
                add("0.0.0.0/0")
                add("::/0")
              }
            }
    return Tailcfg.Node(
        ID = NodeID,
        StableID = ID,
        Name = DNSName.ifEmpty { HostName },
        User = UserID,
        Sharer = AltSharerUserID.takeIf { it != 0L },
        Key = PublicKey,
        KeyExpiry = KeyExpiry,
        Addresses = TailscaleIPs,
        AllowedIPs = allowedIPs,
        Hostinfo =
            Tailcfg.Hostinfo(
                OS = OS, Hostname = HostName, ShareeNode = ShareeNode, Location = Location),
        LastSeen = LastSeen,
        Online = Online,
        Capabilities = Capabilities,
        CapMap = CapMap,
        ComputedName = computedName,
        ComputedNameWithHost = computedNameWithHost)
  }

  private fun IpnState.PeerStatus.displayName(status: IpnState.Status): String {
    val computed = computedName(status)
    return computed.ifEmpty { HostName.ifEmpty { PublicKey } }
  }

  // isSameSelf reports whether self can be applied as a mutation to this
  // synthetic NetworkMap. This is intentionally a boundary check, not a full
  // node equality check: if any stable self identity field differs, a WatchIPNBus
  // delta stream has crossed to a different profile/tailnet and the peer set must
  // be rebuilt from that stream instead of merged with stale peers.
  private fun Netmap.NetworkMap.isSameSelf(self: Tailcfg.Node): Boolean {
    if (SelfNode.ID != 0L && self.ID != 0L && SelfNode.ID != self.ID) {
      return false
    }
    if (SelfNode.StableID.isNotEmpty() &&
        self.StableID.isNotEmpty() &&
        SelfNode.StableID != self.StableID) {
      return false
    }
    if (SelfNode.User != 0L && self.User != 0L && SelfNode.User != self.User) {
      return false
    }
    return true
  }

  private fun Netmap.NetworkMap.magicDNSSuffix(): String = SelfNode.magicDNSSuffix()

  private fun Tailcfg.Node.magicDNSSuffix(): String {
    val name = Name.trim('.')
    val suffix = name.substringAfter('.', missingDelimiterValue = name)
    return suffix.trim('.')
  }

  private fun Tailcfg.Node.withDisplayNames(magicDNSSuffix: String): Tailcfg.Node {
    if (!ComputedName.isNullOrEmpty() && !ComputedNameWithHost.isNullOrEmpty()) {
      return this
    }
    val name = Name.trim('.').removeSuffix(".$magicDNSSuffix")
    var hostIfDifferent = Hostinfo.Hostname.orEmpty()
    if (name.equals(hostIfDifferent, ignoreCase = true)) {
      hostIfDifferent = ""
    }
    val computedName =
        when {
          name.isNotEmpty() -> name
          hostIfDifferent.isNotEmpty() -> {
            val host = hostIfDifferent
            hostIfDifferent = ""
            host
          }
          else -> Key
        }
    val computedNameWithHost =
        if (hostIfDifferent.isNotEmpty()) "$computedName ($hostIfDifferent)" else computedName
    return copy(ComputedName = computedName, ComputedNameWithHost = computedNameWithHost)
  }
}
