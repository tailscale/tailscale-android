// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"errors"
	"sync/atomic"
	"testing"
	"time"
)

func TestConfigureVPNFailure(t *testing.T) {
	for _, fromEngine := range []bool{false, true} {
		t.Run(map[bool]string{false: "startup", true: "config-update"}[fromEngine], func(t *testing.T) {
			failure := errors.New("early VPN setup failure")
			var reply chan error
			if fromEngine {
				reply = make(chan error)
			}
			stopped := make(chan error)
			done := make(chan struct{})
			cancel := make(chan struct{})
			defer close(cancel)
			go func() {
				defer close(done)
				configureVPN(func() error { return failure }, reply, func(err error) {
					select {
					case stopped <- err:
					case <-cancel:
					}
				})
			}()
			if fromEngine {
				// Cleanup must not prevent the engine from receiving its configuration result.
				if got := awaitVPNResult(t, reply); got != failure {
					t.Fatalf("configuration result = %v, want %v", got, failure)
				}
			}
			if got := awaitVPNResult(t, stopped); got != failure {
				t.Fatalf("stop reason = %v, want %v", got, failure)
			}
			<-done
		})
	}
}

func TestConfigureVPNSuccessDoesNotStopService(t *testing.T) {
	reply := make(chan error, 1)
	configureVPN(func() error { return nil }, reply, func(err error) {
		t.Fatalf("successful configuration stopped the VPN: %v", err)
	})
	if err := <-reply; err != nil {
		t.Fatalf("configuration result = %v, want nil", err)
	}
}

func TestStopVPNOnFailureAllowsBackendReconfiguration(t *testing.T) {
	failure := errors.New("early VPN setup failure")
	service := &testVPNDisconnector{disconnected: make(chan string, 1)}
	configRequests := make(chan chan struct{})
	cancel := make(chan struct{})
	loopDone := make(chan struct{})
	defer close(cancel)
	go func() {
		defer close(loopDone)
		stopVPNOnFailure(service, failure, func() error {
			// Changing WantRunning to false synchronously reconfigures the engine.
			ack := make(chan struct{})
			select {
			case configRequests <- ack:
			case <-cancel:
				return nil
			}
			select {
			case <-ack:
			case <-cancel:
			}
			return nil
		})
		// The VPN loop must remain available to process that configuration update.
		select {
		case ack := <-configRequests:
			close(ack)
		case <-cancel:
		}
	}()
	select {
	case reason := <-service.disconnected:
		if reason != failure.Error() {
			t.Fatalf("disconnect reason = %q, want %q", reason, failure.Error())
		}
	case <-time.After(5 * time.Second):
		t.Fatal("VPN loop blocked while stopping the backend; retries cannot be processed")
	}
	<-loopDone
}

func TestConfigureVPNRetryAfterFailure(t *testing.T) {
	for _, fromEngine := range []bool{false, true} {
		t.Run(map[bool]string{false: "startup", true: "config-update"}[fromEngine], func(t *testing.T) {
			failure := errors.New("early VPN setup failure")
			service := &testVPNDisconnector{disconnected: make(chan string, 1)}
			var firstReply chan error
			if fromEngine {
				firstReply = make(chan error)
			}
			stopConfig := make(chan chan error)
			retry := make(chan struct{})
			retryReply := make(chan error)
			loopDone := make(chan struct{})
			cancel := make(chan struct{})
			defer close(cancel)
			go func() {
				defer close(loopDone)
				configureVPN(func() error { return failure }, firstReply, func(err error) {
					stopVPNOnFailure(service, err, func() error {
						// EditPrefs waits for the loop to apply the stopped configuration.
						reply := make(chan error, 1)
						select {
						case stopConfig <- reply:
						case <-cancel:
							return nil
						}
						select {
						case err := <-reply:
							return err
						case <-cancel:
							return nil
						}
					})
				})
				select {
				case reply := <-stopConfig:
					configureVPN(func() error { return nil }, reply, func(error) {})
				case <-cancel:
					return
				}
				select {
				case <-retry:
					configureVPN(func() error { return nil }, retryReply, func(error) {})
				case <-cancel:
				}
			}()
			if fromEngine {
				if got := awaitVPNResult(t, firstReply); got != failure {
					t.Fatalf("first configuration result = %v, want %v", got, failure)
				}
			}
			select {
			case reason := <-service.disconnected:
				if reason != failure.Error() {
					t.Fatalf("disconnect reason = %q, want %q", reason, failure.Error())
				}
			case <-time.After(5 * time.Second):
				t.Fatal("failed VPN did not stop; backend reconfiguration is blocked")
			}
			close(retry)
			if err := awaitVPNResult(t, retryReply); err != nil {
				t.Fatalf("retry configuration result = %v, want nil", err)
			}
			<-loopDone
		})
	}
}

func TestStopVPNOnFailureStillDisconnectsWhenBackendStopFails(t *testing.T) {
	failure := errors.New("early VPN setup failure")
	service := &testVPNDisconnector{disconnected: make(chan string, 1)}
	stopVPNOnFailure(service, failure, func() error {
		return errors.New("could not update preferences")
	})
	select {
	case reason := <-service.disconnected:
		if reason != failure.Error() {
			t.Fatalf("disconnect reason = %q, want %q", reason, failure.Error())
		}
	case <-time.After(5 * time.Second):
		t.Fatal("failed service was not disconnected")
	}
}

func TestDelayedVPNFailureDoesNotClearRetryStatus(t *testing.T) {
	var active atomic.Bool
	active.Store(true)
	service := &testVPNDisconnector{
		disconnected: make(chan string, 1),
		status:       active.Store,
	}
	stopBackend := make(chan struct{})
	defer close(stopBackend)
	failure := errors.New("early VPN setup failure")
	stopVPNOnFailure(service, failure, func() error {
		<-stopBackend
		return nil
	})
	if active.Load() {
		t.Fatal("failed VPN remained active while the backend was stopping")
	}

	// A new service connects before the failed service finishes disconnecting.
	active.Store(true)
	stopBackend <- struct{}{}
	select {
	case <-service.disconnected:
		if !active.Load() {
			t.Fatal("delayed failure cleanup cleared the retry's active status")
		}
	case <-time.After(5 * time.Second):
		t.Fatal("failed service did not disconnect")
	}
}

type testVPNDisconnector struct {
	disconnected chan string
	status       func(bool)
}

func (s *testVPNDisconnector) UpdateVpnStatus(active bool) {
	if s.status != nil {
		s.status(active)
	}
}

func (s *testVPNDisconnector) DisconnectVPN(reason string) {
	s.disconnected <- reason
}

func awaitVPNResult(t *testing.T, results <-chan error) error {
	t.Helper()
	select {
	case err := <-results:
		return err
	case <-time.After(5 * time.Second):
		t.Fatal("VPN configuration result or failure cleanup was blocked")
		return nil
	}
}
