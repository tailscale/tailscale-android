// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

import (
	"log"
	"sort"
	"strings"
	"sync"
	"time"

	"gioui.org/app"
	"gioui.org/io/system"
	"gioui.org/layout"
	"gioui.org/op"

	"gioui.org/font/gofont"

	"tailscale.com/control/controlclient"
	"tailscale.com/ipn"
	"tailscale.com/tailcfg"
	"tailscale.com/tailscale-android/jni"
	"tailscale.com/wgengine/router"
)

//go:generate go run github.com/go-bindata/go-bindata/go-bindata -nocompress -o logo.go tailscale.png

type App struct {
	jvm    jni.JVM
	appCtx jni.Object
	appDir string
	store  *stateStore

	// updates is notifies whenever netState or browseURL changes.
	updates chan struct{}
	// vpnClosed is notified when the VPNService is closed while
	// logged in.
	vpnClosed chan struct{}

	// mu protects the following fields.
	mu sync.Mutex
	// netState is the most recent network state.
	netState NetworkState
	// browseURL is set whenever the backend wants to
	// browse.
	browseURL *string
}

type clientState struct {
	browseURL string
	net       NetworkState
	// query is the search query, in lowercase.
	query string

	Peers []UIPeer
	// WantsEnabled is the desired state of the VPN: enabled or
	// disabled.
	WantsEnabled bool
}

type NetworkState struct {
	State       ipn.State
	NetworkMap  *controlclient.NetworkMap
	HasInternet bool
}

// UIEvent is an event flowing from the UI to the backend.
type UIEvent interface{}

type ConnectEvent struct {
	Enable bool
}

type CopyEvent struct {
	Text string
}

type SearchEvent struct {
	Query string
}

type ReauthEvent struct{}

type LogoutEvent struct{}

const enabledKey = "ipn_enabled"

func main() {
	a := &App{
		jvm:       jni.JVMFor(app.JavaVM()),
		appCtx:    jni.Object(app.AppContext()),
		updates:   make(chan struct{}, 1),
		vpnClosed: make(chan struct{}, 1),
	}
	appDir, err := app.DataDir()
	if err != nil {
		fatalErr(err)
	}
	a.appDir = appDir
	a.store = newStateStore(a.appDir, a.jvm, a.appCtx)
	events := make(chan UIEvent)
	go func() {
		if err := a.runBackend(events); err != nil {
			fatalErr(err)
		}
	}()
	go func() {
		if err := a.runUI(events); err != nil {
			fatalErr(err)
		}
	}()
	app.Main()
}

func (a *App) runBackend(events <-chan UIEvent) error {
	var cfg *router.Config
	var state NetworkState
	var service jni.Object
	var b *backend
	b, err := newBackend(a.appDir, a.jvm, a.store, func(s *router.Config) error {
		cfg = s
		if b == nil || service == 0 || cfg == nil {
			return nil
		}
		return b.updateTUN(service, cfg)
	})
	if err != nil {
		return err
	}
	defer b.CloseTUNs()
	var timer *time.Timer
	var alarmChan <-chan time.Time
	alarm := func(t *time.Timer) {
		if timer != nil {
			timer.Stop()
		}
		timer = t
		if timer != nil {
			alarmChan = timer.C
		}
	}
	err = b.Start(func(n ipn.Notify) {
		if s := n.State; s != nil {
			oldState := state.State
			state.State = *s
			if service != 0 {
				a.updateNotification(service, state.State)
			}
			if service != 0 {
				if cfg != nil && state.State >= ipn.Starting {
					if err := b.updateTUN(service, cfg); err != nil {
						a.notifyVPNClosed()
					}
				} else {
					b.CloseTUNs()
				}
			}
			// Stop VPN if we logged out.
			if oldState > ipn.Stopped && state.State <= ipn.Stopped {
				if err := a.callVoidMethod(a.appCtx, "stopVPN", "()V"); err != nil {
					fatalErr(err)
				}
			}
			a.notify(state)
		}
		if u := n.BrowseToURL; u != nil {
			a.setURL(*u)
		}
		if m := n.NetMap; m != nil {
			state.NetworkMap = m
			a.notify(state)
			if service != 0 {
				alarm(a.notifyExpiry(service, m.Expiry))
			}
		}
	})
	if err != nil {
		return err
	}
	for {
		select {
		case <-alarmChan:
			if m := state.NetworkMap; m != nil && service != 0 {
				alarm(a.notifyExpiry(service, m.Expiry))
			}
		case e := <-events:
			switch e.(type) {
			case ReauthEvent:
				b.backend.StartLoginInteractive()
			case LogoutEvent:
				b.backend.Logout()
			}
		case s := <-onConnect:
			jni.Do(a.jvm, func(env jni.Env) error {
				if jni.IsSameObject(env, s, service) {
					// We already have a reference.
					jni.DeleteGlobalRef(env, s)
					return nil
				}
				if service != 0 {
					jni.DeleteGlobalRef(env, service)
				}
				service = s
				return nil
			})
			a.updateNotification(service, state.State)
			if m := state.NetworkMap; m != nil {
				alarm(a.notifyExpiry(service, m.Expiry))
			}
			if cfg != nil && state.State >= ipn.Starting {
				if err := b.updateTUN(service, cfg); err != nil {
					a.notifyVPNClosed()
				}
			}
		case <-onConnectivityChange:
			state.HasInternet = connected.Load().(bool)
			if b != nil {
				b.LinkChange()
			}
			a.notify(state)
		case s := <-onDisconnect:
			b.CloseTUNs()
			jni.Do(a.jvm, func(env jni.Env) error {
				defer jni.DeleteGlobalRef(env, s)
				if jni.IsSameObject(env, service, s) {
					jni.DeleteGlobalRef(env, service)
					service = 0
				}
				return nil
			})
			if state.State >= ipn.Starting {
				a.notifyVPNClosed()
			}
		}
	}
}

// updateNotification updates the foreground persistent status notification.
func (a *App) updateNotification(service jni.Object, state ipn.State) error {
	var msg, title string
	switch state {
	case ipn.Starting:
		title, msg = "Connecting...", ""
	case ipn.Running:
		title, msg = "Connected", ""
	default:
		return nil
	}
	return jni.Do(a.jvm, func(env jni.Env) error {
		cls := jni.GetObjectClass(env, service)
		update := jni.GetMethodID(env, cls, "updateStatusNotification", "(Ljava/lang/String;Ljava/lang/String;)V")
		jtitle := jni.JavaString(env, title)
		jmessage := jni.JavaString(env, msg)
		return jni.CallVoidMethod(env, service, update, jni.Value(jtitle), jni.Value(jmessage))
	})
}

// notifyExpiry notifies the user of imminent session expiry and
// returns a new timer that triggers when the user should be notified
// again.
func (a *App) notifyExpiry(service jni.Object, expiry time.Time) *time.Timer {
	d := time.Until(expiry)
	var title string
	const msg = "Reauthenticate to maintain the connection to your network."
	var t *time.Timer
	const (
		aday = 24 * time.Hour
		soon = 5 * time.Minute
	)
	switch {
	case d <= 0:
		title = "Your authentication has expired!"
	case d <= soon:
		title = "Your authentication expires soon!"
		t = time.NewTimer(d)
	case d <= aday:
		title = "Your authentication expires in a day."
		t = time.NewTimer(d - soon)
	default:
		return time.NewTimer(d - aday)
	}
	err := jni.Do(a.jvm, func(env jni.Env) error {
		cls := jni.GetObjectClass(env, service)
		notify := jni.GetMethodID(env, cls, "notify", "(Ljava/lang/String;Ljava/lang/String;)V")
		jtitle := jni.JavaString(env, title)
		jmessage := jni.JavaString(env, msg)
		return jni.CallVoidMethod(env, service, notify, jni.Value(jtitle), jni.Value(jmessage))
	})
	if err != nil {
		fatalErr(err)
	}
	return t
}

func (a *App) notifyVPNClosed() {
	select {
	case a.vpnClosed <- struct{}{}:
	default:
	}
}

func (a *App) notify(state NetworkState) {
	a.mu.Lock()
	a.netState = state
	a.mu.Unlock()
	select {
	case a.updates <- struct{}{}:
	default:
	}
}

func (a *App) setURL(url string) {
	a.mu.Lock()
	a.browseURL = &url
	a.mu.Unlock()
	select {
	case a.updates <- struct{}{}:
	default:
	}
}

func (a *App) runUI(backend chan<- UIEvent) error {
	w := app.NewWindow()
	gofont.Register()
	ui, err := newUI(a.store)
	if err != nil {
		return err
	}
	// Register an Android Fragment instance for lifecycle tracking
	// of our Activity.
	w.RegisterFragment("com.tailscale.ipn.Peer")
	var ops op.Ops
	state := new(clientState)
	var peer jni.Object
	state.WantsEnabled, _ = a.store.ReadBool(enabledKey, true)
	ui.enabled.Value = state.WantsEnabled
	for {
		select {
		case <-a.vpnClosed:
			state.WantsEnabled = false
			w.Invalidate()
		case <-a.updates:
			a.mu.Lock()
			oldState := state.net.State
			state.net = a.netState
			if a.browseURL != nil {
				state.browseURL = *a.browseURL
				a.browseURL = nil
			}
			a.mu.Unlock()
			a.updateState(peer, state)
			w.Invalidate()
			if peer != 0 {
				newState := state.net.State
				// Start VPN if we just logged in.
				if state.WantsEnabled && oldState <= ipn.Stopped && newState > ipn.Stopped {
					if err := a.callVoidMethod(peer, "prepareVPN", "()V"); err != nil {
						fatalErr(err)
					}
				}
			}
		case peer = <-onPeerCreated:
			w.Invalidate()
			a.setVPNState(peer, state)
		case p := <-onPeerDestroyed:
			jni.Do(a.jvm, func(env jni.Env) error {
				defer jni.DeleteGlobalRef(env, p)
				if jni.IsSameObject(env, peer, p) {
					jni.DeleteGlobalRef(env, peer)
					peer = 0
				}
				return nil
			})
		case <-vpnPrepared:
			if err := a.callVoidMethod(a.appCtx, "startVPN", "()V"); err != nil {
				return err
			}
		case e := <-w.Events():
			switch e := e.(type) {
			case system.DestroyEvent:
				return e.Err
			case system.FrameEvent:
				gtx := layout.NewContext(&ops, e.Queue, e.Config, e.Size)
				events := ui.layout(gtx, e.Insets, state)
				e.Frame(gtx.Ops)
				a.processUIEvents(backend, w, events, peer, state)
			}
		}
	}
}

func (a *App) updateState(javaPeer jni.Object, state *clientState) {
	if javaPeer != 0 && state.browseURL != "" {
		a.browseToURL(javaPeer, state.browseURL)
		state.browseURL = ""
	}

	state.Peers = nil
	netMap := state.net.NetworkMap
	if netMap == nil {
		return
	}
	// Split into sections.
	users := make(map[tailcfg.UserID]struct{})
	var peers []UIPeer
	for _, p := range netMap.Peers {
		if q := state.query; q != "" {
			// Filter peers according to search query.
			host := strings.ToLower(p.Hostinfo.Hostname)
			name := strings.ToLower(p.Name)
			var addr string
			if len(p.Addresses) > 0 {
				addr = p.Addresses[0].IP.String()
			}
			if !strings.Contains(host, q) && !strings.Contains(name, q) && !strings.Contains(addr, q) {
				continue
			}
		}
		users[p.User] = struct{}{}
		peers = append(peers, UIPeer{
			Owner: p.User,
			Peer:  p,
		})
	}
	// Add section (user) headers.
	for u := range users {
		name := netMap.UserProfiles[u].DisplayName
		name = strings.ToUpper(name)
		peers = append(peers, UIPeer{Owner: u, Name: name})
	}
	myID := state.net.NetworkMap.User
	sort.Slice(peers, func(i, j int) bool {
		lhs, rhs := peers[i], peers[j]
		if lu, ru := lhs.Owner, rhs.Owner; ru != lu {
			// Sort own peers first.
			if lu == myID {
				return true
			}
			if ru == myID {
				return false
			}
			return lu < ru
		}
		lp, rp := lhs.Peer, rhs.Peer
		// Sort headers first.
		if lp == nil {
			return true
		}
		if rp == nil {
			return false
		}
		return lp.Hostinfo.Hostname < rp.Hostinfo.Hostname ||
			lp.Hostinfo.Hostname == rp.Hostinfo.Hostname && lp.ID < rp.ID
	})
	state.Peers = peers
}

func (a *App) processUIEvents(backend chan<- UIEvent, w *app.Window, events []UIEvent, peer jni.Object, state *clientState) {
	for _, e := range events {
		switch e := e.(type) {
		case ReauthEvent:
			go func() {
				backend <- e
			}()
		case LogoutEvent:
			go func() {
				backend <- e
			}()
		case CopyEvent:
			w.WriteClipboard(e.Text)
		case SearchEvent:
			state.query = strings.ToLower(e.Query)
			a.updateState(peer, state)
		case ConnectEvent:
			if e.Enable == state.WantsEnabled {
				return
			}
			if e.Enable && peer == 0 {
				return
			}
			state.WantsEnabled = e.Enable
			a.store.WriteBool(enabledKey, e.Enable)
			a.updateState(peer, state)
			a.setVPNState(peer, state)
		}
	}
}

func (a *App) setVPNState(peer jni.Object, state *clientState) {
	var err error
	if state.WantsEnabled && state.net.State > ipn.Stopped {
		err = a.callVoidMethod(peer, "prepareVPN", "()V")
	} else {
		err = a.callVoidMethod(a.appCtx, "stopVPN", "()V")
	}
	if err != nil {
		fatalErr(err)
	}
}

func (a *App) browseToURL(peer jni.Object, url string) {
	err := jni.Do(a.jvm, func(env jni.Env) error {
		jurl := jni.JavaString(env, url)
		return a.callVoidMethod(peer, "showURLCustomTabs", "(Ljava/lang/String;)V", jni.Value(jurl))
	})
	if err != nil {
		fatalErr(err)
	}
}

func (a *App) callVoidMethod(obj jni.Object, name, sig string, args ...jni.Value) error {
	if obj == 0 {
		panic("invalid object")
	}
	return jni.Do(a.jvm, func(env jni.Env) error {
		cls := jni.GetObjectClass(env, obj)
		m := jni.GetMethodID(env, cls, name, sig)
		return jni.CallVoidMethod(env, obj, m, args...)
	})
}

func fatalErr(err error) {
	log.Print(err)
}
