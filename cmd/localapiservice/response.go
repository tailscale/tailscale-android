package localapiservice

import (
	"net"
	"net/http"
	"sync"
)

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

func (r *Response) StatusCode() int {
	return r.status
}

func (r *Response) Flush() {
	r.startWritingBodyOnce.Do(func() {
		close(r.startWritingBody)
	})
}
