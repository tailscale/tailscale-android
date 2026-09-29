// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package multitun

import (
	"log"
	"os"
	"runtime/debug"
	"sync"

	"github.com/tailscale/wireguard-go/tun"
	"tailscale.com/syncs"
)

// Device implements a tun.Device that supports multiple
// underlying devices. This is necessary because Android VPN devices
// have static configurations and wgengine.NewUserspaceEngine
// assumes a single static tun.Device.
type Device struct {
	// devices is for adding new devices.
	devices chan tun.Device
	// event is the combined event channel from all active devices.
	events chan tun.Event
	// mtu is reported while there is no underlying device.
	mtu int

	close    chan struct{}
	closeErr chan error

	reads        chan readRequest
	writes       chan writeRequest
	mtus         chan chan mtuReply
	names        chan chan nameReply
	shutdowns    chan struct{}
	shutdownDone chan struct{}

	downMu sync.Mutex
	// downCh is closed when the multiTUN is brought down,
	// such as when Tailscale transitions to the Stopped state.
	// This indicates that all outgoing packets should be dropped,
	// and [Device.Write] should return immediately without blocking
	// until [Device.Up] is called. See [Device.Down]
	// and tailscale/tailscale#18679 for more details.
	//
	// It can be read without holding downMu, but the mutex
	// must be held when writing.
	downCh syncs.AtomicValue[chan struct{}]
	down   bool // whether the downCh is closed
}

// tunDevice wraps and drives a single tun.Device.
type tunDevice struct {
	dev tun.Device
	// close closes the device.
	close     chan struct{}
	closeDone chan error
}

type readRequest struct {
	slab    []byte
	packets []tun.ReadPacket
	reply   chan<- ioReply
}

type writeRequest struct {
	data   [][]byte
	offset int
	reply  chan<- ioReply
}

type ioReply struct {
	count int
	err   error
}

type mtuReply struct {
	mtu int
	err error
}

type nameReply struct {
	name string
	err  error
}

// New returns a Device with no underlying devices, initially down.
func New(mtu int) *Device {
	d := &Device{
		devices:      make(chan tun.Device),
		events:       make(chan tun.Event),
		mtu:          mtu,
		close:        make(chan struct{}),
		closeErr:     make(chan error),
		reads:        make(chan readRequest),
		writes:       make(chan writeRequest),
		mtus:         make(chan chan mtuReply),
		names:        make(chan chan nameReply),
		shutdowns:    make(chan struct{}),
		shutdownDone: make(chan struct{}),
		down:         true, // The device is initially down.
	}
	downCh := make(chan struct{})
	d.downCh.Store(downCh)
	close(downCh)
	go d.run()
	return d
}

func (d *Device) run() {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in multiTUN.run %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	// cur is the newest device, the only one Android routes to. Older
	// devices close themselves and their goroutines exit once superseded.
	var cur *tunDevice
	for {
		select {
		case <-d.shutdowns:
			if cur != nil {
				close(cur.close)
				<-cur.closeDone
				cur = nil
			}
			d.shutdownDone <- struct{}{}
		case <-d.close:
			var err error
			if cur != nil {
				err = <-cur.closeDone
			}
			d.closeErr <- err
			return
		case dev := <-d.devices:
			if cur != nil {
				close(cur.close)
			}
			cur = &tunDevice{
				dev:       dev,
				close:     make(chan struct{}),
				closeDone: make(chan error, 1),
			}
			// Use the new device right away rather than after the old one
			// finishes closing, which can block in the kernel.
			go d.readFrom(cur)
			go d.runDevice(cur)
		case m := <-d.mtus:
			r := mtuReply{mtu: d.mtu}
			if cur != nil {
				r.mtu, r.err = cur.dev.MTU()
			}
			m <- r
		case n := <-d.names:
			var r nameReply
			if cur != nil {
				r.name, r.err = cur.dev.Name()
			}
			n <- r
		}
	}
}

// readBufSize fits any IP packet plus the spacing tun.Device.Read reserves.
const readBufSize = 1<<16 + 2*tun.ReadPacketSpacing

// readFrom reads dev into its own buffer and hands each result to a pending
// [Device.Read]. Reading into the caller's slab instead would let a closing
// device hold the caller's only Read: a Read pending when the fd is closed
// runs close(2) itself, which can block in the kernel.
func (d *Device) readFrom(dev *tunDevice) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in multiTUN.readFrom %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	slab := make([]byte, readBufSize)
	packets := make([]tun.ReadPacket, 1)
	for {
		n, err := dev.dev.Read(slab, packets)
		if err != nil {
			select {
			case <-dev.close:
				// Closed after being superseded or shut down.
				return
			default:
			}
		}
		select {
		case r := <-d.reads:
			r.reply <- copyRead(r, slab, packets[:n], err)
		case <-d.close:
			return
		}
	}
}

// copyRead copies packets read into slab over to r.
func copyRead(r readRequest, slab []byte, packets []tun.ReadPacket, err error) ioReply {
	off := tun.ReadPacketSpacing
	for i, p := range packets {
		if i == len(r.packets) || off+p.Size+tun.ReadPacketSpacing > len(r.slab) {
			return ioReply{i, tun.ErrTooManySegments}
		}
		copy(r.slab[off:], slab[p.Offset:p.Offset+p.Size])
		r.packets[i] = tun.ReadPacket{Offset: off, Size: p.Size}
		off += p.Size + tun.ReadPacketSpacing
	}
	return ioReply{len(packets), err}
}

func (d *Device) runDevice(dev *tunDevice) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in multiTUN.runDevice %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	defer func() {
		// The documentation for https://developer.android.com/reference/android/net/VpnService.Builder#establish()
		// states that "Therefore, after draining the old file
		// descriptor...", but pending Reads are never unblocked
		// when a new descriptor is created.
		//
		// Close it instead and hope that no packets are lost.
		dev.closeDone <- dev.dev.Close()
	}()
	// Pump device events.
	go func() {
		defer func() {
			if p := recover(); p != nil {
				log.Printf("panic in multiTUN.readFrom.events %s: %s", p, debug.Stack())
				panic(p)
			}
		}()
		for {
			select {
			case e := <-dev.dev.Events():
				d.events <- e
			case <-dev.close:
				return
			}
		}
	}()
	for {
		select {
		case w := <-d.writes:
			n, err := dev.dev.Write(w.data, w.offset)
			w.reply <- ioReply{n, err}
		case <-dev.close:
			// Device closed.
			return
		case <-d.close:
			// Multi-device closed.
			return
		}
	}
}

func (d *Device) Add(dev tun.Device) {
	d.devices <- dev
}

// Up brings the multiTUN up, allowing it to write packets
// to the underlying tunnel device. If there is no underlying
// device yet, write operations are pended until a new device
// is added with [Device.Add].
//
// It reports whether this call brought the device up.
func (d *Device) Up() bool {
	d.downMu.Lock()
	defer d.downMu.Unlock()
	if !d.down {
		return false
	}
	d.downCh.Store(make(chan struct{}))
	d.down = false
	return true
}

// Down brings the multiTUN down, causing all outgoing packets
// to be dropped without waiting for the underlying tunnel device,
// and makes all [Device.Write] calls return immediately
// until [Device.Up] is called.
//
// It mainly exists to distinguish between cases where the underlying
// device is temporarily unavailable due to VPN reconfiguration,
// in which case write requests should be pended, and cases where
// Tailscale is stopped, where any pending and new requests
// should complete immediately to prevent deadlocks.
// See tailscale/tailscale#18679.
//
// It reports whether this call brought the device down.
func (d *Device) Down() bool {
	d.downMu.Lock()
	defer d.downMu.Unlock()
	if d.down {
		return false
	}
	close(d.downCh.Load())
	d.down = true
	return true
}

func (d *Device) File() *os.File {
	// The underlying file descriptor is not constant on Android.
	// Let's hope no-one uses it.
	panic("not available on Android")
}

func (d *Device) Read(slab []byte, packets []tun.ReadPacket) (int, error) {
	r := make(chan ioReply)
	select {
	// We don't care about d.downCh here, as it's fine
	// to continue waiting until the tunnel is up again
	// or the multiTUN device is permanently closed.
	// This does not block WireGuard reconfiguration.
	case d.reads <- readRequest{
		slab:    slab,
		packets: packets,
		reply:   r,
	}:
		rep := <-r
		return rep.count, rep.err
	case <-d.close:
		// Return immediately if the multiTUN device is closed.
		return 0, os.ErrClosed
	}
}

func (d *Device) Write(data [][]byte, offset int) (int, error) {
	r := make(chan ioReply)
	select {
	case d.writes <- writeRequest{
		data:   data,
		offset: offset,
		reply:  r,
	}:
		rep := <-r
		return rep.count, rep.err
	case <-d.downCh.Load():
		// Drop the packet silently if the tunnel is down.
		// Otherwise, a race may occur during wireguard reconfig
		// and result in a deadlock, since a wireguard-go/device.Peer
		// cannot be removed until its RoutineSequentialReceiver
		// returns, and it will not return if it is blocked in
		// (*Device).Write while sending to d.writes without
		// a receiver on the other side of the pipe.
		return 0, nil
	case <-d.close:
		// Return immediately if the multiTUN device is closed.
		return 0, os.ErrClosed
	}
}

func (d *Device) MTU() (int, error) {
	r := make(chan mtuReply)
	d.mtus <- r
	rep := <-r
	return rep.mtu, rep.err
}

func (d *Device) Name() (string, error) {
	r := make(chan nameReply)
	d.names <- r
	rep := <-r
	return rep.name, rep.err
}

func (d *Device) Events() <-chan tun.Event {
	return d.events
}

func (d *Device) Shutdown() {
	d.shutdowns <- struct{}{}
	<-d.shutdownDone
}

func (d *Device) Close() error {
	close(d.close)
	return <-d.closeErr
}

func (d *Device) BatchSize() int {
	// TODO(raggi): currently Android disallows the necessary ioctls to enable
	// batching. File a bug.
	return 1
}
