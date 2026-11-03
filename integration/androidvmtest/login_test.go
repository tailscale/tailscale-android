// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"flag"
	"fmt"
	"net"
	"net/http/httptest"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"

	"tailscale.com/tstest/integration/testcontrol"
	"tailscale.com/types/logger"
)

var (
	loginNameRe = flag.String("android.login-name-re", `user-[0-9]+@fake-control\.example\.net`,
		"regexp matching the login name of the profile shown in the app's settings after the "+
			"auth-key login (testcontrol numbers users per registration)")
	loginTaps = flag.Int("android.login-taps", 3,
		"number of rapid 'Reauthenticate' taps in the interactive login test; >1 reproduces the "+
			"tailscale/tailscale#18304 race on unfixed builds")
)

// TestAndroidInteractiveLogin drives an interactive login (the user switcher's
// "Reauthenticate" row) against a local testcontrol server from the app's UI
// on the emulator.
//
// It reproduces tailscale/tailscale#18304: every tap of "Log in" (or
// "Reauthenticate") runs editPrefs()+start(), and the Go backend's start()
// tears down the control client and cancels any in-flight register request,
// so rapid taps keep cancelling the request that would produce the auth URL,
// and the login appears to do nothing. On unfixed builds the test observes
// the repeated backend restarts (each tap runs the whole login chain) and the
// backend health subsystem's "context canceled" login errors being ignored;
// on fixed builds it observes a single login chain, the login state machine's
// phase transitions, and a completed end-to-end login (auth URL delivered,
// authenticated via testcontrol, node re-registered).
//
// testcontrol is configured with RequireAuth: every registration (including
// the seed auth-key login) is handed an auth URL which authenticates the node
// key once visited. The test completes the login the way the browser would,
// by visiting the auth URL that the app's control client logs
// ("control: AuthURL is ..."): the control client multiplexes register
// requests over its ts2021 noise connection, so an HTTP-level wrapper cannot
// observe them.
func TestAndroidInteractiveLogin(t *testing.T) {
	if !linuxAndAdbReady(t) {
		return
	}
	if *loginTaps < 1 {
		t.Fatalf("--android.login-taps must be >= 1")
	}

	advertisedDERPHost := *derpHost
	if advertisedDERPHost == "" {
		advertisedDERPHost = nonLoopbackIPv4(t)
	}
	t.Logf("test DERP/STUN host: %s", advertisedDERPHost)
	derpMap := runDERPAndSTUN(t, advertisedDERPHost)

	// RequireAuth hands every registration an auth URL (which authenticates
	// the node key once visited) instead of authorizing the node directly, so
	// the app's auth-URL (BrowseToURL) flow is exercised. RequireAuthKey
	// cannot be used here: it rejects keyless interactive registers outright
	// ("invalid authkey").
	control := &testcontrol.Server{
		Logf:        logger.WithPrefix(t.Logf, "testcontrol: "),
		DERPMap:     derpMap,
		RequireAuth: true,
		AllOnline:   true,
	}
	controlServer := httptest.NewServer(control)
	t.Cleanup(controlServer.Close)

	controlURL := rewriteHost(t, controlServer.URL, *emulatorHost)
	control.ExplicitBaseURL = controlURL
	t.Logf("test control URL for Android: %s", controlURL)

	adb := adbRunner{path: *adbPath, serial: *adbSerial}
	adb.run(t, "wait-for-device")
	adb.runAllowError(t, "uninstall", androidPackage) // avoid INSTALL_FAILED_VERSION_DOWNGRADE
	adb.run(t, "install", "-r", "-t", *apkPath)
	t.Cleanup(func() { adb.runAllowError(t, "shell", "am", "force-stop", androidPackage) })
	adb.run(t, "shell", "appops", "set", androidPackage, "ACTIVATE_VPN", "allow")
	adb.run(t, "shell", "am", "force-stop", androidPackage)
	adb.run(t, "shell", "pm", "clear", androidPackage)
	adb.run(t, "logcat", "-c")
	// The app launches the auth URL in the device's browser; without a browser
	// the launch is a no-op (the app reports it), and the browser cannot steal
	// the foreground from the app under test.
	adb.runAllowError(t, "shell", "pm", "uninstall", "--user", "0", "com.android.chrome")
	t.Cleanup(func() {
		adb.runAllowError(t, "shell", "cmd", "package", "install-existing", "com.android.chrome")
	})

	// Seed the app with the testcontrol control URL and log the app in with an
	// auth key via the debug-only integration login receiver, so that the UI
	// login below runs against testcontrol rather than the real control server
	// (a plain UI login uses the prefs' ControlURL, which is the production
	// login server on a fresh install; logging out via the UI would reset it).
	// RequireAuth hands the client an auth URL even for the auth-key login;
	// complete it by visiting it.
	adb.run(t,
		"shell", "am", "broadcast",
		"-a", loginAction,
		"-n", androidPackage+"/.IPNReceiver",
		"--include-stopped-packages",
		"--es", "control_url", controlURL,
		"--es", "auth_key", authKey,
	)
	seedURL := waitForNewAuthURL(t, adb, map[string]bool{}, "seed auth-key login")
	completeAuth(t, control, seedURL)
	waitForNodeCountAtLeast(t, control, 1)
	t.Logf("seed auth-key login complete")

	// Fresh installs show the intro screen first; the permission/connect step
	// may auto-complete because ACTIVATE_VPN was pre-granted via appops.
	adb.run(t, "shell", "am", "start", "-n", androidPackage+"/.MainActivity")
	tapTextIfPresent(t, adb, "Get Started", 60*time.Second)

	// On the first "Running" view the app prompts for missing permissions
	// (notifications); answer it and the system dialog it opens, if any.
	tapTextIfPresent(t, adb, "Continue", 60*time.Second)
	tapTextIfPresent(t, adb, "Allow", 30*time.Second) // system POST_NOTIFICATIONS dialog

	// Open the user switcher and spam its "Reauthenticate" row. This is the
	// exact flow that users hit in tailscale/tailscale#18304 ("spam tap the
	// Reauthenticate option until the login window appears"). The settings
	// screen is opened via the app's navigation deep link rather than by
	// hunting for the header avatar.
	adb.run(t, "shell", "am", "start",
		"-a", "android.intent.action.VIEW",
		"-d", "tailscale://navigate/settings")
	tapTextMatchIfPresent(t, adb, *loginNameRe, 60*time.Second)

	// Each login chain (editPrefs()+start()+startLoginInteractive()) is one
	// "localapi: [POST] /localapi/v0/start" log line; remember how many ran so
	// far (the seed auth-key login is one) to count the chains the spam below
	// runs.
	const startMarker = "localapi: [POST] /localapi/v0/start"
	startsBefore := strings.Count(logcatDump(t, adb), startMarker)
	authURLsSeen := authURLsFromLogcat(logcatDump(t, adb))

	// Find (and wait for) the "Reauthenticate" row.
	if !waitForText(t, adb, "Reauthenticate", 60*time.Second) {
		logcat := logcatDump(t, adb)
		t.Logf("logcat tail:\n%s", lastLines(logcat, 200))
		t.Fatalf("'Reauthenticate' row did not appear")
	}

	// Spam it to reproduce the repeated-tap race. Each tap re-locates the row
	// first: when the login makes progress the app relaunches MainActivity to
	// bring itself back over the browser, which pops the UI back to the main
	// view; re-navigate to the user switcher in that case.
	for i := 0; i < *loginTaps; i++ {
		reauth := findTextBounds(t, adb, "Reauthenticate")
		if reauth == nil {
			adb.run(t, "shell", "am", "start",
				"-a", "android.intent.action.VIEW",
				"-d", "tailscale://navigate/settings")
			if !tapTextMatchIfPresentWait(t, adb, *loginNameRe, 30*time.Second) {
				dumpTextElements(t, adb)
				t.Fatalf("could not re-navigate to the 'Reauthenticate' row before tap %d", i+1)
			}
			reauth = findTextBounds(t, adb, "Reauthenticate")
			if reauth == nil {
				dumpTextElements(t, adb)
				t.Fatalf("'Reauthenticate' row not found after re-navigation before tap %d", i+1)
			}
		}
		adb.run(t, "shell", "input", "tap",
			fmt.Sprint((reauth[0]+reauth[2])/2), fmt.Sprint((reauth[1]+reauth[3])/2))
	}

	// The login must make progress: a new auth URL must be handed to the
	// client (the interactive login regenerates the node key, so testcontrol
	// requires authentication again).
	authURL := waitForNewAuthURL(t, adb, authURLsSeen, "interactive login")
	t.Logf("auth URL delivered: %s", authURL)

	// Finish the login the way the browser would: completing the auth URL
	// authenticates the node key with testcontrol, which unblocks the client's
	// followup register request.
	completeAuth(t, control, authURL)

	// The login must complete: the reauthenticated node registers with
	// testcontrol (the seed login's node, and the new node key of the
	// interactive login).
	waitForNodeCountAtLeast(t, control, 2)
	t.Logf("interactive login completed (node re-registered with testcontrol)")

	logcat := logcatDump(t, adb)
	starts := strings.Count(logcat, startMarker)
	chains := starts - startsBefore
	canceled := strings.Count(logcat, "context canceled")
	t.Logf("login chain markers: login chains run by the taps=%d (total %d), "+
		"'context canceled' log lines=%d", chains, starts, canceled)

	// The login state machine's phase transitions must be observable
	// (communicated progress). These markers only exist on fixed builds.
	for _, marker := range []string{
		"login: phase -> STARTING",
		"login: phase -> AWAITING_URL",
		"login: phase -> AUTHENTICATING",
		"login: phase -> IDLE (login finished after auth)",
	} {
		if !strings.Contains(logcat, marker) {
			t.Errorf("logcat missing login state machine transition %q (see tailscale/tailscale#18304)", marker)
		}
	}

	// Repeated taps must not have run the login chain (editPrefs+start)
	// repeatedly: each start() tears down the control client and cancels the
	// in-flight register request, which is the tailscale/tailscale#18304
	// race. Exactly one chain run is expected: the first tap; later taps can
	// only re-request the auth URL via startLoginInteractive().
	if chains != 1 {
		t.Errorf("repeated taps ran the login chain %d times; expected exactly 1", chains)
	}
	// Some 'context canceled' lines can remain on fixed builds (a re-request
	// tap replaces an in-flight register, as does the LoginDefault race with
	// the first tap's register), but they must not have prevented the login
	// from completing; the completion assertions above cover that.
}

// TestAndroidPreLoginHealthSurfacing verifies that a login failure reported by
// the backend's health subsystem (the login-state warnable, which carries the
// last login error) is surfaced by the app while the user is not logged in.
//
// Before tailscale/tailscale#18304 the app dropped all health warnings unless
// the IPN state was Running, so login failures like a canceled or refused
// register request were invisible pre-login. The login is driven against an
// unreachable control URL via the debug-only integration login receiver.
func TestAndroidPreLoginHealthSurfacing(t *testing.T) {
	if !linuxAndAdbReady(t) {
		return
	}

	// Port 1 on the host: nothing listens there, so register requests fail
	// with connection refused, which the backend reports as a login error.
	deadControlURL := "http://" + net.JoinHostPort(*emulatorHost, "1")

	adb := adbRunner{path: *adbPath, serial: *adbSerial}
	adb.run(t, "wait-for-device")
	adb.runAllowError(t, "uninstall", androidPackage) // avoid INSTALL_FAILED_VERSION_DOWNGRADE
	adb.run(t, "install", "-r", "-t", *apkPath)
	t.Cleanup(func() { adb.runAllowError(t, "shell", "am", "force-stop", androidPackage) })
	adb.run(t, "shell", "appops", "set", androidPackage, "ACTIVATE_VPN", "allow")
	adb.run(t, "shell", "am", "force-stop", androidPackage)
	adb.run(t, "shell", "pm", "clear", androidPackage)
	adb.run(t, "logcat", "-c")

	adb.run(t,
		"shell", "am", "broadcast",
		"-a", loginAction,
		"-n", androidPackage+"/.IPNReceiver",
		"--include-stopped-packages",
		"--es", "control_url", deadControlURL,
		"--es", "auth_key", authKey,
	)

	// The failed login must surface through the health subsystem: the
	// HealthNotifier processes the backend's health notification and records
	// the login-state warning while the app is not logged in.
	marker := "Health updated: [login-state]"
	if !waitForLogcat(t, adb, marker, *waitTimeout) {
		logcat := logcatDump(t, adb)
		t.Logf("logcat tail:\n%s", lastLines(logcat, 400))
		t.Fatalf("login failures were not surfaced pre-login: %q not found in logcat within %v", marker, *waitTimeout)
	}
	t.Logf("pre-login health surfaced: %q found in logcat", marker)
}

func linuxAndAdbReady(t *testing.T) bool {
	t.Helper()
	if goOS := os.Getenv("GOOS"); goOS != "" && goOS != "linux" {
		t.Skipf("Android integration test currently only runs on Linux (GOOS=%s)", goOS)
	}
	if _, err := exec.LookPath(*adbPath); err != nil {
		t.Skipf("adb not found: %v", err)
	}
	if *apkPath == "" {
		t.Skip("set --android.apk or TAILSCALE_ANDROID_APK to run")
	}
	return true
}

// waitForNodeCountAtLeast waits until testcontrol has at least n nodes.
func waitForNodeCountAtLeast(t *testing.T, control *testcontrol.Server, n int) {
	t.Helper()
	deadline := time.Now().Add(*waitTimeout)
	for time.Now().Before(deadline) {
		if nodes := control.AllNodes(); len(nodes) >= n {
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
	t.Fatalf("timed out after %v waiting for at least %d nodes; testcontrol has %d nodes",
		*waitTimeout, n, control.NumNodes())
}

// completeAuth finishes an interactive login the way the browser would: it
// marks the auth URL's path as authenticated with testcontrol, which
// authenticates the node key and unblocks the client's followup register
// request.
func completeAuth(t *testing.T, control *testcontrol.Server, url string) {
	t.Helper()
	if !control.CompleteAuth(url) {
		t.Fatalf("completing auth for %s: auth path unknown to testcontrol", url)
	}
}

// authURLRe matches the control client's log line for a delivered auth URL.
var authURLRe = regexp.MustCompile(`AuthURL is (https?://\S+)`)

// authURLsFromLogcat returns the set of auth URLs the app's control client has
// logged as delivered so far.
func authURLsFromLogcat(logcat string) map[string]bool {
	urls := map[string]bool{}
	for _, m := range authURLRe.FindAllStringSubmatch(logcat, -1) {
		urls[m[1]] = true
	}
	return urls
}

// waitForNewAuthURL waits until the app's control client logs an auth URL
// delivery that is not in seen, and returns that URL.
func waitForNewAuthURL(t *testing.T, adb adbRunner, seen map[string]bool, what string) string {
	t.Helper()
	deadline := time.Now().Add(*waitTimeout)
	for time.Now().Before(deadline) {
		logcat := logcatDump(t, adb)
		matches := authURLRe.FindAllStringSubmatch(logcat, -1)
		for _, m := range matches {
			if !seen[m[1]] {
				return m[1]
			}
		}
		time.Sleep(500 * time.Millisecond)
	}
	logcat := logcatDump(t, adb)
	t.Logf("logcat tail:\n%s", lastLines(logcat, 400))
	t.Fatalf("no new auth URL was delivered to the client for the %s within %v", what, *waitTimeout)
	return ""
}

// waitForText waits until a UI element with the given text is present.
func waitForText(t *testing.T, adb adbRunner, text string, timeout time.Duration) bool {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if findTextBounds(t, adb, text) != nil {
			return true
		}
		time.Sleep(2 * time.Second)
	}
	return false
}

// tapTextIfPresent taps a UI element with the given text as soon as it
// appears; it is not an error if the element never appears.
func tapTextIfPresent(t *testing.T, adb adbRunner, text string, timeout time.Duration) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if b := findTextBounds(t, adb, text); b != nil {
			t.Logf("tapping %q at center of %v", text, b)
			adb.run(t, "shell", "input", "tap", fmt.Sprint((b[0]+b[2])/2), fmt.Sprint((b[1]+b[3])/2))
			return
		}
		time.Sleep(2 * time.Second)
	}
	t.Logf("%q never appeared; continuing", text)
	dumpTextElements(t, adb)
}

// tapTextMatchIfPresent taps the first UI element whose text or content
// description matches the given regular expression, as soon as it appears; it
// is not an error if no match ever appears.
func tapTextMatchIfPresent(t *testing.T, adb adbRunner, pattern string, timeout time.Duration) {
	t.Helper()
	re := regexp.MustCompile(pattern)
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if b := findTextMatchBounds(t, adb, re); b != nil {
			t.Logf("tapping match of %q at center of %v", pattern, b)
			adb.run(t, "shell", "input", "tap", fmt.Sprint((b[0]+b[2])/2), fmt.Sprint((b[1]+b[3])/2))
			return
		}
		time.Sleep(2 * time.Second)
	}
	t.Logf("no UI element matching %q appeared; continuing", pattern)
	dumpTextElements(t, adb)
}

// tapTextMatchIfPresentWait is like tapTextMatchIfPresent but reports whether
// a match was found and tapped.
func tapTextMatchIfPresentWait(t *testing.T, adb adbRunner, pattern string, timeout time.Duration) bool {
	t.Helper()
	re := regexp.MustCompile(pattern)
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if b := findTextMatchBounds(t, adb, re); b != nil {
			t.Logf("tapping match of %q at center of %v", pattern, b)
			adb.run(t, "shell", "input", "tap", fmt.Sprint((b[0]+b[2])/2), fmt.Sprint((b[1]+b[3])/2))
			return true
		}
		time.Sleep(2 * time.Second)
	}
	return false
}

// findTextBounds returns the bounds [x1,y1,x2,y2] of the first UI element with
// the given text or content description, or nil if none is present.
func findTextBounds(t *testing.T, adb adbRunner, text string) []int {
	t.Helper()
	return findTextMatchBounds(t, adb, regexp.MustCompile(`^`+regexp.QuoteMeta(text)+`$`))
}

// findTextMatchBounds returns the bounds of the first UI element whose text or
// content description matches re, or nil if none is present.
func findTextMatchBounds(t *testing.T, adb adbRunner, re *regexp.Regexp) []int {
	t.Helper()
	dump := uiDump(t, adb)
	// Nodes look like:
	//   <node ... text="Log in" ... bounds="[68,1354][1012,1432]" .../>
	// Attribute order within a node is not guaranteed, so match per node.
	nodeRe := regexp.MustCompile(`<node[^>]*>`)
	textRe := regexp.MustCompile(`text="([^"]*)"`)
	descRe := regexp.MustCompile(`content-desc="([^"]*)"`)
	boundsRe := regexp.MustCompile(`bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"`)
	for _, node := range nodeRe.FindAllString(dump, -1) {
		var labels []string
		if m := textRe.FindStringSubmatch(node); m != nil {
			labels = append(labels, m[1])
		}
		if m := descRe.FindStringSubmatch(node); m != nil {
			labels = append(labels, m[1])
		}
		matched := false
		for _, label := range labels {
			if re.MatchString(label) {
				matched = true
				break
			}
		}
		if !matched {
			continue
		}
		m := boundsRe.FindStringSubmatch(node)
		if m == nil {
			continue
		}
		x1, _ := strconv.Atoi(m[1])
		y1, _ := strconv.Atoi(m[2])
		x2, _ := strconv.Atoi(m[3])
		y2, _ := strconv.Atoi(m[4])
		return []int{x1, y1, x2, y2}
	}
	return nil
}

// dumpTextElements logs the texts and content descriptions currently visible
// in the UI, for diagnosing UI-navigation failures.
func dumpTextElements(t *testing.T, adb adbRunner) {
	t.Helper()
	dump := uiDump(t, adb)
	nodeRe := regexp.MustCompile(`<node[^>]*>`)
	textRe := regexp.MustCompile(`text="([^"]*)"`)
	descRe := regexp.MustCompile(`content-desc="([^"]*)"`)
	t.Logf("visible UI elements:")
	for _, node := range nodeRe.FindAllString(dump, -1) {
		if m := textRe.FindStringSubmatch(node); m != nil && m[1] != "" {
			t.Logf("  text=%q", m[1])
		}
		if m := descRe.FindStringSubmatch(node); m != nil && m[1] != "" {
			t.Logf("  content-desc=%q", m[1])
		}
	}
}

func uiDump(t *testing.T, adb adbRunner) string {
	t.Helper()
	adb.runAllowError(t, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
	out, err := adb.runOutputAllowError("shell", "cat", "/sdcard/ui.xml")
	if err != nil || out == "" {
		return ""
	}
	return out
}

// waitForLogcat waits until the logcat contains the given marker.
func waitForLogcat(t *testing.T, adb adbRunner, marker string, timeout time.Duration) bool {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if strings.Contains(logcatDump(t, adb), marker) {
			return true
		}
		time.Sleep(2 * time.Second)
	}
	return false
}

func logcatDump(t *testing.T, adb adbRunner) string {
	t.Helper()
	out, err := adb.runOutputAllowError("logcat", "-d")
	if err != nil {
		t.Logf("logcat dump failed: %v", err)
		return ""
	}
	return out
}

func lastLines(s string, n int) string {
	lines := strings.Split(strings.TrimRight(s, "\n"), "\n")
	if len(lines) > n {
		lines = lines[len(lines)-n:]
	}
	return strings.Join(lines, "\n")
}
