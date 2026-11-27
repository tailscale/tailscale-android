// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/net/tsaddr"
)

// internetAddr is a TEST-NET-3 address standing in for the internet past the exit node.
var internetAddr = netip.MustParseAddr("203.0.113.1")

const lanBody = "hello-from-the-lan"

func exitNodeCanBeEnabled(t *testing.T, e *androidEnv) {
	e.makePeerExitNode(t)
	showMainScreen(t, e)
	waitForConnected(t, e)
	useExitNode(t, e)
	e.waitForExitNodeTraffic(t)
}

func exitNodeLANAccess(t *testing.T, e *androidEnv) {
	lanURL, hits := startLANServer(t)
	// Check the LAN works without the exit node, so a broken LAN can't pass as blocked below.
	stopUsingExitNode(t, e)
	e.waitForProbeBody(t, lanURL, lanBody)
	useExitNode(t, e)
	e.waitForExitNodeTraffic(t)
	before := hits.Load()
	// An exit node never routes private ranges, so with LAN access off the request goes nowhere.
	if got := e.probe(t, lanURL); !strings.Contains(got, "exit=1\nGET "+lanURL+": ") || hits.Load() != before {
		t.Fatalf("want the LAN request to fail with Allow LAN access off, got %d LAN requests and probe result %q", hits.Load()-before, got)
	}
	toggleLANAccess(t, e, true)
	e.waitForProbeBody(t, lanURL, lanBody)
	toggleLANAccess(t, e, false)
	stopUsingExitNode(t, e)
}

func (e *androidEnv) waitForExitNodeTraffic(t *testing.T) {
	t.Helper()
	e.waitForProbeBody(t, "http://"+internetAddr.String()+"/", "exit node got "+internetAddr.String()+":80")
}

// makePeerExitNode answers for internetAddr itself, since tsnet doesn't forward.
func (e *androidEnv) makePeerExitNode(t *testing.T) {
	t.Helper()
	e.peer.RegisterFallbackTCPHandler(func(src, dst netip.AddrPort) (func(net.Conn), bool) {
		if dst.Addr() != internetAddr {
			return nil, true // reject with a RST
		}
		return func(c net.Conn) { replyHTTP(c, fmt.Sprintf("exit node got %v", dst)) }, true
	})

	lc := e.peerClient(t)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if _, err := lc.EditPrefs(ctx, &ipn.MaskedPrefs{
		Prefs:              ipn.Prefs{AdvertiseRoutes: tsaddr.ExitRoutes()},
		AdvertiseRoutesSet: true,
	}); err != nil {
		t.Fatalf("tsnet peer EditPrefs AdvertiseRoutes: %v", err)
	}
	st, err := lc.Status(ctx)
	if err != nil {
		t.Fatalf("tsnet peer Status: %v", err)
	}
	e.control.SetSubnetRoutes(st.Self.PublicKey, tsaddr.ExitRoutes())
}

// startLANServer serves lanBody on the test host, which the emulator reaches as a LAN host.
func startLANServer(t *testing.T) (lanURL string, hits *atomic.Int32) {
	t.Helper()
	hits = new(atomic.Int32)
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		fmt.Fprint(w, lanBody)
	}))
	t.Cleanup(srv.Close)
	return rewriteHost(t, srv.URL, *emulatorHost) + "/", hits
}

func openExitNodePicker(t *testing.T, e *androidEnv) {
	t.Helper()
	showMainScreen(t, e)
	tap(t, e.adb, waitForAppNode(t, e, "the Exit Node row", withText(appString(t, strExitNode))))
	title := appString(t, strChooseExitNode)
	waitForAppNode(t, e, title, withText(title))
}

func useExitNode(t *testing.T, e *androidEnv) {
	t.Helper()
	openExitNodePicker(t, e)
	tap(t, e.adb, waitForAppNode(t, e, peerHostname+" in the exit node list", withText(peerHostname)))
	waitForExitNodeRow(t, e, peerHostname, appString(t, strDisable))
}

func stopUsingExitNode(t *testing.T, e *androidEnv) {
	t.Helper()
	openExitNodePicker(t, e)
	none := appString(t, strNone)
	tap(t, e.adb, waitForAppNode(t, e, "None in the exit node list", withText(none)))
	waitForExitNodeRow(t, e, none)
}

func waitForExitNodeRow(t *testing.T, e *androidEnv, texts ...string) {
	t.Helper()
	label := appString(t, strExitNode)
	pollUI(t, e.adb, fmt.Sprintf("the Exit Node row showing %q", texts), func(s uiScreen) bool {
		if s.pkg() != androidPackage {
			dismissForeignApp(t, e, s.pkg())
			return false
		}
		row, found := s.row(withText(label))
		for _, text := range texts {
			found = found && row.holds(withText(text))
		}
		return found
	})
}

func toggleLANAccess(t *testing.T, e *androidEnv, on bool) {
	t.Helper()
	openExitNodePicker(t, e)
	waitForAppNode(t, e, "the Allow LAN access row", withText(appString(t, strAllowLANAccess)))
	sw := waitForAppNode(t, e, "the Allow LAN access switch", func(n uiNode) bool { return n.Checkable })
	if sw.Checked == on {
		t.Fatalf("the Allow LAN access switch is already checked=%v", on)
	}
	tap(t, e.adb, sw)
	waitForAppNode(t, e, fmt.Sprintf("the Allow LAN access switch checked=%v", on), func(n uiNode) bool { return n.Checkable && n.Checked == on })
}
