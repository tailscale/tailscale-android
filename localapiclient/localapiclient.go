package localapiclient

import (
	"bytes"
	"fmt"
	"log"
	"net/http"
	"time"

	"tailscale.com/ipn/localapi"
)

// LocalApiResponseWriter is our custom implementation of http.ResponseWriter
type LocalApiResponseWriter struct {
	headers http.Header
	body    bytes.Buffer
	status  int
}

func newLocalApiResponseWriter() *LocalApiResponseWriter {
	return &LocalApiResponseWriter{headers: http.Header{}, status: http.StatusOK}
}

func (w *LocalApiResponseWriter) Header() http.Header {
	return w.headers
}

// Write writes the data to the response body and will send the data to Java.
func (w *LocalApiResponseWriter) Write(data []byte) (int, error) {
	if w.status == 0 {
		w.WriteHeader(http.StatusOK)
	}
	return w.body.Write(data)
}

func (w *LocalApiResponseWriter) WriteHeader(statusCode int) {
	w.status = statusCode
}

func (w *LocalApiResponseWriter) Body() []byte {
	return w.body.Bytes()
}

func (w *LocalApiResponseWriter) StatusCode() int {
	return w.status
}

type LocalApi interface {
	GetBugReportID(result chan<- string, h *localapi.Handler, fallbackLog string) (LocalApiResponseWriter, error)
}

type BugReportLocalApi struct{}

func (b BugReportLocalApi) GetBugReportID(bugReportChan chan<- string, h *localapi.Handler, fallbackLog string) (*LocalApiResponseWriter, error) {
	w := newLocalApiResponseWriter()
	req, err := http.NewRequest("POST", "/localapi/v0/bugreport", nil)
	if err != nil {
		log.Printf("error creating new request for bug report: %v", err)
		return w, err
	}
	h.ServeHTTP(w, req)
	if w.StatusCode() > 300 {
		err := fmt.Errorf("bug report bad http status: %v", w.StatusCode())
		log.Printf("%s", err)
		bugReportChan <- fallbackLog
		return w, err
	}
	report := string(w.Body())
	select {
	case bugReportChan <- report:
		err := fmt.Errorf("bug report was successfully retrieved: %s", report)
		log.Printf("%s", err)
		return w, err
	// timeout, send fallback log
	case <-time.After(2 * time.Second):
		err := fmt.Errorf("bug report retrieval timed out, sending fallback log: %s", fallbackLog)
		bugReportChan <- fallbackLog
		log.Printf("%s", err)
		return w, err
	}
}
