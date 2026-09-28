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
	// readDone is notified when the read goroutine is done.
	readDone chan struct{}
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

	var devices []*tunDevice
	// readDone is the readDone channel of the device being read from.
	var readDone chan struct{}
	for {
		select {
		case <-readDone:
			// The oldest device has reached EOF, replace it.
			n := copy(devices, devices[1:])
			devices = devices[:n]
			if len(devices) > 0 {
				// Start reading from the next device.
				dev := devices[0]
				readDone = dev.readDone
				go d.readFrom(dev)
			}
		case <-d.shutdowns:
			// Shut down all devices. Only the newest is still open;
			// older ones were asked to stop when superseded, and only
			// the oldest is being read from.
			if len(devices) > 0 {
				close(devices[len(devices)-1].close)
				for _, dev := range devices {
					<-dev.closeDone
				}
				<-devices[0].readDone
			}
			devices = nil
			d.shutdownDone <- struct{}{}
		case <-d.close:
			var derr error
			for _, dev := range devices {
				if err := <-dev.closeDone; err != nil {
					derr = err
				}
			}
			d.closeErr <- derr
			return
		case dev := <-d.devices:
			if len(devices) > 0 {
				// Ask the most recent device to stop.
				prev := devices[len(devices)-1]
				close(prev.close)
			}
			wrap := &tunDevice{
				dev:       dev,
				close:     make(chan struct{}),
				closeDone: make(chan error, 1),
				readDone:  make(chan struct{}, 1),
			}
			if len(devices) == 0 {
				// Start reading from this first device.
				readDone = wrap.readDone
				go d.readFrom(wrap)
			}
			// Write to the new device right away rather than after prev
			// finishes closing: closing a TUN can block in the kernel, and
			// another device may be added meanwhile, which would then never
			// be run or closed, leaving reads stuck on it.
			go d.runDevice(wrap)
			devices = append(devices, wrap)
		case m := <-d.mtus:
			r := mtuReply{mtu: d.mtu}
			if len(devices) > 0 {
				dev := devices[len(devices)-1]
				r.mtu, r.err = dev.dev.MTU()
			}
			m <- r
		case n := <-d.names:
			var r nameReply
			if len(devices) > 0 {
				dev := devices[len(devices)-1]
				r.name, r.err = dev.dev.Name()
			}
			n <- r
		}
	}
}

func (d *Device) readFrom(dev *tunDevice) {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in multiTUN.readFrom %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	defer func() {
		dev.readDone <- struct{}{}
	}()
	for {
		select {
		case r := <-d.reads:
			n, err := dev.dev.Read(r.slab, r.packets)
			stop := false
			if err != nil {
				select {
				case <-dev.close:
					stop = true
					err = nil
				default:
				}
			}
			r.reply <- ioReply{n, err}
			if stop {
				return
			}
		case <-d.close:
			return
		}
	}
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
