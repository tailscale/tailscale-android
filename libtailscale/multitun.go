// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"log"
	"os"
	"runtime/debug"
	"sync"

	"github.com/tailscale/wireguard-go/tun"
	"tailscale.com/syncs"
)

// multiTUN implements a tun.Device that supports multiple
// underlying devices. This is necessary because Android VPN devices
// have static configurations and wgengine.NewUserspaceEngine
// assumes a single static tun.Device.
type multiTUN struct {
	// devices is for adding new devices.
	devices chan tun.Device
	// event is the combined event channel from all active devices.
	events chan tun.Event

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
	// and [multiTUN.Write] should return immediately without blocking
	// until [multiTUN.Up] is called. See [multiTUN.Down]
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
	// close is closed by stop to ask the goroutines to exit.
	close chan struct{}
	// closeDone is written by runDevice when it exits, carrying the
	// result of closing the underlying device.
	closeDone chan error
	// readDone is notified when the read goroutine is done.
	readDone chan struct{}
	// closeOnce guards close, which is closed both when the device is
	// superseded by a newer one and again during shutdown.
	closeOnce sync.Once
	// reading reports whether readFrom is running for this device.
	reading bool
	// writing reports whether runDevice is running for this device.
	writing bool
}

func (t *tunDevice) stop() {
	t.closeOnce.Do(func() { close(t.close) })
}

// stopped reports whether stop has been called.
func (t *tunDevice) stopped() bool {
	select {
	case <-t.close:
		return true
	default:
		return false
	}
}

// idle reports whether no goroutine is attached to the device.
func (t *tunDevice) idle() bool {
	return !t.reading && !t.writing
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

func newTUNDevices() *multiTUN {
	d := &multiTUN{
		devices:      make(chan tun.Device),
		events:       make(chan tun.Event),
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

func (d *multiTUN) run() {
	defer func() {
		if p := recover(); p != nil {
			log.Printf("panic in multiTUN.run %s: %s", p, debug.Stack())
			panic(p)
		}
	}()

	// devices is the queue of underlying devices, oldest first. The last
	// element is the live device.
	var devices []*tunDevice
	// reader and writer are the devices with a readFrom and a runDevice
	// goroutine attached; readDone and runDone are their completion
	// channels. Either may be nil while a goroutine is being replaced.
	var (
		reader   *tunDevice
		readDone chan struct{}
		writer   *tunDevice
		runDone  chan error
	)

	// newest returns the live device, or nil if there is none.
	newest := func() *tunDevice {
		if len(devices) == 0 {
			return nil
		}
		return devices[len(devices)-1]
	}
	startRead := func(dev *tunDevice) {
		dev.reading = true
		reader = dev
		readDone = dev.readDone
		go d.readFrom(dev)
	}
	startWrite := func(dev *tunDevice) {
		dev.writing = true
		writer = dev
		runDone = dev.closeDone
		go d.runDevice(dev)
	}
	// prune drops every superseded device that has no goroutine left,
	// closing its underlying device so the kernel interface goes away.
	// Closing is idempotent, so a device already closed by runDevice is
	// fine to close again.
	prune := func() {
		kept := devices[:0]
		for _, dev := range devices {
			if dev.stopped() && dev.idle() {
				dev.dev.Close()
				continue
			}
			kept = append(kept, dev)
		}
		for i := len(kept); i < len(devices); i++ {
			devices[i] = nil
		}
		devices = kept
	}
	// release stops dev and, if no writer will do it on exit, closes the
	// underlying device now. Closing unblocks a reader parked in Read,
	// which is the only way out of a Read on a DOWN interface.
	release := func(dev *tunDevice) {
		dev.stop()
		if !dev.writing {
			dev.dev.Close()
		}
	}

	for {
		select {
		case <-readDone:
			// The reader's device is done: it was superseded and its
			// underlying device was closed. Move reading to the live
			// device, skipping anything superseded in between.
			reader.reading = false
			reader, readDone = nil, nil
			prune()
			if dev := newest(); dev != nil {
				startRead(dev)

			}
		case <-runDone:
			// The writer's device is done and runDevice has closed it.
			// Move writing to the live device.
			writer.writing = false
			writer, runDone = nil, nil
			prune()
			if dev := newest(); dev != nil {
				startWrite(dev)
			}
		case <-d.shutdowns:
			// Shut down all devices.
			for _, dev := range devices {
				release(dev)
				if dev.writing {
					<-dev.closeDone
					dev.writing = false
				}
				if dev.reading {
					<-dev.readDone
					dev.reading = false
				}


			}
			devices = nil
			reader, readDone = nil, nil
			writer, runDone = nil, nil
			d.shutdownDone <- struct{}{}
		case <-d.close:
			var derr error
			for _, dev := range devices {
				release(dev)
				if dev.writing {
					if err := <-dev.closeDone; err != nil {
						derr = err
					}
					continue
				}
				if err := dev.dev.Close(); err != nil {
					derr = err
				}
			}
			d.closeErr <- derr
			return
		case dev := <-d.devices:
			// Android has already reset the previous interface; it will
			// never carry traffic again. Ask its goroutines to stop and
			// make sure it gets closed even if none were ever attached.
			if prev := newest(); prev != nil {
				release(prev)








			}
			wrap := &tunDevice{
				dev:       dev,
				close:     make(chan struct{}),
				closeDone: make(chan error),
				readDone:  make(chan struct{}, 1),
			}








			devices = append(devices, wrap)
			prune()
			if reader == nil {
				startRead(wrap)
			}
			if writer == nil {
				startWrite(wrap)
			}
		case m := <-d.mtus:
			r := mtuReply{mtu: defaultMTU}
			if dev := newest(); dev != nil {

				r.mtu, r.err = dev.dev.MTU()
			}
			m <- r
		case n := <-d.names:
			var r nameReply
			if dev := newest(); dev != nil {

				r.name, r.err = dev.dev.Name()
			}
			n <- r
		}
	}
}

func (d *multiTUN) readFrom(dev *tunDevice) {
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
			if err != nil && dev.stopped() {
				// The device was superseded and closed under us; this is
				// not an error of the multiTUN, just the end of this
				// device. Report an empty read and hand over.
				stop = true
				err = nil

			}
			r.reply <- ioReply{n, err}
			if stop {
				return
			}
		case <-dev.close:
			// Superseded while idle: leave without touching the device so
			// the request goes to the reader of the live device.
			return
		case <-d.close:
			return
		}
	}
}

func (d *multiTUN) runDevice(dev *tunDevice) {
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
			case e, ok := <-dev.dev.Events():
				if !ok {
					return
				}
				d.events <- e
			case <-dev.close:
				return
			}
		}
	}()
	warned := false
	for {
		select {
		case w := <-d.writes:
			n, err := dev.dev.Write(w.data, w.offset)
			if err != nil {
				if !warned {
					warned = true
					name, _ := dev.dev.Name()
					log.Printf("multiTUN: write to %s failed: %v; dropping (interface reset or superseded)", name, err)
				}
				n, err = 0, nil
			}
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

func (d *multiTUN) add(dev tun.Device) {
	d.devices <- dev
}

// Up brings the multiTUN up, allowing it to write packets
// to the underlying tunnel device. If there is no underlying
// device yet, write operations are pended until a new device
// is added with [multiTUN.add].
//
// It reports whether this call brought the device up.
func (d *multiTUN) Up() bool {
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
// and makes all [multiTUN.Write] calls return immediately
// until [multiTUN.Up] is called.
//
// It mainly exists to distinguish between cases where the underlying
// device is temporarily unavailable due to VPN reconfiguration,
// in which case write requests should be pended, and cases where
// Tailscale is stopped, where any pending and new requests
// should complete immediately to prevent deadlocks.
// See tailscale/tailscale#18679.
//
// It reports whether this call brought the device down.
func (d *multiTUN) Down() bool {
	d.downMu.Lock()
	defer d.downMu.Unlock()
	if d.down {
		return false
	}
	close(d.downCh.Load())
	d.down = true
	return true
}

func (d *multiTUN) File() *os.File {
	// The underlying file descriptor is not constant on Android.
	// Let's hope no-one uses it.
	panic("not available on Android")
}

func (d *multiTUN) Read(slab []byte, packets []tun.ReadPacket) (int, error) {
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

func (d *multiTUN) Write(data [][]byte, offset int) (int, error) {
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
		// (*multiTUN).Write while sending to d.writes without
		// a receiver on the other side of the pipe.
		return 0, nil
	case <-d.close:
		// Return immediately if the multiTUN device is closed.
		return 0, os.ErrClosed
	}
}

func (d *multiTUN) MTU() (int, error) {
	r := make(chan mtuReply)
	d.mtus <- r
	rep := <-r
	return rep.mtu, rep.err
}

func (d *multiTUN) Name() (string, error) {
	r := make(chan nameReply)
	d.names <- r
	rep := <-r
	return rep.name, rep.err
}

func (d *multiTUN) Events() <-chan tun.Event {
	return d.events
}

func (d *multiTUN) Shutdown() {
	d.shutdowns <- struct{}{}
	<-d.shutdownDone
}

func (d *multiTUN) Close() error {
	close(d.close)
	return <-d.closeErr
}

func (d *multiTUN) BatchSize() int {
	// TODO(raggi): currently Android disallows the necessary ioctls to enable
	// batching. File a bug.
	return 1
}
