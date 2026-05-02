// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"fmt"
	"net/netip"
	"os"
	"runtime"
	"strings"
	"sync/atomic"
)

type tsocksRuntimeSnapshot struct {
	ActiveRelays int64
	Goroutines   int
	OpenFDs      int
}

func (c *tsocksController) relayStart(flowID string, src, dst netip.AddrPort, decision tsocksRouteDecision) tsocksRuntimeSnapshot {
	snapshot := c.snapshotRuntime(atomic.AddInt64(&c.activeRelays, 1))
	c.log(tsocksDatapathTag, fmt.Sprintf("event=relay_start flow_id=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t activeRelays=%d goroutines=%d openFDs=%d", flowID, src, dst, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, snapshot.ActiveRelays, snapshot.Goroutines, snapshot.OpenFDs))
	return snapshot
}

func (c *tsocksController) relayEnd(flowID string, src, dst netip.AddrPort, decision tsocksRouteDecision, bytesUp, bytesDown int64, reason string) tsocksRuntimeSnapshot {
	snapshot := c.snapshotRuntime(atomic.AddInt64(&c.activeRelays, -1))
	c.log(tsocksDatapathTag, fmt.Sprintf("event=relay_end flow_id=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t bytes_up=%d bytes_down=%d closeReason=%s activeRelays=%d goroutines=%d openFDs=%d", flowID, src, dst, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, bytesUp, bytesDown, sanitizeForLog(reason), snapshot.ActiveRelays, snapshot.Goroutines, snapshot.OpenFDs))
	return snapshot
}

func (c *tsocksController) logTerminatorAttach(flowID string, src, dst netip.AddrPort, decision tsocksRouteDecision, reason string) {
	c.log(tsocksDatapathTag, fmt.Sprintf("event=terminator_attach flow_id=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t reason=%s", flowID, src, dst, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, sanitizeForLog(reason)))
}

func (c *tsocksController) logSocksConnectEvent(flowID, target string, stage string, targetHost string, targetPort int, err error) {
	line := fmt.Sprintf("event=socks_connect flow_id=%s target=%s stage=%s socksHost=%s socksPort=%d targetHost=%s targetPort=%d", flowID, target, stage, tsocksServerHost, tsocksServerPort, targetHost, targetPort)
	if err != nil {
		line += fmt.Sprintf(" reason=%s", sanitizeForLog(err.Error()))
	}
	c.log(tsocksSocksTag, line)
}

func (c *tsocksController) snapshotRuntime(activeRelays int64) tsocksRuntimeSnapshot {
	return tsocksRuntimeSnapshot{
		ActiveRelays: activeRelays,
		Goroutines:   runtime.NumGoroutine(),
		OpenFDs:      tsocksOpenFDCount(),
	}
}

func tsocksOpenFDCount() int {
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return -1
	}
	return len(entries)
}

func tsocksCloseReason(results []relayResult) string {
	for _, result := range results {
		if result.kind == relayCloseRST {
			return result.direction + "_rst"
		}
	}
	first := results[0]
	if len(results) > 1 && results[1].completedAt.Before(first.completedAt) {
		first = results[1]
	}
	if first.kind == relayCloseFIN {
		return first.direction + "_fin"
	}
	if first.kind == relayCloseTimeout {
		return first.direction + "_timeout"
	}
	var parts []string
	for _, result := range results {
		if result.kind == relayCloseOther && result.err != nil {
			parts = append(parts, result.direction+"_"+sanitizeForLog(result.err.Error()))
		}
	}
	if len(parts) == 0 {
		return "eof"
	}
	return strings.Join(parts, ";")
}
