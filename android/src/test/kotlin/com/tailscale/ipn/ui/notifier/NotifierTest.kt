// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.notifier

import com.tailscale.ipn.App
import com.tailscale.ipn.UninitializedApp
import com.tailscale.ipn.ui.model.Ipn.Notify
import com.tailscale.ipn.ui.model.IpnState
import com.tailscale.ipn.ui.model.NodeID
import com.tailscale.ipn.ui.model.Tailcfg
import com.tailscale.ipn.util.TSLog
import com.tailscale.ipn.util.TSLog.LibtailscaleWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.mock

class NotifierTest {
  private lateinit var originalWrapper: LibtailscaleWrapper

  @Before
  fun setUp() {
    originalWrapper = TSLog.libtailscaleWrapper
    TSLog.libtailscaleWrapper = mock(LibtailscaleWrapper::class.java)
    // Notifier's initializer loads the inline-share inbox from encrypted prefs.
    UninitializedApp::class
        .java
        .getDeclaredField("appInstance")
        .apply { isAccessible = true }
        .set(null, mock(App::class.java, RETURNS_DEEP_STUBS))
  }

  @After
  fun tearDown() {
    TSLog.libtailscaleWrapper = originalWrapper
  }

  private fun peer(id: NodeID) =
      IpnState.PeerStatus(ID = "s$id", NodeID = id, DNSName = "peer$id.ts.net.", UserID = 1)

  private fun node(id: NodeID) =
      Tailcfg.Node(ID = id, StableID = "s$id", Name = "peer$id.ts.net.", User = 1)

  private fun seed(vararg peers: NodeID) {
    Notifier.updateNetworkMap(
        Notify(
            InitialStatus =
                IpnState.Status(
                    Self = peer(1),
                    Peer = peers.associate { "key$it" to peer(it) },
                )
        )
    )
  }

  private fun peerIDs() = Notifier.netmap.value?.Peers?.map { it.ID }

  // A full netmap from control arrives as SelfChange plus every current peer
  // in PeersChanged; peers it dropped arrive in PeersRemoved.
  @Test
  fun fullNetmapDropsRemovedPeer() {
    seed(10, 20)
    Notifier.updateNetworkMap(
        Notify(SelfChange = node(1), PeersChanged = listOf(node(10)), PeersRemoved = listOf(20))
    )
    assertEquals(listOf(10L), peerIDs())
  }

  @Test
  fun fullNetmapDropsLastPeer() {
    seed(10)
    Notifier.updateNetworkMap(Notify(SelfChange = node(1), PeersRemoved = listOf(10)))
    assertEquals(emptyList<NodeID>(), peerIDs())
  }
}
