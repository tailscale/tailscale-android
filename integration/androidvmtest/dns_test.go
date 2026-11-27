// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/dns/dnsmessage"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
	"tailscale.com/types/dnstype"
	"tailscale.com/types/logger"
)

const (
	magicDNSDomain  = "test.ts.net"
	splitDNSDomain  = "forwarded.example"
	globalDNSDomain = "global.example"
	dnsPeerHostname = "tsnetdnspeer"
	dnsPeerBody     = "hello-from-the-dns-peer"
)

func magicDNSCanAddMultipleDevices(t *testing.T, e *androidEnv) {
	showMainScreen(t, e)
	waitForAppNode(t, e, dnsPeerHostname+" in the device list", withText(dnsPeerHostname))
	e.control.SetDNSConfig(magicDNSConfig())
	e.waitForProbeBody(t, "http://"+peerHostname+"."+magicDNSDomain+"/", wantBody)
	e.waitForProbeBody(t, "http://"+dnsPeerHostname+"."+magicDNSDomain+"/", dnsPeerBody)
	waitForDNSSettings(t, e, "the MagicDNS search domain", withText(magicDNSDomain))
}

func magicDNSDeviceReachableViaHostname(t *testing.T, e *androidEnv) {
	e.waitForProbeBody(t, "http://"+dnsPeerHostname+"/", dnsPeerBody)
}

func splitDNSCanBeConfigured(t *testing.T, e *androidEnv, dnsIP netip.Addr) {
	cfg := magicDNSConfig()
	cfg.Routes = map[string][]*dnstype.Resolver{splitDNSDomain: {{Addr: dnsIP.String()}}}
	e.control.SetDNSConfig(cfg)
	e.waitForNewNameBody(t, splitDNSDomain, dnsPeerBody)
	route := func(n uiNode) bool { return strings.HasSuffix(n.Text, splitDNSDomain) }
	waitForDNSSettings(t, e, "the split DNS route", route, withText(dnsIP.String()))
}

func overrideGlobalDNS(t *testing.T, e *androidEnv, dnsIP netip.Addr) {
	cfg := magicDNSConfig()
	cfg.Resolvers = []*dnstype.Resolver{{Addr: dnsIP.String()}}
	e.control.SetDNSConfig(cfg)
	e.waitForNewNameBody(t, globalDNSDomain, dnsPeerBody)
	resolvers := withText(appString(t, strResolvers))
	waitForDNSSettings(t, e, "the global nameserver", resolvers, withText(dnsIP.String()))
}

func magicDNSConfig() *tailcfg.DNSConfig {
	return &tailcfg.DNSConfig{Proxied: true, Domains: []string{magicDNSDomain}}
}

func (e *androidEnv) startDNSPeer(t *testing.T) netip.Addr {
	t.Helper()
	s := &tsnet.Server{
		Dir:        t.TempDir(),
		ControlURL: e.peer.ControlURL,
		AuthKey:    authKey,
		Hostname:   dnsPeerHostname,
		Ephemeral:  true,
		Logf:       logger.WithPrefix(t.Logf, "dns tsnet: "),
		Store:      new(mem.Store),
	}
	t.Cleanup(func() { s.Close() })

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if _, err := s.Up(ctx); err != nil {
		t.Fatalf("DNS tsnet peer Up: %v", err)
	}
	ip, _ := s.TailscaleIPs()
	if !ip.IsValid() {
		t.Fatal("DNS tsnet peer has no IPv4 address")
	}

	ln, err := s.Listen("tcp", ":80")
	if err != nil {
		t.Fatalf("DNS tsnet peer Listen :80: %v", err)
	}
	t.Cleanup(func() { ln.Close() })
	go http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, dnsPeerBody)
	}))

	pc, err := s.ListenPacket("udp", net.JoinHostPort(ip.String(), "53"))
	if err != nil {
		t.Fatalf("DNS tsnet peer ListenPacket :53: %v", err)
	}
	t.Cleanup(func() { pc.Close() })
	go serveDNS(pc, ip)
	return ip
}

func serveDNS(pc net.PacketConn, ip netip.Addr) {
	buf := make([]byte, 1500)
	for {
		n, from, err := pc.ReadFrom(buf)
		if err != nil {
			return
		}
		var p dnsmessage.Parser
		hdr, err := p.Start(buf[:n])
		if err != nil {
			continue
		}
		q, err := p.Question()
		if err != nil {
			continue
		}
		known := strings.HasSuffix(q.Name.String(), ".example.")
		res := dnsmessage.Header{ID: hdr.ID, Response: true}
		if !known {
			res.RCode = dnsmessage.RCodeNameError
		}
		b := dnsmessage.NewBuilder(nil, res)
		b.StartQuestions()
		b.Question(q)
		if known && q.Type == dnsmessage.TypeA {
			b.StartAnswers()
			b.AResource(dnsmessage.ResourceHeader{
				Name:  q.Name,
				Class: dnsmessage.ClassINET,
				TTL:   60,
			}, dnsmessage.AResource{A: ip.As4()})
		}
		if msg, err := b.Finish(); err == nil {
			pc.WriteTo(msg, from)
		}
	}
}

// waitForNewNameBody tries a new name each time, since Android caches failed lookups.
func (e *androidEnv) waitForNewNameBody(t *testing.T, domain, want string) {
	t.Helper()
	var got string
	deadline := time.Now().Add(*waitTimeout)
	for i := 1; time.Now().Before(deadline); i++ {
		if got = e.probe(t, fmt.Sprintf("http://host%d.%s/", i, domain)); strings.Contains(got, "body="+want+"\n") {
			return
		}
		time.Sleep(time.Second)
	}
	t.Fatalf("no name under %s returned %q; last probe result %q", domain, want, got)
}

func waitForDNSSettings(t *testing.T, e *androidEnv, what string, matches ...func(uiNode) bool) {
	t.Helper()
	openSettings(t, e)
	tap(t, e.adb, waitForAppNode(t, e, "the DNS settings row", withText(appString(t, strDNSSettings))))
	useTSDNS := appString(t, strUseTSDNS)
	pollUI(t, e.adb, "DNS settings showing "+what+" with Use Tailscale DNS on", func(s uiScreen) bool {
		if s.pkg() != androidPackage {
			dismissForeignApp(t, e, s.pkg())
			return false
		}
		found := s.has(withText(useTSDNS)) && s.has(isCheckedSwitch)
		for _, match := range matches {
			found = found && s.has(match)
		}
		return found
	})
}
