// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"tailscale.com/ipn/ipnstate"
	"tailscale.com/ipn/store/mem"
	"tailscale.com/tsnet"
	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
)

const browserPeerHostname = "tsnetbrowserpeer"

func logInToCustomControlServer(t *testing.T, e *androidEnv) {
	addAccountWithCustomServer(t, e)
	addAccountWithAuthKey(t, e)
	allowNotifications(t, e)
	waitForConnected(t, e)
	waitForNodeCount(t, e.control, 2)
	e.checkPeerTraffic(t)
	checkSettingsShowServer(t, e)
}

func openAccountsMenuItem(t *testing.T, e *androidEnv, item string) {
	t.Helper()
	tap(t, e.adb, waitForAppNode(t, e, "the settings button", withDesc(settingsDesc)))
	tap(t, e.adb, waitForAppNode(t, e, "the Accounts row", withText(appString(t, strAccounts))))
	tap(t, e.adb, waitForAppNode(t, e, "the Accounts menu", withDesc(accountsMenuDesc)))
	tap(t, e.adb, waitForAppNode(t, e, item, withText(item)))
}

func addAccount(t *testing.T, e *androidEnv, menuItem, screenText, value string) {
	t.Helper()
	openAccountsMenuItem(t, e, appString(t, menuItem))
	text := appString(t, screenText)
	waitForAppNode(t, e, text, withText(text))
	typeInto(t, e.adb, waitForAppNode(t, e, "the text field", withClass(editTextClass)), value)
	tap(t, e.adb, waitForAppNode(t, e, "the Add account button", withText(appString(t, strAddAccount))))
}

func addAccountWithCustomServer(t *testing.T, e *androidEnv) {
	t.Helper()
	addAccount(t, e, strCustomControlMenu, strCustomControlDesc, e.controlURL)
	loginToJoin := appString(t, strLoginToJoin)
	failed := appString(t, strAddProfileFailed)
	n := waitForAppNode(t, e, "the main screen after adding the server", func(n uiNode) bool {
		return n.Text == loginToJoin || n.Text == failed
	})
	if n.Text == failed {
		t.Fatalf("adding %s showed %q", e.controlURL, failed)
	}
}

func addAccountWithAuthKey(t *testing.T, e *androidEnv) {
	t.Helper()
	addAccount(t, e, strAuthKeyMenu, strAuthKeyExplanation, authKey)
}

func allowNotifications(t *testing.T, e *androidEnv) {
	t.Helper()
	tap(t, e.adb, waitForAppNode(t, e, "the notifications prompt", withText(appString(t, strContinue))))
	tap(t, e.adb, waitForNode(t, e.adb, "the notifications permission dialog", withResourceIDSuffix(":id/permission_allow_button")))
}

func checkSettingsShowServer(t *testing.T, e *androidEnv) {
	t.Helper()
	u, err := url.Parse(e.controlURL)
	if err != nil {
		t.Fatal(err)
	}
	openDeepLink(t, e, settingsDeepLink)
	waitForAppNode(t, e, "the account row showing "+u.Hostname(), withText(u.Hostname()))
}

func logOut(t *testing.T, e *androidEnv) {
	login := e.peerLogin(t)
	openAccounts(t, e)
	button := appString(t, strLogOut)
	tap(t, e.adb, waitForAppNode(t, e, button, withText(button)))
	waitForMainText(t, e, appString(t, strLoginToJoin))
	openAccounts(t, e)
	add := appString(t, strAddAnotherAccount)
	pollUI(t, e.adb, login+" gone from the Accounts list", func(s uiScreen) bool {
		return s.has(withText(add)) && !s.has(withText(login)) && len(checkedNodes(s)) == 0
	})
}

func logIn(t *testing.T, e *androidEnv, b *browserTailnet) string {
	b.requireLoginPage(t, e)
	showMainScreen(t, e)
	visits := b.visits.Load()
	addAccount(t, e, strCustomControlMenu, strCustomControlDesc, b.controlURL)
	b.waitForVisits(t, visits+1)
	showMainScreen(t, e)
	waitForConnected(t, e)
	login := e.waitForNewIdentity(t, b, "")
	checkCurrentAccount(t, e, login)
	return login
}

func fastUserSwitching(t *testing.T, e *androidEnv, b *browserTailnet, first string) {
	openAccounts(t, e)
	add := appString(t, strAddAnotherAccount)
	tap(t, e.adb, waitForAppNode(t, e, add, withText(add)))
	showMainScreen(t, e)
	visits := b.visits.Load()
	addAccount(t, e, strCustomControlMenu, strCustomControlDesc, b.controlURL)
	b.waitForVisits(t, visits+1)
	showMainScreen(t, e)
	waitForConnected(t, e)
	second := e.waitForNewIdentity(t, b, first)
	checkCurrentAccount(t, e, second)
	for _, login := range []string{first, second} {
		switchToAccount(t, e, login)
		e.waitForProbeBody(t, b.peerURL, login)
		checkCurrentAccount(t, e, login)
	}
}

// peerLogin returns the tsnet peer's login, which the phone shares on this tailnet.
func (e *androidEnv) peerLogin(t *testing.T) string {
	t.Helper()
	st, err := e.peerClient(t).Status(context.Background())
	if err != nil {
		t.Fatalf("tsnet peer Status: %v", err)
	}
	return st.User[st.Self.UserID].LoginName
}

func openAccounts(t *testing.T, e *androidEnv) {
	t.Helper()
	openDeepLink(t, e, settingsDeepLink)
	settings := appString(t, strSettingsTitle)
	waitForAppNode(t, e, settings, withText(settings))
	// The row shows the test server's host when signed in and "Accounts" when not.
	accounts := appString(t, strAccounts)
	tap(t, e.adb, waitForAppNode(t, e, "the Settings account row", func(n uiNode) bool {
		return n.Text == *emulatorHost || n.Text == accounts
	}))
	add := appString(t, strAddAnotherAccount)
	waitForAppNode(t, e, add, withText(add))
}

func switchToAccount(t *testing.T, e *androidEnv, login string) {
	t.Helper()
	openAccounts(t, e)
	tap(t, e.adb, waitForAppNode(t, e, login+" in the Accounts list", withText(login)))
	waitForMainText(t, e, appString(t, strConnected))
}

// waitForMainText waits for want, failing at once on the dialog a failed logout or switch shows.
func waitForMainText(t *testing.T, e *androidEnv, want string) {
	t.Helper()
	failed := appString(t, strLogoutFailed)
	n := waitForAppNode(t, e, want, func(n uiNode) bool { return n.Text == want || n.Text == failed })
	if n.Text == failed {
		t.Fatalf("the app showed %q while waiting for %q", failed, want)
	}
}

func checkCurrentAccount(t *testing.T, e *androidEnv, login string) {
	t.Helper()
	openAccounts(t, e)
	pollUI(t, e.adb, login+" marked as the current account", func(s uiScreen) bool {
		return onlyCurrent(s, login)
	})
}

// onlyCurrent reports whether login's row is the only one marked, which Compose reports as checked.
func onlyCurrent(s uiScreen, login string) bool {
	checked := checkedNodes(s)
	return len(checked) == 1 && checked[0].holds(withText(login))
}

func checkedNodes(s uiScreen) []uiNode {
	var checked []uiNode
	for _, n := range s.nodes {
		if n.Checked {
			checked = append(checked, n)
		}
	}
	return checked
}

type browserTailnet struct {
	control    *testcontrol.Server
	controlURL string
	visits     atomic.Int32
	peerURL    string
}

func (e *androidEnv) startBrowserTailnet(t *testing.T) *browserTailnet {
	t.Helper()
	b := &browserTailnet{control: &testcontrol.Server{
		Logf:        logger.WithPrefix(t.Logf, "browser testcontrol: "),
		DERPMap:     e.derpMap,
		RequireAuth: true,
		AllOnline:   true,
	}}
	mux := http.NewServeMux()
	mux.Handle("/", b.control)
	// testcontrol has no sign-in page, so visiting one completes the sign-in.
	mux.HandleFunc("/auth/", func(w http.ResponseWriter, r *http.Request) {
		if !b.control.CompleteAuth(r.URL.Path) {
			http.NotFound(w, r)
			return
		}
		b.visits.Add(1)
		fmt.Fprint(w, "signed in")
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	b.controlURL = rewriteHost(t, srv.URL, *emulatorHost)
	b.control.ExplicitBaseURL = b.controlURL

	peer := &tsnet.Server{
		Dir:        t.TempDir(),
		ControlURL: srv.URL,
		Hostname:   browserPeerHostname,
		Ephemeral:  true,
		Logf:       logger.WithPrefix(t.Logf, "browser tsnet: "),
		Store:      new(mem.Store),
	}
	t.Cleanup(func() { peer.Close() })
	st := b.signIn(t, peer)
	lc, err := peer.LocalClient()
	if err != nil {
		t.Fatal(err)
	}
	ln, err := peer.Listen("tcp", ":80")
	if err != nil {
		t.Fatalf("browser tsnet Listen :80: %v", err)
	}
	t.Cleanup(func() { ln.Close() })
	go http.Serve(ln, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		who, err := lc.WhoIs(r.Context(), r.RemoteAddr)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		fmt.Fprint(w, who.UserProfile.LoginName)
	}))
	b.peerURL = fmt.Sprintf("http://%s/", st.TailscaleIPs[0])
	return b
}

func (b *browserTailnet) signIn(t *testing.T, s *tsnet.Server) *ipnstate.Status {
	t.Helper()
	if err := s.Start(); err != nil {
		t.Fatalf("browser tsnet Start: %v", err)
	}
	lc, err := s.LocalClient()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), *waitTimeout)
	defer cancel()
	for {
		if st, err := lc.Status(ctx); err == nil && st.AuthURL != "" && b.control.CompleteAuth(st.AuthURL) {
			break
		}
		if ctx.Err() != nil {
			t.Fatalf("browser tsnet got no sign-in URL in %v", *waitTimeout)
		}
		time.Sleep(500 * time.Millisecond)
	}
	st, err := s.Up(ctx)
	if err != nil {
		t.Fatalf("browser tsnet Up: %v", err)
	}
	return st
}

func (b *browserTailnet) requireLoginPage(t *testing.T, e *androidEnv) {
	t.Helper()
	out := e.adb.shellOutput(t, "cmd", "package", "query-activities", "-a", "android.intent.action.VIEW", "-d", b.controlURL+"/auth/x")
	if !strings.Contains(out, probePackage) {
		t.Fatalf("the stand-in browser doesn't open %s:\n%s", b.controlURL, out)
	}
}

func (b *browserTailnet) waitForVisits(t *testing.T, n int32) {
	t.Helper()
	for deadline := time.Now().Add(*waitTimeout); b.visits.Load() < n; time.Sleep(500 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatalf("the app opened %s's sign-in page %d times, want %d", b.controlURL, b.visits.Load(), n)
		}
	}
}

func (e *androidEnv) waitForNewIdentity(t *testing.T, b *browserTailnet, previous string) string {
	t.Helper()
	var got string
	for deadline := time.Now().Add(*waitTimeout); time.Now().Before(deadline); time.Sleep(time.Second) {
		got = e.probe(t, b.peerURL)
		if _, login, ok := strings.Cut(got, "exit=0\nstatus=200\nbody="); ok {
			if login = strings.TrimSuffix(login, "\n"); login != "" && login != previous {
				return login
			}
		}
	}
	t.Fatalf("GET %s never named an account other than %q; last probe result %q", b.peerURL, previous, got)
	return ""
}

func TestCurrentAccount(t *testing.T) {
	const dump = `<hierarchy rotation="0"><node package="com.tailscale.ipn" bounds="[0,0][1080,2400]"><node checked="%v" bounds="[0,300][1080,480]"><node text="user-2@fake-control.example.net" bounds="[150,320][800,370]" /></node><node clickable="true" checked="%v" bounds="[0,480][1080,660]"><node text="user-3@fake-control.example.net" bounds="[150,500][800,550]" /></node></node></hierarchy>`
	for _, tt := range []struct {
		name          string
		first, second bool
		want          bool
	}{
		{"its row", true, false, true},
		{"the other row", false, true, false},
		{"both rows", true, true, false},
		{"no row", false, false, false},
	} {
		s, err := parseUIDump(fmt.Sprintf(dump, tt.first, tt.second))
		if err != nil {
			t.Fatal(err)
		}
		if got := onlyCurrent(s, "user-2@fake-control.example.net"); got != tt.want {
			t.Errorf("with %s checked, onlyCurrent = %v; want %v", tt.name, got, tt.want)
		}
	}
}
