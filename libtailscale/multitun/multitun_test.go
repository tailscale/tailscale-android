// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package multitun

import (
	"os"
	"sync"
	"testing"
	"time"

	"github.com/tailscale/wireguard-go/tun"
)

// fakeTUN models an Android VPN fd. in holds packets the OS routes to it,
// out collects packets written to it.
type fakeTUN struct {
	name      string
	in        chan []byte
	out       chan []byte
	closing   chan struct{}
	closeOnce sync.Once
	// release gates Close. Closing a TUN fd unregisters the netdev, which
	// can block in the kernel waiting for references to drop.
	release chan struct{}
}

func newFakeTUN(name string) *fakeTUN {
	release := make(chan struct{})
	close(release)
	return &fakeTUN{
		name:    name,
		in:      make(chan []byte, 1),
		out:     make(chan []byte, 1),
		closing: make(chan struct{}),
		release: release,
	}
}

func (f *fakeTUN) File() *os.File { return nil }

func (f *fakeTUN) Read(slab []byte, packets []tun.ReadPacket) (int, error) {
	select {
	case p := <-f.in:
		n := copy(slab[tun.ReadPacketSpacing:], p)
		packets[0] = tun.ReadPacket{Offset: tun.ReadPacketSpacing, Size: n}
		return 1, nil
	case <-f.closing:
		// A Read pending at Close drops the fd's last reference, so it
		// runs close(2) itself (see internal/poll.FD.readUnlock).
		<-f.release
		return 0, os.ErrClosed
	}
}

func (f *fakeTUN) Write(bufs [][]byte, offset int) (int, error) {
	for _, b := range bufs {
		f.out <- append([]byte(nil), b[offset:]...)
	}
	return len(bufs), nil
}

func (f *fakeTUN) MTU() (int, error)        { return 1280, nil }
func (f *fakeTUN) Name() (string, error)    { return f.name, nil }
func (f *fakeTUN) Events() <-chan tun.Event { return nil }
func (f *fakeTUN) BatchSize() int           { return 1 }

func (f *fakeTUN) Close() error {
	f.closeOnce.Do(func() { close(f.closing) })
	<-f.release
	return nil
}

const testTimeout = 5 * time.Second

// readLoop reads from d like wireguard-go, which always has a Read pending.
func readLoop(d *Device) <-chan string {
	packets := make(chan string)
	go func() {
		slab := make([]byte, 2048)
		p := make([]tun.ReadPacket, 1)
		for {
			n, err := d.Read(slab, p)
			if err != nil {
				return
			}
			if n > 0 {
				packets <- string(slab[p[0].Offset : p[0].Offset+p[0].Size])
			}
		}
	}()
	return packets
}

func waitClosed(t *testing.T, f *fakeTUN) {
	t.Helper()
	select {
	case <-f.closing:
	case <-time.After(testTimeout):
		t.Fatalf("TUN %s never closed", f.name)
	}
}

// Android can reconfigure the VPN again while the previous TUN is still
// being closed. Every superseded TUN must be closed and traffic must end
// up on the newest one, which is the only one Android routes to.
func TestMultiTUNReplaceWhileOldTUNClosing(t *testing.T) {
	d := New(1280)
	reads := readLoop(d)
	a, b, c := newFakeTUN("a"), newFakeTUN("b"), newFakeTUN("c")
	a.release = make(chan struct{})

	d.Add(a)
	d.Up()
	d.Add(b)
	waitClosed(t, a) // a's Close is now blocked
	d.Add(c)
	close(a.release)
	waitClosed(t, b)

	c.in <- []byte("outbound")
	select {
	case got := <-reads:
		if got != "outbound" {
			t.Fatalf("Read = %q, want %q", got, "outbound")
		}
	case <-time.After(testTimeout):
		t.Fatal("Read returned no packet")
	}

	if _, err := d.Write([][]byte{[]byte("inbound")}, 0); err != nil {
		t.Fatal(err)
	}
	select {
	case got := <-c.out:
		if string(got) != "inbound" {
			t.Fatalf("c got %q, want %q", got, "inbound")
		}
	case <-time.After(testTimeout):
		t.Fatal("write did not reach c")
	}

	shutdown := make(chan struct{})
	go func() {
		d.Shutdown()
		close(shutdown)
	}()
	select {
	case <-shutdown:
	case <-time.After(testTimeout):
		t.Fatal("Shutdown hung")
	}
	waitClosed(t, c)
}

// Traffic must flow on the new TUN while the old one is still closing,
// even though a Read pending on the old one only returns once it is closed.
func TestMultiTUNReplaceWhileOldTUNClosingForever(t *testing.T) {
	d := New(1280)
	reads := readLoop(d)
	a, b := newFakeTUN("a"), newFakeTUN("b")
	a.release = make(chan struct{})
	defer close(a.release)

	d.Add(a)
	d.Up()
	d.Add(b)
	waitClosed(t, a) // a's Close is now blocked

	go d.Write([][]byte{[]byte("inbound")}, 0)
	select {
	case got := <-b.out:
		if string(got) != "inbound" {
			t.Fatalf("b got %q, want %q", got, "inbound")
		}
	case <-time.After(testTimeout):
		t.Fatal("write did not reach b")
	}

	b.in <- []byte("outbound")
	select {
	case got := <-reads:
		if got != "outbound" {
			t.Fatalf("Read = %q, want %q", got, "outbound")
		}
	case <-time.After(testTimeout):
		t.Fatal("Read returned no packet from b")
	}
}
