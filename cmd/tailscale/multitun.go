// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

import (
	"os"

	"golang.zx2c4.com/wireguard/tun"
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

	reads        chan ioRequest
	writes       chan ioRequest
	flushes      chan chan error
	mtus         chan chan mtuReply
	names        chan chan nameReply
	shutdowns    chan struct{}
	shutdownDone chan struct{}
}

// tunDevice wraps and drives a single run.Device.
type tunDevice struct {
	dev tun.Device
	// close closes the device.
	close     chan struct{}
	closeDone chan error
	// readDone is notified when the read goroutine is done.
	readDone chan struct{}
}

type ioRequest struct {
	data   []byte
	offset int
	reply  chan<- ioReply
}

type ioReply struct {
	bytes int
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
		reads:        make(chan ioRequest),
		writes:       make(chan ioRequest),
		flushes:      make(chan chan error),
		mtus:         make(chan chan mtuReply),
		names:        make(chan chan nameReply),
		shutdowns:    make(chan struct{}),
		shutdownDone: make(chan struct{}),
	}
	go d.run()
	return d
}

func (d *multiTUN) run() {
	var devices []*tunDevice
	// readDone is the readDone channel of the device being read from.
	var readDone chan struct{}
	// runDone is the closeDone channel of the device being written to.
	var runDone chan error
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
		case <-runDone:
			// A device completed runDevice, replace it.
			if len(devices) > 0 {
				dev := devices[len(devices)-1]
				runDone = dev.closeDone
				go d.runDevice(dev)
			}
		case <-d.shutdowns:
			// Shut down all devices.
			for _, dev := range devices {
				close(dev.close)
				<-dev.closeDone
				<-dev.readDone
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
				closeDone: make(chan error),
				readDone:  make(chan struct{}, 1),
			}
			if len(devices) == 0 {
				// Start using this first device.
				readDone = wrap.readDone
				go d.readFrom(wrap)
				runDone = wrap.closeDone
				go d.runDevice(wrap)
			}
			devices = append(devices, wrap)
		case f := <-d.flushes:
			var err error
			if len(devices) > 0 {
				dev := devices[len(devices)-1]
				err = dev.dev.Flush()
			}
			f <- err
		case m := <-d.mtus:
			r := mtuReply{mtu: defaultMTU}
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

func (d *multiTUN) readFrom(dev *tunDevice) {
	defer func() {
		dev.readDone <- struct{}{}
	}()
	for {
		select {
		case r := <-d.reads:
			n, err := dev.dev.Read(r.data, r.offset)
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

func (d *multiTUN) runDevice(dev *tunDevice) {
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

func (d *multiTUN) add(dev tun.Device) {
	d.devices <- dev
}

func (d *multiTUN) File() *os.File {
	// The underlying file descriptor is not constant on Android.
	// Let's hope no-one uses it.
	panic("not available on Android")
}

func (d *multiTUN) Read(data []byte, offset int) (int, error) {
	r := make(chan ioReply)
	d.reads <- ioRequest{data, offset, r}
	rep := <-r
	return rep.bytes, rep.err
}

func (d *multiTUN) Write(data []byte, offset int) (int, error) {
	r := make(chan ioReply)
	d.writes <- ioRequest{data, offset, r}
	rep := <-r
	return rep.bytes, rep.err
}

func (d *multiTUN) Flush() error {
	r := make(chan error)
	d.flushes <- r
	return <-r
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

func (d *multiTUN) Events() chan tun.Event {
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
