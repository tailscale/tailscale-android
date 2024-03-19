// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package localapiservice

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"time"

	"tailscale.com/ipn/ipnlocal"
)

type LocalAPIService struct {
	h http.Handler
}

func New(h http.Handler) *LocalAPIService {
	return &LocalAPIService{h: h}
}

// Call calls the given endpoint on the local API using the given HTTP method
// optionally sending the given body. It returns a Response representing the
// result of the call and an error if the call could not be completed or the
// local API returned a status code in the 400 series or greater.
// Note - Response includes a response body available from the Body method, it
// is the caller's responsibility to close this.
func (cl *LocalAPIService) Call(ctx context.Context, method, endpoint string, body io.Reader) (*Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, endpoint, body)
	if err != nil {
		return nil, fmt.Errorf("error creating new request for %s: %w", endpoint, err)
	}
	deadline, _ := ctx.Deadline()
	pipeReader, pipeWriter := net.Pipe()
	pipeReader.SetDeadline(deadline)
	pipeWriter.SetDeadline(deadline)

	resp := &Response{
		headers:          http.Header{},
		status:           http.StatusOK,
		bodyReader:       pipeReader,
		bodyWriter:       pipeWriter,
		startWritingBody: make(chan interface{}),
	}

	go func() {
		cl.h.ServeHTTP(resp, req)
		resp.Flush()
		pipeWriter.Close()
	}()

	select {
	case <-resp.startWritingBody:
		if resp.StatusCode() >= 400 {
			return resp, fmt.Errorf("request failed with status code %d", resp.StatusCode())
		}
		return resp, nil
	case <-ctx.Done():
		return nil, fmt.Errorf("timeout for %s", endpoint)
	}
}

func (s *LocalAPIService) GetBugReportID(ctx context.Context, bugReportChan chan<- string, fallbackLog string) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	r, err := s.Call(ctx, "POST", "/localapi/v0/bugreport", nil)
	defer r.Body().Close()

	if err != nil {
		log.Printf("get bug report: %s", err)
		bugReportChan <- fallbackLog
		return
	}
	logBytes, err := io.ReadAll(r.Body())
	if err != nil {
		log.Printf("read bug report: %s", err)
		bugReportChan <- fallbackLog
		return
	}
	bugReportChan <- string(logBytes)
}

func (s *LocalAPIService) Login(ctx context.Context, backend *ipnlocal.LocalBackend) {
	ctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	r, err := s.Call(ctx, "POST", "/localapi/v0/login-interactive", nil)
	defer r.Body().Close()

	if err != nil {
		log.Printf("login: %s", err)
		backend.StartLoginInteractive()
	}
}

func (s *LocalAPIService) Logout(ctx context.Context, backend *ipnlocal.LocalBackend) error {
	ctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	r, err := s.Call(ctx, "POST", "/localapi/v0/logout", nil)
	defer r.Body().Close()

	if err != nil {
		log.Printf("logout: %s", err)
		logoutctx, logoutcancel := context.WithTimeout(ctx, 5*time.Minute)
		defer logoutcancel()
		backend.Logout(logoutctx)
	}

	return err
}
