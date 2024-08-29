// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import android.net.Uri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.UUID

class Ipn {

  // Represents the overall state of the Tailscale engine.
  enum class State(val value: Int) {
    NoState(0),
    InUseOtherUser(1),
    NeedsLogin(2),
    NeedsMachineAuth(3),
    Stopped(4),
    Starting(5),
    Running(6),
    // Stopping represents a state where a request to stop Tailscale has been issue but has not
    // completed. This state allows UI to optimistically reflect a stopped state, and to fallback if
    // necessary.
    Stopping(7);

    companion object {
      fun fromInt(value: Int): State {
        return State.values().firstOrNull { it.value == value } ?: NoState
      }
    }
  }

  // A nofitication message recieved on the Notify bus.  Fields will be populated based
  // on which NotifyWatchOpts were set when the Notifier was created.
  @Serializable
  data class Notify(
      val Version: String? = null,
      val ErrMessage: String? = null,
      val LoginFinished: Empty.Message? = null,
      val FilesWaiting: Empty.Message? = null,
      val OutgoingFiles: List<OutgoingFile>? = null,
      val State: Int? = null,
      var Prefs: Prefs? = null,
      var NetMap: Netmap.NetworkMap? = null,
      var Engine: EngineStatus? = null,
      var BrowseToURL: String? = null,
      var BackendLogId: String? = null,
      var LocalTCPPort: Int? = null,
      var IncomingFiles: List<PartialFile>? = null,
      var ClientVersion: Tailcfg.ClientVersion? = null,
      var TailFSShares: List<String>? = null,
      var Health: Health.State? = null,
  )

  @Serializable
  data class Prefs(
      var ControlURL: String = "",
      var RouteAll: Boolean = false,
      var AllowsSingleHosts: Boolean = false,
      var CorpDNS: Boolean = false,
      var WantRunning: Boolean = false,
      var LoggedOut: Boolean = false,
      var ShieldsUp: Boolean = false,
      var AdvertiseRoutes: List<String>? = null,
      var AdvertiseTags: List<String>? = null,
      var ExitNodeID: StableNodeID? = null,
      var ExitNodeAllowLANAccess: Boolean = false,
      var Config: Persist.Persist? = null,
      var ForceDaemon: Boolean = false,
      var HostName: String = "",
      var AutoUpdate: AutoUpdatePrefs? = AutoUpdatePrefs(true, true),
      var InternalExitNodePrior: String? = null,
  ) {

    // For the InternalExitNodePrior and ExitNodeId, these will treats the empty string as null to
    // simplify the downstream logic.

    val selectedExitNodeID: String?
      get() {
        return if (InternalExitNodePrior.isNullOrEmpty()) null else InternalExitNodePrior
      }

    val activeExitNodeID: String?
      get() {
        return if (ExitNodeID.isNullOrEmpty()) null else ExitNodeID
      }
  }

  @Serializable
  data class MaskedPrefs(
      var ControlURLSet: Boolean? = null,
      var RouteAllSet: Boolean? = null,
      var CorpDNSSet: Boolean? = null,
      var ExitNodeIDSet: Boolean? = null,
      var ExitNodeAllowLANAccessSet: Boolean? = null,
      var WantRunningSet: Boolean? = null,
      var ShieldsUpSet: Boolean? = null,
      var AdvertiseRoutesSet: Boolean? = null,
      var ForceDaemonSet: Boolean? = null,
      var HostnameSet: Boolean? = null,
      var InternalExitNodePriorSet: Boolean? = null,
  ) {

    var ControlURL: String? = null
      set(value) {
        field = value
        ControlURLSet = true
      }

    var RouteAll: Boolean? = null
      set(value) {
        field = value
        RouteAllSet = true
      }

    var CorpDNS: Boolean? = null
      set(value) {
        field = value
        CorpDNSSet = true
      }

    var ExitNodeID: StableNodeID? = null
      set(value) {
        field = value
        ExitNodeIDSet = true
      }

    var InternalExitNodePrior: String? = null
      set(value) {
        field = value
        InternalExitNodePriorSet = true
      }

    var ExitNodeAllowLANAccess: Boolean? = null
      set(value) {
        field = value
        ExitNodeAllowLANAccessSet = true
      }

    var WantRunning: Boolean? = null
      set(value) {
        field = value
        WantRunningSet = true
      }

    var ShieldsUp: Boolean? = null
      set(value) {
        field = value
        ShieldsUpSet = true
      }

    var AdvertiseRoutes: List<String>? = null
      set(value) {
        field = value
        AdvertiseRoutesSet = true
      }

    var ForceDaemon: Boolean? = null
      set(value) {
        field = value
        ForceDaemonSet = true
      }

    var Hostname: String? = null
      set(value) {
        field = value
        HostnameSet = true
      }
  }

  @Serializable
  data class AutoUpdatePrefs(
      var Check: Boolean? = null,
      var Apply: Boolean? = null,
  )

  @Serializable
  data class EngineStatus(
      val RBytes: Long,
      val WBytes: Long,
      val NumLive: Int,
      val LivePeers: Map<String, IpnState.PeerStatusLite>,
  )

  @Serializable
  data class PartialFile(
      val Name: String,
      val Started: String,
      val DeclaredSize: Long,
      val Received: Long,
      val PartialPath: String? = null,
      var FinalPath: String? = null,
      val Done: Boolean? = null,
  )

  @Serializable
  data class OutgoingFile(
      val ID: String = "",
      val Name: String,
      val PeerID: StableNodeID = "",
      val Started: String = "",
      val DeclaredSize: Long,
      val Sent: Long = 0L,
      val PartialPath: String? = null,
      var FinalPath: String? = null,
      val Finished: Boolean = false,
      val Succeeded: Boolean = false,
  ) {
    @Transient lateinit var uri: Uri // only used on client

    fun prepare(peerId: StableNodeID): OutgoingFile {
      val f = copy(ID = UUID.randomUUID().toString(), PeerID = peerId)
      f.uri = uri
      return f
    }
  }

  @Serializable data class FileTarget(var Node: Tailcfg.Node, var PeerAPIURL: String)

  @Serializable
  data class Options(
      var FrontendLogID: String? = null,
      var UpdatePrefs: Prefs? = null,
      var AuthKey: String? = null,
  )
}

class Persist {
  @Serializable
  data class Persist(
      var PrivateMachineKey: String =
          "privkey:0000000000000000000000000000000000000000000000000000000000000000",
      var PrivateNodeKey: String =
          "privkey:0000000000000000000000000000000000000000000000000000000000000000",
      var OldPrivateNodeKey: String =
          "privkey:0000000000000000000000000000000000000000000000000000000000000000",
      var Provider: String = "",
  )
}
