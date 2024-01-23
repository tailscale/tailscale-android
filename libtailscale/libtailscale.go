package libtailscale

import (
	"bytes"
	"fmt"
	"io"
	"log"
	"net/http"

	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/ipn/localapi"
	"tailscale.com/net/dns"
	"tailscale.com/net/netmon"
	"tailscale.com/net/tsdial"
	"tailscale.com/tsd"
	"tailscale.com/types/logid"
	"tailscale.com/wgengine"
	"tailscale.com/wgengine/router"
)

type Request struct {
	Method  string
	Path    string
	Headers map[string][]string
	Body    []byte
}

type Response struct {
	StatusCode int
	Headers    map[string][]string
	bodyWriter *bytes.Buffer
}

func (resp *Response) Body() []byte {
	return resp.bodyWriter.Bytes()
}

type Tailscale interface {
	ProcessRequest(req *Request) *Response
}

type tailscale struct {
	backend  *ipnlocal.LocalBackend
	localapi *localapi.Handler
}

func NewTailscale() (Tailscale, error) {
	logf := log.Printf
	var sys tsd.System
	var logID logid.PrivateID
	logID.UnmarshalText([]byte("dead0000dead0000dead0000dead0000dead0000dead0000dead0000dead0000"))
	netMon, err := netmon.New(logf)
	if err != nil {
		log.Printf("netmon.New: %w", err)
	}
	dialer := new(tsdial.Dialer)
	cb := &router.CallbackRouter{
		SetBoth:           func(rcfg *router.Config, dcfg *dns.OSConfig) error { return nil },
		SplitDNS:          false,
		GetBaseConfigFunc: nil,
	}
	engine, err := wgengine.NewUserspaceEngine(logf, wgengine.Config{
		Tun:          nil,
		Router:       cb,
		DNS:          cb,
		Dialer:       dialer,
		SetSubsystem: sys.Set,
		NetMon:       netMon,
	})
	if err != nil {
		return nil, fmt.Errorf("runBackend: NewUserspaceEngine: %v", err)
	}
	sys.Set(engine)
	backend, err := ipnlocal.NewLocalBackend(logf, logID.Public(), &sys, 0)
	if err != nil {
		return nil, err
	}
	api := localapi.NewHandler(backend, logf, netMon, logID.Public())
	return &tailscale{
		backend:  backend,
		localapi: api,
	}, nil
}

func (t *tailscale) ProcessRequest(req *Request) (resp *Response) {
	defer func() {
		if p := recover(); p != nil {
			resp = &Response{
				StatusCode: http.StatusInternalServerError,
			}
			fmt.Fprintf(resp, "%s", p)
		}
	}()

	resp = &Response{
		StatusCode: http.StatusOK,
		Headers:    make(map[string][]string),
		bodyWriter: &bytes.Buffer{},
	}
	t.localapi.ServeHTTP(resp, req.AsHTTPRequest())

	return
}

func (req *Request) AsHTTPRequest() *http.Request {
	result, _ := http.NewRequest(req.Method, fmt.Sprintf("http://server/%v", req.Path), nil)
	if req.Body != nil {
		result.Body = io.NopCloser(bytes.NewReader(req.Body))
	}
	if req.Headers != nil {
		for name, values := range req.Headers {
			for _, value := range values {
				result.Header.Add(name, value)
			}
		}
	}
	return result
}

func (resp *Response) Header() http.Header {
	return http.Header(resp.Headers)
}

func (resp *Response) WriteHeader(statusCode int) {
	resp.StatusCode = statusCode
}

func (resp *Response) Write(p []byte) (int, error) {
	return resp.bodyWriter.Write(p)
}
