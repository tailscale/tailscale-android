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
	"sync"
	"time"
	"unsafe"

	"gioui.org/app"
	"gioui.org/io/system"
	"gioui.org/layout"
	"gioui.org/op"
	"golang.org/x/oauth2"

	"github.com/tailscale/tailscale-android/jni"
	"tailscale.com/control/controlclient"
	"tailscale.com/ipn"
	"tailscale.com/tailcfg"
	"tailscale.com/wgengine/router"
)

//go:generate go run github.com/go-bindata/go-bindata/go-bindata -nocompress -o logo.go tailscale.png google.png

type App struct {
	jvm *jni.JVM
	// appCtx is a global reference to the com.tailscale.ipn.App instance.
	appCtx jni.Object

	store *stateStore

	// updates is notifies whenever netState or browseURL changes.
	updates chan struct{}

	// backend is the channel for events from the frontend to the
	// backend.
	backendEvents chan UIEvent

	// mu protects the following fields.
	mu sync.Mutex
	// netState is the most recent network state.
	netState BackendState
	// browseURL is set whenever the backend wants to
	// browse.
	browseURL *string
	// prefs is set when new preferences arrive.
	prefs *ipn.Prefs
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

type BackendState struct {
	State        ipn.State
	NetworkMap   *controlclient.NetworkMap
	LostInternet bool
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

type OAuth2Event struct {
	Token *oauth2.Token
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

const enabledKey = "ipn_enabled"

// releaseCertFingerprint is the SHA-1 fingerprint of the Google Play Store signing key.
// It is used to check whether the app is signed for release.
const releaseCertFingerprint = "86:9D:11:8B:63:1E:F8:35:C6:D9:C2:66:53:BC:28:22:2F:B8:C1:AE"

// backendEvents receives events from the UI (Activity, Tile etc.) to the backend.
var backendEvents = make(chan UIEvent)

func main() {
	a := &App{
		jvm:     (*jni.JVM)(unsafe.Pointer(app.JavaVM())),
		appCtx:  jni.Object(app.AppContext()),
		updates: make(chan struct{}, 1),
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
	configs := make(chan *router.Config)
	configErrs := make(chan error)
	b, err := newBackend(appDir, a.jvm, a.store, func(s *router.Config) error {
		if s == nil {
			return nil
		}
		configs <- s
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
	var prefs *ipn.Prefs
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
		cfg       *router.Config
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
			if b == nil || service == 0 || cfg == nil {
				configErrs <- nil
				break
			}
			configErrs <- b.updateTUN(service, cfg)
		case n := <-notifications:
			if p := n.Prefs; p != nil {
				first := prefs == nil
				prefs = p.Clone()
				if first {
					prefs.Hostname = a.hostname()
					prefs.OSVersion = a.osVersion()
					prefs.DeviceModel = a.modelName()
					go b.backend.SetPrefs(prefs)
				}
				a.setPrefs(prefs)
			}
			if s := n.State; s != nil {
				oldState := state.State
				state.State = *s
				if service != 0 {
					a.updateNotification(service, state.State)
				}
				if service != 0 {
					if cfg != nil && state.State >= ipn.Starting {
						if err := b.updateTUN(service, cfg); err != nil {
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
				a.notify(state)
				if service != 0 {
					alarm(a.notifyExpiry(service, m.Expiry))
				}
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
				prefs.WantRunning = !prefs.WantRunning
				go b.backend.SetPrefs(prefs)
			case WebAuthEvent:
				if !signingIn {
					go b.backend.StartLoginInteractive()
					signingIn = true
				}
			case LogoutEvent:
				go b.backend.Logout()
			case ConnectEvent:
				prefs.WantRunning = e.Enable
				go b.backend.SetPrefs(prefs)
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
			if cfg != nil && state.State >= ipn.Starting {
				if err := b.updateTUN(service, cfg); err != nil {
					log.Printf("VPN update failed: %v", err)
					notifyVPNClosed()
				}
			}
		case <-onConnectivityChange:
			state.LostInternet = !connected.Load().(bool)
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
	err := jni.Do(a.jvm, func(env *jni.Env) error {
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

func (a *App) notify(state BackendState) {
	a.mu.Lock()
	a.netState = state
	a.mu.Unlock()
	select {
	case a.updates <- struct{}{}:
	default:
	}
}

func (a *App) setPrefs(prefs *ipn.Prefs) {
	a.mu.Lock()
	a.prefs = prefs
	wantRunning := jni.True
	if !prefs.WantRunning {
		wantRunning = jni.False
	}
	a.mu.Unlock()
	if err := a.callVoidMethod(a.appCtx, "setTileStatus", "(Z)V", jni.Value(wantRunning)); err != nil {
		fatalErr(err)
	}
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
					Token: &oauth2.Token{
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
		case <-a.updates:
			a.mu.Lock()
			oldState := state.backend.State
			state.backend = a.netState
			if a.browseURL != nil {
				state.browseURL = *a.browseURL
				a.browseURL = nil
				ui.signinType = noSignin
			}
			if a.prefs != nil {
				ui.enabled.Value = a.prefs.WantRunning
				a.prefs = nil
			}
			a.mu.Unlock()
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
				gtx := layout.NewContext(&ops, e)
				events := ui.layout(gtx, e.Insets, state)
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

	state.Peers = nil
	netMap := state.backend.NetworkMap
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
	myID := state.backend.NetworkMap.User
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
		lName := lp.DisplayName(lp.User == myID)
		rName := rp.DisplayName(rp.User == myID)
		return lName < rName || lName == rName && lp.ID < rp.ID
	})
	state.Peers = peers
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
