package localapiclient

import (
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"sync"

	"tailscale.com/ipn/localapi"
)

// Response represents the result of processing an http.Request.
type Response struct {
	headers              http.Header
	status               int
	bodyWriter           net.Conn
	bodyReader           net.Conn
	startWritingBody     chan interface{}
	startWritingBodyOnce sync.Once
}

func (r *Response) Header() http.Header {
	return r.headers
}

// Write writes the data to the response body and will send the data to Java.
func (r *Response) Write(data []byte) (int, error) {
	r.Flush()
	if r.status == 0 {
		r.WriteHeader(http.StatusOK)
	}
	return r.bodyWriter.Write(data)
}

func (r *Response) WriteHeader(statusCode int) {
	r.status = statusCode
}

func (r *Response) Body() net.Conn {
	return r.bodyReader
}

func (r *Response) StatusCode() int {
	return r.status
}

func (r *Response) Flush() {
	r.startWritingBodyOnce.Do(func() {
		close(r.startWritingBody)
	})
}

type LocalAPIClient struct {
	h *localapi.Handler
}

func New(h *localapi.Handler) *LocalAPIClient {
	return &LocalAPIClient{h: h}
}

// Call calls the given endpoint on the local API using the given HTTP method
// optionally sending the given body. It returns a Response representing the
// result of the call and an error if the call could not be completed or the
// local API returned a status code in the 400 series or greater.
// Note - Response includes a response body available from the Body method, it
// is the caller's responsibility to close this.
func (cl *LocalAPIClient) Call(ctx context.Context, method, endpoint string, body io.Reader) (*Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, "/localapi/v0/"+endpoint, body)
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
