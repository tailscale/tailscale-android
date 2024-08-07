// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"maps"
	"mime/multipart"
	"net"
	"net/http"
	"net/textproto"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn"
)

// CallLocalAPI is the method for making localapi calls from Kotlin. It calls
// the given endpoint on the local API using the given HTTP method and
// optionally sending the given body. It returns a Response representing the
// result of the call and an error if the call could not be completed or the
// local API returned a status code in the 400 series or greater.
// Note - Response includes a response body available from the Body method, it
// is the caller's responsibility to close this.
func (app *App) CallLocalAPI(timeoutMillis int, method, endpoint string, body InputStream) (LocalAPIResponse, error) {
	return app.callLocalAPI(timeoutMillis, method, endpoint, nil, adaptInputStream(body))
}

// CallLocalAPIMultipart is like CallLocalAPI, but instead of uploading a
// generic body, it uploads a multipart/form-encoded body consisting of the
// supplied parts.
func (app *App) CallLocalAPIMultipart(timeoutMillis int, method, endpoint string, parts FileParts) (LocalAPIResponse, error) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in CallLocalAPIMultipart %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	r, w := io.Pipe()
	defer r.Close()

	mw := multipart.NewWriter(w)
	header := make(http.Header)
	header.Set("Content-Type", mw.FormDataContentType())
	resultCh := make(chan interface{})
	go func() {
		resp, err := app.callLocalAPI(timeoutMillis, method, endpoint, header, r)
		if err != nil {
			resultCh <- err
		} else {
			resultCh <- resp
		}
	}()

	go func() {
		for i := int32(0); i < parts.Len(); i++ {
			part := parts.Get(i)
			contentType := "application/octet-stream"
			if part.ContentType != "" {
				contentType = part.ContentType
			}
			header := make(textproto.MIMEHeader, 3)
			header.Set("Content-Disposition",
				fmt.Sprintf(`form-data; name="%s"; filename="%s"`,
					escapeQuotes("file"), escapeQuotes(part.Filename)))
			header.Set("Content-Type", contentType)
			header.Set("Content-Length", strconv.FormatInt(part.ContentLength, 10))
			p, err := mw.CreatePart(header)
			if err != nil {
				resultCh <- fmt.Errorf("CreatePart: %w", err)
				return
			}
			_, err = io.Copy(p, adaptInputStream(part.Body))
			if err != nil {
				resultCh <- fmt.Errorf("Copy: %w", err)
				return
			}
		}

		err := mw.Close()
		if err != nil {
			resultCh <- fmt.Errorf("Close MultipartWriter: %w", err)
		}
		err = w.Close()
		if err != nil {
			resultCh <- fmt.Errorf("Close Writer: %w", err)
		}
	}()

	result := <-resultCh
	switch t := result.(type) {
	case LocalAPIResponse:
		return t, nil
	case error:
		return nil, t
	default:
		panic("unexpected result type, this shouldn't happen")
	}
}

func (app *App) NotifyPolicyChanged() {
	app.policyStore.notifyChanged()
}

func (app *App) EditPrefs(prefs ipn.MaskedPrefs) (LocalAPIResponse, error) {
	r, w := io.Pipe()
	go func() {
		defer w.Close()
		enc := json.NewEncoder(w)
		if err := enc.Encode(prefs); err != nil {
			log.Printf("Error encoding preferences: %v", err)
		}
	}()
	return app.callLocalAPI(30000, "PATCH", "prefs", nil, r)
}

func (app *App) callLocalAPI(timeoutMillis int, method, endpoint string, header http.Header, body io.ReadCloser) (LocalAPIResponse, error) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in callLocalAPI %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	app.ready.Wait()

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(uint64(timeoutMillis)*uint64(time.Millisecond)))
	defer cancel()

	if body != nil {
		defer body.Close()
	}

	req, err := http.NewRequestWithContext(ctx, method, endpoint, body)
	maps.Copy(req.Header, header)
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

		defer pipeWriter.Close()
		app.localAPIHandler.ServeHTTP(resp, req)
		resp.Flush()
	}()

	select {
	case <-resp.startWritingBody:
		return resp, nil
	case <-ctx.Done():
		return nil, fmt.Errorf("timeout for %s", endpoint)
	}
}

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

func adaptInputStream(in InputStream) io.ReadCloser {
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

// Below taken from Go stdlib
var quoteEscaper = strings.NewReplacer("\\", "\\\\", `"`, "\\\"")

func escapeQuotes(s string) string {
	return quoteEscaper.Replace(s)
}
