// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"runtime/debug"
	"sync"
	"time"
)

// CallLocalAPI calls the given endpoint on the local API using the given HTTP method
// optionally sending the given body. It returns a Response representing the
// result of the call and an error if the call could not be completed or the
// local API returned a status code in the 400 series or greater.
// Note - Response includes a response body available from the Body method, it
// is the caller's responsibility to close this.
func (app *App) CallLocalAPI(timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in CallLocalAPI %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	app.ready.Wait()

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(uint64(timeoutMillis)*uint64(time.Millisecond)))
	defer cancel()

	if body != nil {
		defer body.Close()
	}

	req, err := http.NewRequestWithContext(ctx, method, endpoint, adaptInputStream(body))
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
		defer func() {
			if p := recover(); p != nil {
				log.Printf("panic in CallLocalAPI.ServeHTTP %s: %s", p, debug.Stack())
				panic(p)
			}
		}()

		app.localAPIHandler.ServeHTTP(resp, req)
		resp.Flush()
		pipeWriter.Close()
	}()

	select {
	case <-resp.startWritingBody:
		return resp, nil
	case <-ctx.Done():
		return nil, fmt.Errorf("timeout for %s", endpoint)
	}
}

// func adaptInputStream(stream InputStream) io.ReadCloser {
// 	if stream == nil {
// 		return nil
// 	}

// 	reader, writer := io.Pipe()
// 	go func() {
// 		for {
// 			bytes, more, err := stream.Read()
// 			if bytes != nil {

// 			}
// 		}
// 	}
// }

// Response represents the result of processing an localAPI request.
// On completion, the response body can be read out of the bodyWriter.
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

// Write writes the data to the response body which an then be
// read out as a json object.
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

func (r *Response) BodyBytes() ([]byte, error) {
	return io.ReadAll(r.bodyReader)
}

func (r *Response) BodyInputStream() InputStream {
	return nil
}

func (r *Response) StatusCode() int {
	return r.status
}

func (r *Response) Flush() {
	r.startWritingBodyOnce.Do(func() {
		close(r.startWritingBody)
	})
}

func adaptInputStream(in InputStream) io.Reader {
	if in == nil {
		return nil
	}
	r, w := io.Pipe()
	go func() {
		defer w.Close()
		for {
			b, err := in.Read()
			if err != nil {
				log.Printf("error reading from inputstream: %s", err)
			}
			if b == nil {
				return
			}
			w.Write(b)
		}
	}()
	return r
}
