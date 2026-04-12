// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"fmt"
	"net"
	"net/netip"
	"os"
	"slices"
	"strings"
	"sync"
	"time"

	wtun "github.com/tailscale/wireguard-go/tun"
	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
)

type step0Tun struct {
	raw    wtun.Device
	appCtx AppContext
	tsocks *tsocksController

	ep     *channel.Endpoint
	stack  *stack.Stack
	ctx    context.Context
	cancel context.CancelFunc
	lns    []*gonet.TCPListener

	mu           sync.Mutex
	seenFlows    map[string]bool
	seenPayloads map[string]bool
	seenRoutes   map[string]bool
	seenEvents   map[string]bool
	closed       bool
}

func newStep0Tun(raw wtun.Device, appCtx AppContext, tsocks *tsocksController) (wtun.Device, error) {
	mtu, err := raw.MTU()
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(context.Background())
	w := &step0Tun{raw: raw, appCtx: appCtx, tsocks: tsocks, ctx: ctx, cancel: cancel, seenFlows: map[string]bool{}, seenPayloads: map[string]bool{}, seenRoutes: map[string]bool{}, seenEvents: map[string]bool{}}
	if err := w.initProofStack(uint32(mtu)); err != nil {
		cancel()
		return nil, err
	}
	go w.pumpProofPackets()
	w.log(tsocksDatapathTag, fmt.Sprintf("event=step0_enabled targets=%s route=%s", tsocksTargetsSummary(tsocksInterceptTargets()), tsocksRouteTailnetSocks))
	return w, nil
}

func (w *step0Tun) initProofStack(mtu uint32) error {
	w.ep = channel.New(1024, mtu, "")
	w.stack = stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol},
		HandleLocal:        true,
	})
	if tcpipErr := w.stack.CreateNIC(1, w.ep); tcpipErr != nil {
		return fmt.Errorf("CreateNIC: %v", tcpipErr)
	}
	for _, addr := range tsocksInjectedRouteTargets() {
		protoAddr := tcpip.ProtocolAddress{
			Protocol:          ipv4.ProtocolNumber,
			AddressWithPrefix: tcpip.AddrFromSlice(addr.AsSlice()).WithPrefix(),
		}
		if tcpipErr := w.stack.AddProtocolAddress(1, protoAddr, stack.AddressProperties{}); tcpipErr != nil {
			return fmt.Errorf("AddProtocolAddress %s: %v", addr, tcpipErr)
		}
	}
	w.stack.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})
	for _, target := range tsocksInterceptTargets() {
		listener, err := gonet.ListenTCP(w.stack, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFromSlice(target.Addr().AsSlice()), Port: target.Port()}, ipv4.ProtocolNumber)
		if err != nil {
			return err
		}
		w.lns = append(w.lns, listener)
		go w.serveTargetListener(listener, target)
	}
	return nil
}

func (w *step0Tun) serveTargetListener(listener *gonet.TCPListener, target netip.AddrPort) {
	for {
		conn, err := listener.Accept()
		if err != nil {
			w.mu.Lock()
			closed := w.closed
			w.mu.Unlock()
			if !closed {
				w.log(tsocksDatapathTag, fmt.Sprintf("event=listener_accept_fail dst=%s reason=%s", target, sanitizeForLog(err.Error())))
			}
			return
		}
		src, ok := addrPortFromNetAddr(conn.RemoteAddr())
		if !ok {
			src = netip.MustParseAddrPort("0.0.0.0:0")
		}
		flowID := tsocksFlowID(src, target)
		w.log(tsocksDatapathTag, fmt.Sprintf("event=forwarder_accept flow_id=%s src=%s dst=%s", flowID, src, target))
		go w.serveProofConn(conn, target)
	}
}

func (w *step0Tun) serveProofConn(conn net.Conn, target netip.AddrPort) {
	defer conn.Close()
	decision := matchTSocksRule(target)
	src, ok := addrPortFromNetAddr(conn.RemoteAddr())
	if !ok {
		src = netip.MustParseAddrPort("0.0.0.0:0")
	}
	flowID := tsocksFlowID(src, target)
	w.tsocks.logTerminatorAttach(flowID, src, target, decision, "gvisor_listener_accept")
	w.log(tsocksDatapathTag, fmt.Sprintf("event=endpoint_created flow_id=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t", flowID, src, target, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied))
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	backend, err := w.tsocks.dialViaSocks(ctx, flowID, target.Addr().String(), int(target.Port()), "datapath", target.String())
	if err != nil {
		w.log(tsocksDatapathTag, fmt.Sprintf("event=target_connect_fail flow_id=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t reason=%s", flowID, src, target, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, sanitizeForLog(err.Error())))
		return
	}
	defer backend.Close()
	w.log(tsocksDatapathTag, fmt.Sprintf("event=target_connect_success flow_id=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t", flowID, src, target, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied))
	w.tsocks.relayStart(flowID, src, target, decision)
	bytesUp, bytesDown, reason := relayTCP(conn, backend)
	reason = w.adjustCloseReason(flowID, reason)
	w.tsocks.relayEnd(flowID, src, target, decision, bytesUp, bytesDown, reason)
	w.log(tsocksDatapathTag, fmt.Sprintf("event=conn_close flow_id=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t bytes_up=%d bytes_down=%d closeReason=%s", flowID, src, target, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, bytesUp, bytesDown, sanitizeForLog(reason)))
}

func (w *step0Tun) pumpProofPackets() {
	for {
		pkt := w.ep.ReadContext(w.ctx)
		if pkt == nil {
			return
		}
		view := pkt.ToView()
		packet := append([]byte(nil), view.AsSlice()...)
		pkt.DecRef()
		w.logOutboundTCP(packet)
		if _, err := w.raw.Write([][]byte{packet}, 0); err != nil {
			w.log(tsocksDatapathTag, fmt.Sprintf("event=raw_write_fail reason=%s", sanitizeForLog(err.Error())))
			return
		}
	}
}

func (w *step0Tun) logOutboundTCP(packet []byte) {
	if len(packet) < header.IPv4MinimumSize {
		return
	}
	ip := header.IPv4(packet)
	if !ip.IsValid(len(packet)) || ip.TransportProtocol() != header.TCPProtocolNumber {
		return
	}
	tcpHdr := header.TCP(ip.Payload())
	flags := tcpHdr.Flags()
	src := netip.AddrPortFrom(netip.AddrFrom4(ip.SourceAddress().As4()).Unmap(), tcpHdr.SourcePort())
	dst := netip.AddrPortFrom(netip.AddrFrom4(ip.DestinationAddress().As4()).Unmap(), tcpHdr.DestinationPort())
	flowID := tsocksFlowID(src, dst)
	if flags.Contains(header.TCPFlagSyn) && flags.Contains(header.TCPFlagAck) {
		w.logTCPEventOnce(flowID, "synack_sent", src, dst, "direction=server_to_client")
	}
	if flags == header.TCPFlagAck {
		w.logTCPEventOnce(flowID, "ack_seen", src, dst, "direction=server_to_client")
	}
	if flags.Contains(header.TCPFlagFin) && flags.Contains(header.TCPFlagAck) {
		w.logTCPEventOnce(flowID, "finack_seen", src, dst, "direction=server_to_client")
	} else if flags.Contains(header.TCPFlagFin) {
		w.logTCPEventOnce(flowID, "fin_seen", src, dst, "direction=server_to_client")
	}
	if flags.Contains(header.TCPFlagRst) {
		w.logTCPEventOnce(flowID, "rst_seen", src, dst, "direction=server_to_client")
	}
}

func (w *step0Tun) File() *os.File { return w.raw.File() }

func (w *step0Tun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	for {
		n, err := w.raw.Read(bufs, sizes, offset)
		if err != nil {
			return n, err
		}
		out := 0
		for i := 0; i < n; i++ {
			packet := bufs[i][offset : offset+sizes[i]]
			if w.shouldIntercept(packet) {
				w.injectProofPacket(packet)
				continue
			}
			if out != i {
				copy(bufs[out][offset:], packet)
				sizes[out] = sizes[i]
			}
			out++
		}
		if out > 0 {
			return out, nil
		}
	}
}

func (w *step0Tun) shouldIntercept(packet []byte) bool {
	if len(packet) < header.IPv4MinimumSize {
		return false
	}
	ip := header.IPv4(packet)
	if !ip.IsValid(len(packet)) || ip.TransportProtocol() != header.TCPProtocolNumber {
		return false
	}
	src := netip.AddrFrom4(ip.SourceAddress().As4()).Unmap()
	dst := netip.AddrFrom4(ip.DestinationAddress().As4()).Unmap()
	tcpHdr := header.TCP(ip.Payload())
	dstPort := tcpHdr.DestinationPort()
	target := netip.AddrPortFrom(dst, dstPort)
	decision := w.tsocks.routeForDatapath(target)
	flowID := tsocksFlowID(netip.AddrPortFrom(src, tcpHdr.SourcePort()), target)
	flags := tcpHdr.Flags()
	if flags.Contains(header.TCPFlagSyn) && !flags.Contains(header.TCPFlagAck) {
		offloadState := tsocksDecisionOffloadState(decision, target)
		w.mu.Lock()
		key := flowID
		firstRoute := !w.seenRoutes[key]
		if firstRoute {
			w.seenRoutes[key] = true
		}
		first := !w.seenFlows[key] && decision.Route == tsocksRouteTailnetSocks
		if first {
			w.seenFlows[key] = true
		}
		w.mu.Unlock()
		if firstRoute {
			line := fmt.Sprintf("event=route_decision flow_id=%s src=%s:%d dst=%s:%d protocol=tcp matchedRule=%s selectedRoute=%s injectedRoute=%t entered_tun_due_to_/32=%t offloadDecision=%s offloadReason=%s recursionGuard=%t", flowID, src, tcpHdr.SourcePort(), dst, dstPort, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, decision.InjectedRouteApplied, offloadState.Decision, offloadState.Reason, tsocksDecisionRecursionGuard(decision))
			if decision.InjectedRouteApplied && decision.Route == tsocksRouteDirect {
				line += " expectedBehavior=true note=entered_tun_due_to_/32_is_expected_not_bug"
			}
			w.log(tsocksDatapathTag, line)
		}
		if first {
			w.log(tsocksDatapathTag, fmt.Sprintf("event=flow_identified flow_id=%s src=%s:%d dst=%s:%d protocol=tcp matchedRule=%s selectedRoute=%s injectedRoute=%t offloadDecision=%s offloadReason=%s recursionGuard=%t", flowID, src, tcpHdr.SourcePort(), dst, dstPort, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, offloadState.Decision, offloadState.Reason, tsocksDecisionRecursionGuard(decision)))
			w.logTCPEventOnce(flowID, "syn_received", netip.AddrPortFrom(src, tcpHdr.SourcePort()), target, "direction=client_to_server")
		}
	}
	if flags == header.TCPFlagAck {
		w.logTCPEventOnce(flowID, "ack_seen", netip.AddrPortFrom(src, tcpHdr.SourcePort()), target, "direction=client_to_server")
	}
	if flags.Contains(header.TCPFlagFin) && flags.Contains(header.TCPFlagAck) {
		w.logTCPEventOnce(flowID, "finack_seen", netip.AddrPortFrom(src, tcpHdr.SourcePort()), target, "direction=client_to_server")
	} else if flags.Contains(header.TCPFlagFin) {
		w.logTCPEventOnce(flowID, "fin_seen", netip.AddrPortFrom(src, tcpHdr.SourcePort()), target, "direction=client_to_server")
	}
	if flags.Contains(header.TCPFlagRst) {
		w.logTCPEventOnce(flowID, "rst_seen", netip.AddrPortFrom(src, tcpHdr.SourcePort()), target, "direction=client_to_server")
	}
	if decision.Route != tsocksRouteTailnetSocks || !slices.Contains(tsocksInterceptTargets(), target) {
		return false
	}
	if len(tcpHdr.Payload()) > 0 {
		w.mu.Lock()
		firstData := !w.seenPayloads[flowID]
		if firstData {
			w.seenPayloads[flowID] = true
		}
		w.mu.Unlock()
		if firstData {
			w.log(tsocksDatapathTag, fmt.Sprintf("event=payload_seen flow_id=%s src=%s:%d dst=%s:%d bytes=%d", flowID, src, tcpHdr.SourcePort(), dst, dstPort, len(tcpHdr.Payload())))
		}
	}
	return true
}

func (w *step0Tun) injectProofPacket(packet []byte) {
	pkb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(append([]byte(nil), packet...))})
	w.ep.InjectInbound(header.IPv4ProtocolNumber, pkb)
}

func (w *step0Tun) Write(bufs [][]byte, offset int) (int, error) { return w.raw.Write(bufs, offset) }
func (w *step0Tun) MTU() (int, error)                            { return w.raw.MTU() }
func (w *step0Tun) Name() (string, error)                        { return w.raw.Name() }
func (w *step0Tun) Events() <-chan wtun.Event                    { return w.raw.Events() }
func (w *step0Tun) BatchSize() int                               { return w.raw.BatchSize() }

func (w *step0Tun) Close() error {
	w.mu.Lock()
	w.closed = true
	w.mu.Unlock()
	w.cancel()
	for _, ln := range w.lns {
		_ = ln.Close()
	}
	if w.ep != nil {
		w.ep.Close()
	}
	return w.raw.Close()
}

func addrPortFromNetAddr(addr net.Addr) (netip.AddrPort, bool) {
	if addr == nil {
		return netip.AddrPort{}, false
	}
	parsed, err := netip.ParseAddrPort(addr.String())
	if err != nil {
		return netip.AddrPort{}, false
	}
	return parsed, true
}

func (w *step0Tun) log(tag, line string) {
	if w.appCtx != nil {
		w.appCtx.Log(tag, line)
	}
}

func (w *step0Tun) logTCPEventOnce(flowID, event string, src, dst netip.AddrPort, extra string) {
	key := flowID + ":" + event + ":" + extra
	w.mu.Lock()
	if w.seenEvents[key] {
		w.mu.Unlock()
		return
	}
	w.seenEvents[key] = true
	w.mu.Unlock()
	line := fmt.Sprintf("event=%s flow_id=%s src=%s dst=%s", event, flowID, src, dst)
	if extra != "" {
		line += " " + extra
	}
	w.log(tsocksDatapathTag, line)
}

func (w *step0Tun) adjustCloseReason(flowID, reason string) string {
	if strings.HasSuffix(reason, "_rst") {
		return reason
	}
	if w.hasSeenEvent(flowID + ":rst_seen:direction=client_to_server") {
		return "client_rst"
	}
	if w.hasSeenEvent(flowID + ":rst_seen:direction=server_to_client") {
		return "server_rst"
	}
	return reason
}

func (w *step0Tun) hasSeenEvent(key string) bool {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.seenEvents[key]
}
