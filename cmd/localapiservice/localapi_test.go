package localapiservice

import (
	"context"
	"io"
	"net/http"
	"testing"
	"time"
)

var ctx = context.Background()

type BadStatusHandler struct{}

func (b *BadStatusHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.WriteHeader(http.StatusBadRequest)
}

func TestBadStatus(t *testing.T) {
	ctx, cancel := context.WithDeadline(ctx, time.Now().Add(2*time.Second))
	client := New(&BadStatusHandler{})
	defer cancel()

	_, err := client.Call(ctx, "POST", "test", nil)

	if err.Error() != "request failed with status code 400" {
		t.Error("Expected bad status error, but got", err)
	}
}

type TimeoutHandler struct{}

var successfulResponse = "successful response!"

func (b *TimeoutHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	time.Sleep(6 * time.Second)
	w.Write([]byte(successfulResponse))
}

func TestTimeout(t *testing.T) {
	ctx, cancel := context.WithDeadline(ctx, time.Now().Add(2*time.Second))
	client := New(&TimeoutHandler{})
	defer cancel()

	_, err := client.Call(ctx, "GET", "test", nil)

	if err.Error() != "timeout for test" {
		t.Error("Expected timeout error, but got", err)
	}
}

type SuccessfulHandler struct{}

func (b *SuccessfulHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Write([]byte(successfulResponse))
}

func TestSuccess(t *testing.T) {
	ctx, cancel := context.WithDeadline(ctx, time.Now().Add(2*time.Second))
	client := New(&SuccessfulHandler{})
	defer cancel()

	w, err := client.Call(ctx, "GET", "test", nil)

	if err != nil {
		t.Error("Expected no error, but got", err)
	}

	report, err := io.ReadAll(w.Body())
	if string(report) != successfulResponse {
		t.Error("Expected successful report, but got", report)
	}
}
