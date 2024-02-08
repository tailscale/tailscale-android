package localapiclient

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"time"

	"tailscale.com/ipn/localapi"
)

// Response represents the result of processing an http.Request.
type Response struct {
	headers    http.Header
	status     int
	bodyWriter io.WriteCloser
	bodyReader io.ReadCloser
}

func (r *Response) Header() http.Header {
	return r.headers
}

// Write writes the data to the response body, which will be sent to Java. If WriteHeader is not called
// explicitly, the first call to Write will trigger an implicit WriteHeader(http.StatusOK).
func (r *Response) Write(data []byte) (int, error) {
	if r.status == 0 {
		r.WriteHeader(http.StatusOK)
	}
	return r.bodyWriter.Write(data)
}

func (r *Response) WriteHeader(statusCode int) {
	r.status = statusCode
}

func (r *Response) Body() io.ReadCloser {
	return r.bodyReader
}

func (r *Response) StatusCode() int {
	return r.status
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
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, method, "/localapi/v0/"+endpoint, body)
	if err != nil {
		return nil, fmt.Errorf("error creating new request for %s: %w", endpoint, err)
	}
	pipeReader, pipeWriter := io.Pipe()
	defer pipeWriter.Close()

	resp := &Response{
		headers:    http.Header{},
		status:     http.StatusOK,
		bodyReader: pipeReader,
		bodyWriter: pipeWriter,
	}
	cl.h.ServeHTTP(resp, req)
	if resp.StatusCode() >= 400 {
		return resp, fmt.Errorf("request failed with status code %d", resp.StatusCode())
	}
	return resp, nil
}
