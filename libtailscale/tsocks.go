// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"time"

	"tailscale.com/net/tsdial"
)

const (
	tsocksTestTag     = "TSOCKS_TEST"
	tsocksRouteTag    = "TSOCKS_ROUTE"
	tsocksSocksTag    = "TSOCKS_SOCKS"
	tsocksDatapathTag = "TSOCKS_DATAPATH"

	tsocksLANHost           = "192.168.31.101"
	tsocksTailnetLabHost    = "100.109.193.113"
	tsocksTailnetDomainHost = "wide-ts-wu"
	tsocksServerHost        = "100.78.63.77"
	tsocksServerPort        = 1080
	tsocksPublicHost        = "example.com"
	tsocksPublicPort        = 80

	tsocksProbeTimeoutDefault = 5000
	tsocksMaxTimeoutMs        = 10000
)

type tsocksRoute string

const (
	tsocksRouteDirect          tsocksRoute = "DIRECT"
	tsocksRouteTailscaleNormal tsocksRoute = "TAILSCALE_NORMAL"
	tsocksRouteTailnetSocks    tsocksRoute = "TAILNET_SOCKS"
)

type tsocksRouteDecision struct {
	Route                tsocksRoute `json:"route"`
	MatchedRule          string      `json:"matchedRule"`
	InjectedRouteApplied bool        `json:"injectedRouteApplied"`
}

type tsocksProbeRequest struct {
	Scenario     string `json:"scenario"`
	RequestID    string `json:"requestId"`
	Host         string `json:"host"`
	Port         int    `json:"port"`
	Protocol     string `json:"protocol"`
	Path         string `json:"path"`
	Payload      string `json:"payload"`
	HostHeader   string `json:"hostHeader"`
	TimeoutMs    int    `json:"timeoutMs"`
	SocksEnabled bool   `json:"socksEnabled"`
	PreviewOnly  bool   `json:"previewOnly"`
}

type tsocksProbeResult struct {
	Route         string `json:"route"`
	MatchedRule   string `json:"matchedRule"`
	BytesSent     int    `json:"bytesSent"`
	BytesReceived int    `json:"bytesReceived"`
	Detail        string `json:"detail"`
	InjectedRoute bool   `json:"injectedRouteApplied"`
}

type tsocksController struct {
	appCtx AppContext
	dialer *tsdial.Dialer
}

func newTSocksController(appCtx AppContext, dialer *tsdial.Dialer) *tsocksController {
	return &tsocksController{appCtx: appCtx, dialer: dialer}
}

func (a *App) RunTsocksProbe(requestJSON string) (string, error) {
	a.ready.Wait()
	if a.tsocks == nil {
		return "", errors.New("tsocks_not_ready")
	}
	result, err := a.tsocks.runProbe(requestJSON)
	if err != nil {
		return "", err
	}
	b, err := json.Marshal(result)
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func (c *tsocksController) runProbe(requestJSON string) (*tsocksProbeResult, error) {
	var req tsocksProbeRequest
	if err := json.Unmarshal([]byte(requestJSON), &req); err != nil {
		return nil, err
	}
	if req.Scenario == "" {
		req.Scenario = "unspecified"
	}
	if req.RequestID == "" {
		req.RequestID = fmt.Sprintf("req-%d", time.Now().UnixMilli())
	}
	if req.Protocol == "" {
		req.Protocol = "tcp"
	}
	if req.TimeoutMs == 0 {
		req.TimeoutMs = tsocksProbeTimeoutDefault
	}
	if err := c.validateProbeRequest(req); err != nil {
		c.log(tsocksTestTag, fmt.Sprintf("event=TEST_FAIL requestId=%s scenario=%s route=UNKNOWN reason=%s", req.RequestID, req.Scenario, sanitizeForLog(err.Error())))
		return nil, err
	}
	c.log(tsocksTestTag, fmt.Sprintf("event=request_start requestId=%s scenario=%s protocol=%s host=%s port=%d timeoutMs=%d socksEnabled=%t", req.RequestID, req.Scenario, req.Protocol, req.Host, req.Port, req.TimeoutMs, req.SocksEnabled))
	decision := c.routeForProbe(req)
	probeTarget := net.JoinHostPort(req.Host, strconv.Itoa(req.Port))
	offloadDecision := "BASELINE_NATIVE_PATH_OK"
	if addr, err := netip.ParseAddr(req.Host); err == nil && addr.Is4() {
		offloadDecision = tsocksDecisionOffloadDecision(decision, netip.AddrPortFrom(addr.Unmap(), uint16(req.Port)))
	}
	c.log(tsocksRouteTag, fmt.Sprintf("event=route_decision requestId=%s target=%s matchedRule=%s selectedRoute=%s injectedRoute=%t offloadDecision=%s recursionGuard=%t", req.RequestID, probeTarget, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, offloadDecision, tsocksDecisionRecursionGuard(decision)))
	if req.PreviewOnly {
		result := &tsocksProbeResult{
			Route:         string(decision.Route),
			MatchedRule:   decision.MatchedRule,
			Detail:        "preview_only",
			InjectedRoute: decision.InjectedRouteApplied,
		}
		c.log(tsocksTestTag, fmt.Sprintf("event=TEST_PASS requestId=%s scenario=%s route=%s protocol=%s bytesSent=0 bytesReceived=0 detail=%s", req.RequestID, req.Scenario, decision.Route, req.Protocol, result.Detail))
		return result, nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(req.TimeoutMs)*time.Millisecond)
	defer cancel()
	conn, err := c.openProbeConn(ctx, req, decision)
	if err != nil {
		c.log(tsocksTestTag, fmt.Sprintf("event=TEST_FAIL requestId=%s scenario=%s route=%s reason=%s", req.RequestID, req.Scenario, decision.Route, sanitizeForLog(err.Error())))
		return nil, err
	}
	defer conn.Close()
	result, err := c.executeProbe(conn, req, decision)
	if err != nil {
		c.log(tsocksTestTag, fmt.Sprintf("event=TEST_FAIL requestId=%s scenario=%s route=%s reason=%s", req.RequestID, req.Scenario, decision.Route, sanitizeForLog(err.Error())))
		return nil, err
	}
	result.Route = string(decision.Route)
	result.MatchedRule = decision.MatchedRule
	result.InjectedRoute = decision.InjectedRouteApplied
	c.log(tsocksTestTag, fmt.Sprintf("event=TEST_PASS requestId=%s scenario=%s route=%s protocol=%s bytesSent=%d bytesReceived=%d detail=%s", req.RequestID, req.Scenario, decision.Route, req.Protocol, result.BytesSent, result.BytesReceived, sanitizeForLog(result.Detail)))
	return result, nil
}

func (c *tsocksController) datapathHandler(src, dst netip.AddrPort) (func(net.Conn), bool) {
	decision := c.routeForDatapath(dst)
	flowID := tsocksFlowID(src, dst)
	offloadDecision := tsocksDecisionOffloadDecision(decision, dst)
	c.log(tsocksDatapathTag, fmt.Sprintf("event=route_decision flow=datapath flowId=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t offloadDecision=%s recursionGuard=%t", flowID, src, dst, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, offloadDecision, tsocksDecisionRecursionGuard(decision)))
	if decision.Route != tsocksRouteTailnetSocks {
		return nil, false
	}
	return func(conn net.Conn) {
		c.handleDatapathConn(src, dst, conn, decision)
	}, true
}

func (c *tsocksController) handleDatapathConn(src, dst netip.AddrPort, client net.Conn, decision tsocksRouteDecision) {
	defer client.Close()
	ctx, cancel := context.WithTimeout(context.Background(), tsocksMaxTimeoutMs*time.Millisecond)
	defer cancel()
	flowID := tsocksFlowID(src, dst)
	backend, err := c.dialViaSocks(ctx, flowID, dst.Addr().String(), int(dst.Port()), "datapath", dst.String())
	if err != nil {
		c.log(tsocksDatapathTag, fmt.Sprintf("event=target_connect_fail flow=datapath flowId=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t reason=%s", flowID, src, dst, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, sanitizeForLog(err.Error())))
		return
	}
	defer backend.Close()
	c.log(tsocksDatapathTag, fmt.Sprintf("event=target_connect_success flow=datapath flowId=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t", flowID, src, dst, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied))
	bytesUp, bytesDown, reason := relayTCP(client, backend)
	c.log(tsocksDatapathTag, fmt.Sprintf("event=conn_close flow=datapath flowId=%s src=%s dst=%s matchedRule=%s selectedRoute=%s injectedRoute=%t bytes_up=%d bytes_down=%d closeReason=%s", flowID, src, dst, decision.MatchedRule, decision.Route, decision.InjectedRouteApplied, bytesUp, bytesDown, sanitizeForLog(reason)))
}

func (c *tsocksController) validateProbeRequest(req tsocksProbeRequest) error {
	if strings.TrimSpace(req.Host) == "" {
		return errors.New("missing_host")
	}
	if req.Port < 1 || req.Port > 65535 {
		return errors.New("invalid_port")
	}
	if req.Protocol != "tcp" && req.Protocol != "http" {
		return errors.New("invalid_protocol")
	}
	if req.TimeoutMs <= 0 {
		return errors.New("invalid_timeout")
	}
	if req.TimeoutMs > tsocksMaxTimeoutMs {
		return errors.New("timeout_too_large")
	}
	return nil
}

func (c *tsocksController) routeForProbe(req tsocksProbeRequest) tsocksRouteDecision {
	host := strings.ToLower(strings.TrimSpace(req.Host))
	if addr, err := netip.ParseAddr(host); err == nil && addr.Is4() {
		decision := matchTSocksRule(netip.AddrPortFrom(addr.Unmap(), uint16(req.Port)))
		if !req.SocksEnabled && decision.Route == tsocksRouteTailnetSocks {
			return tsocksRouteDecision{Route: tsocksRouteDirect, MatchedRule: "socks_disabled", InjectedRouteApplied: decision.InjectedRouteApplied}
		}
		return decision
	}
	switch {
	case host == strings.ToLower(tsocksLANHost):
		return tsocksRouteDecision{Route: tsocksRouteDirect, MatchedRule: "lan_baseline", InjectedRouteApplied: false}
	case host == strings.ToLower(tsocksTailnetLabHost):
		return tsocksRouteDecision{Route: tsocksRouteTailscaleNormal, MatchedRule: "tailnet_lab_baseline", InjectedRouteApplied: false}
	case host == strings.ToLower(tsocksTailnetDomainHost):
		return tsocksRouteDecision{Route: tsocksRouteTailscaleNormal, MatchedRule: "tailnet_domain_baseline", InjectedRouteApplied: false}
	case host == strings.ToLower(tsocksServerHost) && req.Port == tsocksServerPort:
		return tsocksRouteDecision{Route: tsocksRouteDirect, MatchedRule: "socks_server_self", InjectedRouteApplied: false}
	case !req.SocksEnabled:
		return tsocksRouteDecision{Route: tsocksRouteDirect, MatchedRule: "socks_disabled", InjectedRouteApplied: false}
	case host == strings.ToLower(tsocksPublicHost) && req.Port == tsocksPublicPort:
		return tsocksRouteDecision{Route: tsocksRouteTailnetSocks, MatchedRule: "public_allowlist_example_com_80", InjectedRouteApplied: false}
	default:
		return tsocksRouteDecision{Route: tsocksRouteDirect, MatchedRule: "default_direct", InjectedRouteApplied: false}
	}
}

func (c *tsocksController) routeForDatapath(dst netip.AddrPort) tsocksRouteDecision {
	return matchTSocksRule(dst)
}

func (c *tsocksController) openProbeConn(ctx context.Context, req tsocksProbeRequest, decision tsocksRouteDecision) (net.Conn, error) {
	targetAddr := net.JoinHostPort(req.Host, strconv.Itoa(req.Port))
	switch decision.Route {
	case tsocksRouteDirect, tsocksRouteTailscaleNormal:
		conn, err := c.dialer.UserDial(ctx, "tcp", targetAddr)
		if err != nil {
			c.log(tsocksTestTag, fmt.Sprintf("event=target_connect_fail requestId=%s route=%s host=%s port=%d reason=%s", req.RequestID, decision.Route, req.Host, req.Port, sanitizeForLog(err.Error())))
			return nil, err
		}
		c.log(tsocksTestTag, fmt.Sprintf("event=target_connect_success requestId=%s route=%s host=%s port=%d", req.RequestID, decision.Route, req.Host, req.Port))
		return conn, nil
	case tsocksRouteTailnetSocks:
		return c.dialViaSocks(ctx, req.RequestID, req.Host, req.Port, "probe", targetAddr)
	default:
		return nil, errors.New("unsupported_route")
	}
}

func (c *tsocksController) executeProbe(conn net.Conn, req tsocksProbeRequest, decision tsocksRouteDecision) (*tsocksProbeResult, error) {
	_ = conn.SetDeadline(time.Now().Add(time.Duration(req.TimeoutMs) * time.Millisecond))
	switch req.Protocol {
	case "http":
		return c.probeHTTP(conn, req, decision)
	case "tcp":
		return c.probeTCP(conn, req, decision)
	default:
		return nil, errors.New("unsupported_protocol")
	}
}

func (c *tsocksController) probeHTTP(conn net.Conn, req tsocksProbeRequest, decision tsocksRouteDecision) (*tsocksProbeResult, error) {
	method := "GET"
	bodyBytes := []byte(req.Payload)
	if len(bodyBytes) > 0 {
		method = "POST"
	}
	path := strings.TrimSpace(req.Path)
	if path == "" {
		path = "/"
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}
	headers := fmt.Sprintf("%s %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\nUser-Agent: tailscale-android-tsocks-test\r\n", method, path, req.Host)
	hostHeader := strings.TrimSpace(req.HostHeader)
	if hostHeader == "" {
		hostHeader = req.Host
	}
	headers = fmt.Sprintf("%s %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\nUser-Agent: tailscale-android-tsocks-test\r\n", method, path, hostHeader)
	if len(bodyBytes) > 0 {
		headers += fmt.Sprintf("Content-Type: text/plain; charset=utf-8\r\nContent-Length: %d\r\n", len(bodyBytes))
	}
	headers += "\r\n"
	if _, err := conn.Write([]byte(headers)); err != nil {
		return nil, err
	}
	bytesSent := len(headers)
	if len(bodyBytes) > 0 {
		if _, err := conn.Write(bodyBytes); err != nil {
			return nil, err
		}
		bytesSent += len(bodyBytes)
	}
	responseBytes, err := io.ReadAll(io.LimitReader(conn, 8*1024))
	if err != nil {
		return nil, err
	}
	if len(responseBytes) == 0 {
		return nil, errors.New("http_empty_response")
	}
	statusLine := strings.TrimSpace(strings.SplitN(string(responseBytes), "\n", 2)[0])
	if !strings.HasPrefix(statusLine, "HTTP/1.") {
		return nil, errors.New("http_bad_status_line")
	}
	parts := strings.Split(statusLine, " ")
	if len(parts) < 2 {
		return nil, errors.New("http_bad_status_line")
	}
	code, err := strconv.Atoi(parts[1])
	if err != nil {
		return nil, err
	}
	if code < 200 || code > 399 {
		return nil, fmt.Errorf("http_status_%d", code)
	}
	c.log(tsocksTestTag, fmt.Sprintf("event=http_result requestId=%s route=%s statusLine=%s bytesSent=%d bytesReceived=%d", req.RequestID, decision.Route, sanitizeForLog(statusLine), bytesSent, len(responseBytes)))
	return &tsocksProbeResult{BytesSent: bytesSent, BytesReceived: len(responseBytes), Detail: statusLine}, nil
}

func (c *tsocksController) probeTCP(conn net.Conn, req tsocksProbeRequest, decision tsocksRouteDecision) (*tsocksProbeResult, error) {
	payload := req.Payload
	if payload == "" {
		payload = fmt.Sprintf("tailscale-tsocks-test requestId=%s scenario=%s\n", req.RequestID, req.Scenario)
	}
	expectsPong := strings.EqualFold(strings.TrimSpace(payload), "PING")
	if expectsPong {
		payload = "PING\n"
	}
	if _, err := io.WriteString(conn, payload); err != nil {
		return nil, err
	}
	var responseBytes []byte
	var err error
	if expectsPong {
		responseBytes, err = readUntil(conn, "PONG")
	} else {
		responseBytes, err = io.ReadAll(io.LimitReader(conn, 8*1024))
	}
	if err != nil {
		return nil, err
	}
	if len(responseBytes) == 0 {
		return nil, errors.New("tcp_empty_response")
	}
	responseText := strings.TrimSpace(string(responseBytes))
	if expectsPong && !strings.Contains(responseText, "PONG") {
		return nil, errors.New("tcp_missing_pong")
	}
	c.log(tsocksTestTag, fmt.Sprintf("event=tcp_result requestId=%s route=%s bytesSent=%d bytesReceived=%d response=%s", req.RequestID, decision.Route, len(payload), len(responseBytes), sanitizeForLog(responseText)))
	return &tsocksProbeResult{BytesSent: len(payload), BytesReceived: len(responseBytes), Detail: "tcp_response_received"}, nil
}

func (c *tsocksController) dialViaSocks(ctx context.Context, requestID, targetHost string, targetPort int, flowType, target string) (net.Conn, error) {
	conn, err := c.dialer.UserDial(ctx, "tcp", net.JoinHostPort(tsocksServerHost, strconv.Itoa(tsocksServerPort)))
	if err != nil {
		c.log(tsocksSocksTag, fmt.Sprintf("event=socks_connect_fail flow=%s requestId=%s target=%s targetHost=%s targetPort=%d reason=%s", flowType, requestID, target, targetHost, targetPort, sanitizeForLog(err.Error())))
		return nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	c.log(tsocksSocksTag, fmt.Sprintf("event=socks_server_connect_success flow=%s requestId=%s target=%s socksHost=%s socksPort=%d", flowType, requestID, target, tsocksServerHost, tsocksServerPort))
	if err := socksConnect(conn, targetHost, targetPort); err != nil {
		_ = conn.Close()
		c.log(tsocksSocksTag, fmt.Sprintf("event=socks_connect_fail flow=%s requestId=%s target=%s targetHost=%s targetPort=%d reason=%s", flowType, requestID, target, targetHost, targetPort, sanitizeForLog(err.Error())))
		return nil, err
	}
	_ = conn.SetDeadline(time.Time{})
	c.log(tsocksSocksTag, fmt.Sprintf("event=socks_connect_success flow=%s requestId=%s target=%s targetHost=%s targetPort=%d", flowType, requestID, target, targetHost, targetPort))
	return conn, nil
}

func socksConnect(conn net.Conn, host string, port int) error {
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		return err
	}
	methodResponse := make([]byte, 2)
	if _, err := io.ReadFull(conn, methodResponse); err != nil {
		return err
	}
	if methodResponse[0] != 0x05 || methodResponse[1] != 0x00 {
		return fmt.Errorf("socks_method_rejected_%d_%d", methodResponse[0], methodResponse[1])
	}
	request, err := buildSocksConnectRequest(host, port)
	if err != nil {
		return err
	}
	if _, err := conn.Write(request); err != nil {
		return err
	}
	responseHeader := make([]byte, 4)
	if _, err := io.ReadFull(conn, responseHeader); err != nil {
		return err
	}
	if responseHeader[0] != 0x05 {
		return fmt.Errorf("socks_bad_version_%d", responseHeader[0])
	}
	if responseHeader[1] != 0x00 {
		return fmt.Errorf("socks_connect_reply_%d", responseHeader[1])
	}
	return discardSocksAddress(conn, int(responseHeader[3]))
}

func buildSocksConnectRequest(host string, port int) ([]byte, error) {
	b := []byte{0x05, 0x01, 0x00}
	if addr, err := netip.ParseAddr(host); err == nil {
		if addr.Is4() {
			b = append(b, 0x01)
			b = append(b, addr.AsSlice()...)
		} else {
			b = append(b, 0x04)
			b = append(b, addr.AsSlice()...)
		}
	} else {
		hostBytes := []byte(host)
		if len(hostBytes) > 255 {
			return nil, errors.New("host_too_long")
		}
		b = append(b, 0x03, byte(len(hostBytes)))
		b = append(b, hostBytes...)
	}
	b = append(b, byte((port>>8)&0xff), byte(port&0xff))
	return b, nil
}

func discardSocksAddress(r io.Reader, atyp int) error {
	var addressLength int
	switch atyp {
	case 0x01:
		addressLength = 4
	case 0x03:
		var l [1]byte
		if _, err := io.ReadFull(r, l[:]); err != nil {
			return err
		}
		addressLength = int(l[0])
	case 0x04:
		addressLength = 16
	default:
		return fmt.Errorf("socks_unknown_atyp_%d", atyp)
	}
	_, err := io.CopyN(io.Discard, r, int64(addressLength+2))
	return err
}

type tsocksTCPHalfCloser interface {
	CloseRead() error
	CloseWrite() error
}

func relayTCP(client, backend net.Conn) (int64, int64, string) {
	type relayResult struct {
		direction string
		n         int64
		err       error
	}
	results := make(chan relayResult, 2)
	var clientHalf tsocksTCPHalfCloser
	if hc, ok := client.(tsocksTCPHalfCloser); ok {
		clientHalf = hc
	}
	var backendHalf tsocksTCPHalfCloser
	if hc, ok := backend.(tsocksTCPHalfCloser); ok {
		backendHalf = hc
	}
	go func() {
		n, err := io.Copy(backend, client)
		results <- relayResult{direction: "up", n: n, err: normalizeRelayErr(err)}
		if backendHalf != nil {
			_ = backendHalf.CloseWrite()
		}
		if clientHalf != nil {
			_ = clientHalf.CloseRead()
		}
	}()
	go func() {
		n, err := io.Copy(client, backend)
		results <- relayResult{direction: "down", n: n, err: normalizeRelayErr(err)}
		if clientHalf != nil {
			_ = clientHalf.CloseWrite()
		}
		if backendHalf != nil {
			_ = backendHalf.CloseRead()
		}
	}()
	var bytesUp, bytesDown int64
	var reasons []string
	for i := 0; i < 2; i++ {
		result := <-results
		if result.direction == "up" {
			bytesUp = result.n
		} else {
			bytesDown = result.n
		}
		if result.err != nil {
			reasons = append(reasons, result.err.Error())
		}
	}
	if len(reasons) == 0 {
		return bytesUp, bytesDown, "eof"
	}
	return bytesUp, bytesDown, strings.Join(reasons, ";")
}

func normalizeRelayErr(err error) error {
	if err == nil || errors.Is(err, io.EOF) {
		return nil
	}
	if ne, ok := err.(net.Error); ok && ne.Timeout() {
		return nil
	}
	return err
}

func readUntil(r io.Reader, marker string) ([]byte, error) {
	buf := make([]byte, 0, 8*1024)
	chunk := make([]byte, 1024)
	for len(buf) < 8*1024 {
		n, err := r.Read(chunk)
		if n > 0 {
			buf = append(buf, chunk[:n]...)
			if strings.Contains(string(buf), marker) {
				return buf, nil
			}
		}
		if err != nil {
			if errors.Is(err, io.EOF) && len(buf) > 0 {
				return buf, nil
			}
			return nil, err
		}
	}
	return buf, nil
}

func (c *tsocksController) log(tag, line string) {
	if c.appCtx != nil {
		c.appCtx.Log(tag, line)
	}
}

func sanitizeForLog(value string) string {
	value = strings.TrimSpace(value)
	if value == "" {
		return "empty"
	}
	var b strings.Builder
	for _, r := range value {
		switch {
		case (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || strings.ContainsRune("_./:=-", r):
			b.WriteRune(r)
		case r == ' ' || r == '\n' || r == '\r' || r == '\t':
			b.WriteByte('_')
		default:
			b.WriteByte('-')
		}
	}
	return b.String()
}
