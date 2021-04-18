// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

import (
	"crypto/sha1"
	"fmt"
	"log"
	"sort"
	"strings"
	"time"
	"unsafe"

	"gioui.org/app"
	"gioui.org/io/system"
	"gioui.org/layout"
	"gioui.org/op"
	"inet.af/netaddr"

	"github.com/tailscale/tailscale-android/jni"
	"tailscale.com/ipn"
	"tailscale.com/net/dns"
	"tailscale.com/tailcfg"
	"tailscale.com/types/netmap"
	"tailscale.com/wgengine/router"
)

//go:generate go run github.com/go-bindata/go-bindata/go-bindata -nocompress -o logo.go tailscale.png google.png

type App struct {
	jvm *jni.JVM
	// appCtx is a global reference to the com.tailscale.ipn.App instance.
	appCtx jni.Object

	store *stateStore

	// netStates receives the most recent network state.
	netStates chan BackendState
	// prefs receives new preferences from the backend.
	prefs chan *ipn.Prefs
	// browseURLs receives URLs when the backend wants to browse.
	browseURLs chan string

	// backend is the channel for events from the frontend to the
	// backend.
	backendEvents chan UIEvent
}

var (
	// googleClass is a global reference to the com.tailscale.ipn.Google class.
	googleClass jni.Class
)

type clientState struct {
	browseURL string
	backend   BackendState
	// query is the search query, in lowercase.
	query string

	Peers []UIPeer
}

type ExitStatus uint8

const (
	// No exit node selected.
	ExitNone ExitStatus = iota
	// Exit node selected and exists, but is offline or missing.
	ExitOffline
	// Exit node selected and online.
	ExitOnline
)

type ExitNode struct {
	Label  string
	Online bool
	ID     tailcfg.StableNodeID
}

type BackendState struct {
	Prefs        *ipn.Prefs
	State        ipn.State
	NetworkMap   *netmap.NetworkMap
	LostInternet bool
	// Exits are the peers that can act as exit node.
	Exits []ExitNode
	// ExitState describes the state of our exit node.
	ExitStatus ExitStatus
	// Exit is our current exit node, if any.
	Exit ExitNode
}

// UIEvent is an event flowing from the UI to the backend.
type UIEvent interface{}

type RouteAllEvent struct {
	ID tailcfg.StableNodeID
}

type ConnectEvent struct {
	Enable bool
}

type CopyEvent struct {
	Text string
}

type SearchEvent struct {
	Query string
}

type OAuth2Event struct {
	Token *tailcfg.Oauth2Token
}

// UIEvent types.
type (
	ToggleEvent     struct{}
	ReauthEvent     struct{}
	WebAuthEvent    struct{}
	GoogleAuthEvent struct{}
	LogoutEvent     struct{}
)

// serverOAuthID is the OAuth ID of the tailscale-android server, used
// by GoogleSignInOptions.Builder.requestIdToken.
const serverOAuthID = "744055068597-hv4opg0h7vskq1hv37nq3u26t8c15qk0.apps.googleusercontent.com"

// releaseCertFingerprint is the SHA-1 fingerprint of the Google Play Store signing key.
// It is used to check whether the app is signed for release.
const releaseCertFingerprint = "86:9D:11:8B:63:1E:F8:35:C6:D9:C2:66:53:BC:28:22:2F:B8:C1:AE"

// backendEvents receives events from the UI (Activity, Tile etc.) to the backend.
var backendEvents = make(chan UIEvent)

func main() {
	a := &App{
		jvm:        (*jni.JVM)(unsafe.Pointer(app.JavaVM())),
		appCtx:     jni.Object(app.AppContext()),
		netStates:  make(chan BackendState, 1),
		browseURLs: make(chan string, 1),
		prefs:      make(chan *ipn.Prefs, 1),
	}
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		loader := jni.ClassLoaderFor(env, a.appCtx)
		cl, err := jni.LoadClass(env, loader, "com.tailscale.ipn.Google")
		if err != nil {
			// Ignore load errors; the Google class is not included in F-Droid builds.
			return nil
		}
		googleClass = jni.Class(jni.NewGlobalRef(env, jni.Object(cl)))
		return nil
	})
	if err != nil {
		fatalErr(err)
	}
	a.store = newStateStore(a.jvm, a.appCtx)
	go func() {
		if err := a.runBackend(); err != nil {
			fatalErr(err)
		}
	}()
	go func() {
		if err := a.runUI(); err != nil {
			fatalErr(err)
		}
	}()
	app.Main()
}

func (a *App) runBackend() error {
	appDir, err := app.DataDir()
	if err != nil {
		fatalErr(err)
	}
	type configPair struct {
		rcfg *router.Config
		dcfg *dns.OSConfig
	}
	configs := make(chan configPair)
	configErrs := make(chan error)
	b, err := newBackend(appDir, a.jvm, a.store, func(rcfg *router.Config, dcfg *dns.OSConfig) error {
		if rcfg == nil {
			return nil
		}
		configs <- configPair{rcfg, dcfg}
		return <-configErrs
	})
	if err != nil {
		return err
	}
	defer b.CloseTUNs()

	// Contrary to the documentation for VpnService.Builder.addDnsServer,
	// ChromeOS doesn't fall back to the underlying network nameservers if
	// we don't provide any.
	b.avoidEmptyDNS = a.isChromeOS()

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
	notifications := make(chan ipn.Notify, 1)
	startErr := make(chan error)
	// Start from a goroutine to avoid deadlock when Start
	// calls the callback.
	go func() {
		startErr <- b.Start(func(n ipn.Notify) {
			notifications <- n
		})
	}()
	var (
		cfg       configPair
		state     BackendState
		service   jni.Object
		signingIn bool
	)
	for {
		select {
		case err := <-startErr:
			if err != nil {
				return err
			}
		case s := <-configs:
			cfg = s
			if b == nil || service == 0 || cfg.rcfg == nil {
				configErrs <- nil
				break
			}
			configErrs <- b.updateTUN(service, cfg.rcfg, cfg.dcfg)
		case n := <-notifications:
			exitWasOnline := state.ExitStatus == ExitOnline
			if p := n.Prefs; p != nil {
				first := state.Prefs == nil
				state.Prefs = p.Clone()
				state.updateExitNodes()
				if first {
					state.Prefs.Hostname = a.hostname()
					state.Prefs.OSVersion = a.osVersion()
					state.Prefs.DeviceModel = a.modelName()
					go b.backend.SetPrefs(state.Prefs)
				}
				a.setPrefs(state.Prefs)
			}
			if s := n.State; s != nil {
				oldState := state.State
				state.State = *s
				if service != 0 {
					a.updateNotification(service, state.State)
				}
				if service != 0 {
					if cfg.rcfg != nil && state.State >= ipn.Starting {
						if err := b.updateTUN(service, cfg.rcfg, cfg.dcfg); err != nil {
							log.Printf("VPN update failed: %v", err)
							notifyVPNClosed()
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
				signingIn = false
				a.setURL(*u)
			}
			if m := n.NetMap; m != nil {
				state.NetworkMap = m
				state.updateExitNodes()
				a.notify(state)
				if service != 0 {
					alarm(a.notifyExpiry(service, m.Expiry))
				}
			}
			// Notify if a previously online exit is not longer online (or missing).
			if service != 0 && exitWasOnline && state.ExitStatus == ExitOffline {
				a.pushNotify(service, "Connection Lost", "Your exit node is offline. Disable your exit node or contact your network admin for help.")
			}
		case <-alarmChan:
			if m := state.NetworkMap; m != nil && service != 0 {
				alarm(a.notifyExpiry(service, m.Expiry))
			}
		case e := <-backendEvents:
			switch e := e.(type) {
			case OAuth2Event:
				go b.backend.Login(e.Token)
			case ToggleEvent:
				state.Prefs.WantRunning = !state.Prefs.WantRunning
				go b.backend.SetPrefs(state.Prefs)
			case WebAuthEvent:
				if !signingIn {
					go b.backend.StartLoginInteractive()
					signingIn = true
				}
			case LogoutEvent:
				go b.backend.Logout()
			case ConnectEvent:
				state.Prefs.WantRunning = e.Enable
				go b.backend.SetPrefs(state.Prefs)
			case RouteAllEvent:
				state.Prefs.ExitNodeID = e.ID
				go b.backend.SetPrefs(state.Prefs)
				state.updateExitNodes()
				a.notify(state)
			}
		case s := <-onConnect:
			jni.Do(a.jvm, func(env *jni.Env) error {
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
			if cfg.rcfg != nil && state.State >= ipn.Starting {
				if err := b.updateTUN(service, cfg.rcfg, cfg.dcfg); err != nil {
					log.Printf("VPN update failed: %v", err)
					notifyVPNClosed()
				}
			}
		case connected := <-onConnectivityChange:
			state.LostInternet = !connected
			if b != nil {
				go b.LinkChange()
			}
			a.notify(state)
		case s := <-onDisconnect:
			b.CloseTUNs()
			jni.Do(a.jvm, func(env *jni.Env) error {
				defer jni.DeleteGlobalRef(env, s)
				if jni.IsSameObject(env, service, s) {
					jni.DeleteGlobalRef(env, service)
					service = 0
				}
				return nil
			})
			if state.State >= ipn.Starting {
				notifyVPNClosed()
			}
		}
	}
}

func (a *App) isChromeOS() bool {
	var chromeOS bool
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, a.appCtx)
		m := jni.GetMethodID(env, cls, "isChromeOS", "()Z")
		b, err := jni.CallBooleanMethod(env, a.appCtx, m)
		chromeOS = b
		return err
	})
	if err != nil {
		panic(err)
	}
	return chromeOS
}

func (s *BackendState) updateExitNodes() {
	s.ExitStatus = ExitNone
	var exitID tailcfg.StableNodeID
	if p := s.Prefs; p != nil {
		exitID = p.ExitNodeID
		if exitID != "" {
			s.ExitStatus = ExitOffline
		}
	}
	hasMyExit := exitID == ""
	s.Exits = nil
	var peers []*tailcfg.Node
	if s.NetworkMap != nil {
		peers = s.NetworkMap.Peers
	}
	for _, p := range peers {
		canRoute := false
		for _, r := range p.AllowedIPs {
			if r == netaddr.MustParseIPPrefix("0.0.0.0/0") || r == netaddr.MustParseIPPrefix("::/0") {
				canRoute = true
				break
			}
		}
		myExit := p.StableID == exitID
		hasMyExit = hasMyExit || myExit
		exit := ExitNode{
			Label:  p.DisplayName(true),
			Online: canRoute,
			ID:     p.StableID,
		}
		if myExit {
			s.Exit = exit
			if canRoute {
				s.ExitStatus = ExitOnline
			}
		}
		if canRoute || myExit {
			s.Exits = append(s.Exits, exit)
		}
	}
	sort.Slice(s.Exits, func(i, j int) bool {
		return s.Exits[i].Label < s.Exits[j].Label
	})
	if !hasMyExit {
		// Insert node missing from netmap.
		s.Exit = ExitNode{Label: "Unknown device", ID: exitID}
		s.Exits = append([]ExitNode{s.Exit}, s.Exits...)
	}
}

// hostname builds a hostname from android.os.Build fields, in place of a
// useless os.Hostname().
func (a *App) hostname() string {
	var hostname string
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, a.appCtx)
		getHostname := jni.GetMethodID(env, cls, "getHostname", "()Ljava/lang/String;")
		n, err := jni.CallObjectMethod(env, a.appCtx, getHostname)
		hostname = jni.GoString(env, jni.String(n))
		return err
	})
	if err != nil {
		panic(err)
	}
	return hostname
}

// osVersion returns android.os.Build.VERSION.RELEASE. " [nogoogle]" is appended
// if Google Play services are not compiled in.
func (a *App) osVersion() string {
	var version string
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, a.appCtx)
		m := jni.GetMethodID(env, cls, "getOSVersion", "()Ljava/lang/String;")
		n, err := jni.CallObjectMethod(env, a.appCtx, m)
		version = jni.GoString(env, jni.String(n))
		return err
	})
	if err != nil {
		panic(err)
	}
	if !googleSignInEnabled() {
		version += " [nogoogle]"
	}
	return version
}

// modelName return the MANUFACTURER + MODEL from
// android.os.Build.
func (a *App) modelName() string {
	var model string
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, a.appCtx)
		m := jni.GetMethodID(env, cls, "getModelName", "()Ljava/lang/String;")
		n, err := jni.CallObjectMethod(env, a.appCtx, m)
		model = jni.GoString(env, jni.String(n))
		return err
	})
	if err != nil {
		panic(err)
	}
	return model
}

func googleSignInEnabled() bool {
	return googleClass != 0
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
	return jni.Do(a.jvm, func(env *jni.Env) error {
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
	if expiry.IsZero() {
		return nil
	}
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
	if err := a.pushNotify(service, title, msg); err != nil {
		fatalErr(err)
	}
	return t
}

func (a *App) pushNotify(service jni.Object, title, msg string) error {
	return jni.Do(a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, service)
		notify := jni.GetMethodID(env, cls, "notify", "(Ljava/lang/String;Ljava/lang/String;)V")
		jtitle := jni.JavaString(env, title)
		jmessage := jni.JavaString(env, msg)
		return jni.CallVoidMethod(env, service, notify, jni.Value(jtitle), jni.Value(jmessage))
	})
}

func (a *App) notify(state BackendState) {
	select {
	case <-a.netStates:
	default:
	}
	a.netStates <- state
	ready := jni.Bool(state.State >= ipn.Stopped)
	if err := a.callVoidMethod(a.appCtx, "setTileReady", "(Z)V", jni.Value(ready)); err != nil {
		fatalErr(err)
	}
}

func (a *App) setPrefs(prefs *ipn.Prefs) {
	wantRunning := jni.Bool(prefs.WantRunning)
	if err := a.callVoidMethod(a.appCtx, "setTileStatus", "(Z)V", jni.Value(wantRunning)); err != nil {
		fatalErr(err)
	}
	select {
	case <-a.prefs:
	default:
	}
	a.prefs <- prefs
}

func (a *App) setURL(url string) {
	select {
	case <-a.browseURLs:
	default:
	}
	a.browseURLs <- url
}

func (a *App) runUI() error {
	w := app.NewWindow()
	ui, err := newUI(a.store)
	if err != nil {
		return err
	}
	var ops op.Ops
	state := new(clientState)
	var (
		// activity is the most recent Android Activity reference as reported
		// by Gio ViewEvents.
		activity jni.Object
	)
	deleteActivityRef := func() {
		if activity == 0 {
			return
		}
		jni.Do(a.jvm, func(env *jni.Env) error {
			jni.DeleteGlobalRef(env, activity)
			return nil
		})
		activity = 0
	}
	defer deleteActivityRef()
	for {
		select {
		case <-onVPNClosed:
			requestBackend(ConnectEvent{Enable: false})
		case tok := <-onGoogleToken:
			ui.signinType = noSignin
			if tok != "" {
				requestBackend(OAuth2Event{
					Token: &tailcfg.Oauth2Token{
						AccessToken: tok,
						TokenType:   ipn.GoogleIDTokenType,
					},
				})
			} else {
				// Warn about possible debug certificate.
				if !a.isReleaseSigned() {
					ui.ShowMessage("Google Sign-In failed because the app is not signed for Play Store")
					w.Invalidate()
				}
			}
		case p := <-a.prefs:
			ui.enabled.Value = p.WantRunning
			w.Invalidate()
		case state.browseURL = <-a.browseURLs:
			ui.signinType = noSignin
			w.Invalidate()
			a.updateState(activity, state)
		case newState := <-a.netStates:
			oldState := state.backend.State
			state.backend = newState
			a.updateState(activity, state)
			w.Invalidate()
			if activity != 0 {
				newState := state.backend.State
				// Start VPN if we just logged in.
				if oldState <= ipn.Stopped && newState > ipn.Stopped {
					if err := a.prepareVPN(activity); err != nil {
						fatalErr(err)
					}
				}
			}
		case <-onVPNPrepared:
			if state.backend.State > ipn.Stopped {
				if err := a.callVoidMethod(a.appCtx, "startVPN", "()V"); err != nil {
					return err
				}
			}
		case <-onVPNRevoked:
			ui.ShowMessage("VPN access denied or another VPN service is always-on")
			w.Invalidate()
		case e := <-w.Events():
			switch e := e.(type) {
			case app.ViewEvent:
				deleteActivityRef()
				view := jni.Object(e.View)
				if view == 0 {
					break
				}
				activity = a.contextForView(view)
				w.Invalidate()
				a.attachPeer(activity)
				if state.backend.State > ipn.Stopped {
					if err := a.prepareVPN(activity); err != nil {
						return err
					}
				}
			case system.DestroyEvent:
				return e.Err
			case *system.CommandEvent:
				if e.Type == system.CommandBack {
					if ui.onBack() {
						e.Cancel = true
					}
				}
			case system.FrameEvent:
				ins := e.Insets
				e.Insets = system.Insets{}
				gtx := layout.NewContext(&ops, e)
				events := ui.layout(gtx, ins, state)
				e.Frame(gtx.Ops)
				a.processUIEvents(w, events, activity, state)
			}
		}
	}
}

// isReleaseSigned reports whether the app is signed with a release
// signature.
func (a *App) isReleaseSigned() bool {
	var cert []byte
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, a.appCtx)
		m := jni.GetMethodID(env, cls, "getPackageCertificate", "()[B")
		str, err := jni.CallObjectMethod(env, a.appCtx, m)
		if err != nil {
			return err
		}
		cert = jni.GetByteArrayElements(env, jni.ByteArray(str))
		return nil
	})
	if err != nil {
		fatalErr(err)
	}
	h := sha1.New()
	h.Write(cert)
	fingerprint := h.Sum(nil)
	hex := fmt.Sprintf("%x", fingerprint)
	// Strip colons and convert to lower case to ease comparing.
	wantFingerprint := strings.ReplaceAll(strings.ToLower(releaseCertFingerprint), ":", "")
	return hex == wantFingerprint
}

// attachPeer registers an Android Fragment instance for
// handling onActivityResult callbacks.
func (a *App) attachPeer(act jni.Object) {
	err := a.callVoidMethod(a.appCtx, "attachPeer", "(Landroid/app/Activity;)V", jni.Value(act))
	if err != nil {
		fatalErr(err)
	}
}

func (a *App) updateState(act jni.Object, state *clientState) {
	if act != 0 && state.browseURL != "" {
		a.browseToURL(act, state.browseURL)
		state.browseURL = ""
	}

	netmap := state.backend.NetworkMap
	var (
		peers []*tailcfg.Node
		myID  tailcfg.UserID
	)
	if netmap != nil {
		peers = netmap.Peers
		myID = netmap.User
	}
	// Split into sections.
	users := make(map[tailcfg.UserID]struct{})
	var uiPeers []UIPeer
	for _, p := range peers {
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
		uiPeers = append(uiPeers, UIPeer{
			Owner: p.User,
			Peer:  p,
		})
	}
	// Add section (user) headers.
	for u := range users {
		name := netmap.UserProfiles[u].DisplayName
		name = strings.ToUpper(name)
		uiPeers = append(uiPeers, UIPeer{Owner: u, Name: name})
	}
	sort.Slice(uiPeers, func(i, j int) bool {
		lhs, rhs := uiPeers[i], uiPeers[j]
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
		lName := lp.DisplayName(lp.User == myID)
		rName := rp.DisplayName(rp.User == myID)
		return lName < rName || lName == rName && lp.ID < rp.ID
	})
	state.Peers = uiPeers
}

func (a *App) prepareVPN(act jni.Object) error {
	return a.callVoidMethod(a.appCtx, "prepareVPN", "(Landroid/app/Activity;I)V",
		jni.Value(act), jni.Value(requestPrepareVPN))
}

func requestBackend(e UIEvent) {
	go func() {
		backendEvents <- e
	}()
}

func (a *App) processUIEvents(w *app.Window, events []UIEvent, act jni.Object, state *clientState) {
	for _, e := range events {
		switch e := e.(type) {
		case ReauthEvent:
			method, _ := a.store.ReadString(loginMethodPrefKey, loginMethodWeb)
			switch method {
			case loginMethodGoogle:
				a.googleSignIn(act)
			default:
				requestBackend(WebAuthEvent{})
			}
		case WebAuthEvent:
			a.store.WriteString(loginMethodPrefKey, loginMethodWeb)
			requestBackend(e)
		case LogoutEvent:
			a.signOut()
			requestBackend(e)
		case ConnectEvent:
			requestBackend(e)
		case RouteAllEvent:
			requestBackend(e)
		case CopyEvent:
			w.WriteClipboard(e.Text)
		case GoogleAuthEvent:
			a.store.WriteString(loginMethodPrefKey, loginMethodGoogle)
			a.googleSignIn(act)
		case SearchEvent:
			state.query = strings.ToLower(e.Query)
			a.updateState(act, state)
		}
	}
}

func (a *App) signOut() {
	if googleClass == 0 {
		return
	}
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		m := jni.GetStaticMethodID(env, googleClass,
			"googleSignOut", "(Landroid/content/Context;)V")
		return jni.CallStaticVoidMethod(env, googleClass, m, jni.Value(a.appCtx))
	})
	if err != nil {
		fatalErr(err)
	}
}

func (a *App) googleSignIn(act jni.Object) {
	if act == 0 || googleClass == 0 {
		return
	}
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		sid := jni.JavaString(env, serverOAuthID)
		m := jni.GetStaticMethodID(env, googleClass,
			"googleSignIn", "(Landroid/app/Activity;Ljava/lang/String;I)V")
		return jni.CallStaticVoidMethod(env, googleClass, m,
			jni.Value(act), jni.Value(sid), jni.Value(requestSignin))
	})
	if err != nil {
		fatalErr(err)
	}
}

func (a *App) browseToURL(act jni.Object, url string) {
	if act == 0 {
		return
	}
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		jurl := jni.JavaString(env, url)
		return a.callVoidMethod(a.appCtx, "showURL", "(Landroid/app/Activity;Ljava/lang/String;)V", jni.Value(act), jni.Value(jurl))
	})
	if err != nil {
		fatalErr(err)
	}
}

func (a *App) callVoidMethod(obj jni.Object, name, sig string, args ...jni.Value) error {
	if obj == 0 {
		panic("invalid object")
	}
	return jni.Do(a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, obj)
		m := jni.GetMethodID(env, cls, name, sig)
		return jni.CallVoidMethod(env, obj, m, args...)
	})
}

// activityForView calls View.getContext and returns a global
// reference to the result.
func (a *App) contextForView(view jni.Object) jni.Object {
	if view == 0 {
		panic("invalid object")
	}
	var ctx jni.Object
	err := jni.Do(a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, view)
		m := jni.GetMethodID(env, cls, "getContext", "()Landroid/content/Context;")
		var err error
		ctx, err = jni.CallObjectMethod(env, view, m)
		ctx = jni.NewGlobalRef(env, ctx)
		return err
	})
	if err != nil {
		panic(err)
	}
	return ctx
}

func fatalErr(err error) {
	// TODO: expose in UI.
	log.Printf("fatal error: %v", err)
}
