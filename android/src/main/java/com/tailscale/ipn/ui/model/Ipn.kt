// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.model

import kotlinx.serialization.Serializable

class Ipn {

        // Represents the overall state of the Tailscale engine.
        enum class State(val value: Int) {
                NoState(0),
                InUseOtherUser(1),
                NeedsLogin(2),
                NeedsMachineAuth(3),
                Stopped(4),
                Starting(5),
                Running(6);

                companion object {
                        fun fromInt(value: Int): State? {
                                return State.values().first { s -> s.value == value }
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
                        val State: Int? = null,
                        var Prefs: Prefs? = null,
                        var NetMap: Netmap.NetworkMap? = null,
                        var Engine: EngineStatus? = null,
                        var BrowseToURL: String? = null,
                        var BackendLogId: String? = null,
                        var LocalTCPPort: Int? = null,
                        var IncomingFiles: List<PartialFile>? = null,
                        var ClientVersion: Tailcfg.ClientVersion? = null,
                        var TailFSShares: Map<String, String>? = null,
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
                        var ExitNodeId: StableNodeID? = null,
                        var ExitNodeAllowLanAccess: Boolean = false,
                        var Config: Persist.Persist? = null,
                        var ForceDaemon: Boolean = false,
                        var HostName: String = "",
                        var AutoUpdate: AutoUpdatePrefs? = AutoUpdatePrefs(true, true),
        )

        @Serializable
        data class MaskedPrefs(
                        var RouteAllSet: Boolean? = null,
                        var CorpDNSSet: Boolean? = null,
                        var ExitNodeIDSet: Boolean? = null,
                        var ExitNodeAllowLANAccessSet: Boolean? = null,
                        var WantRunningSet: Boolean? = null,
                        var ShieldsUpSet: Boolean? = null,
                        var AdvertiseRoutesSet: Boolean? = null,
                        var ForceDaemonSet: Boolean? = null,
                        var HostnameSet: Boolean? = null,
        ) {
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
                var ExitNodeId: StableNodeID? = null
                        set(value) {
                                field = value
                                ExitNodeIDSet = true
                        }
                var ExitNodeAllowLanAccess: Boolean? = null
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
                var AdvertiseRoutes: Boolean? = null
                        set(value) {
                                field = value
                                AdvertiseRoutesSet = true
                        }
                var ForceDaemon: Boolean? = null
                        set(value) {
                                field = value
                                ForceDaemonSet = true
                        }
                var Hostname: Boolean? = null
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
