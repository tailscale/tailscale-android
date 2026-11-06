// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"bytes"
	"context"
	"encoding/xml"
	"errors"
	"fmt"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"
)

const (
	uiDumpPath    = "/data/local/tmp/tailscale-ui.xml"
	editTextClass = "android.widget.EditText"
)

type uiNode struct {
	Text       string   `xml:"text,attr"`
	ResourceID string   `xml:"resource-id,attr"`
	Class      string   `xml:"class,attr"`
	Package    string   `xml:"package,attr"`
	Desc       string   `xml:"content-desc,attr"`
	Checkable  bool     `xml:"checkable,attr"`
	Checked    bool     `xml:"checked,attr"`
	Focused    bool     `xml:"focused,attr"`
	Bounds     string   `xml:"bounds,attr"`
	Children   []uiNode `xml:"node"`
}

type uiScreen struct {
	raw   string
	nodes []uiNode
}

func parseUIDump(data string) (uiScreen, error) {
	var h struct {
		Nodes []uiNode `xml:"node"`
	}
	if err := xml.Unmarshal([]byte(data), &h); err != nil {
		return uiScreen{}, fmt.Errorf("parsing UI dump: %w", err)
	}
	s := uiScreen{raw: data}
	var walk func([]uiNode)
	walk = func(nodes []uiNode) {
		for _, n := range nodes {
			s.nodes = append(s.nodes, n)
			walk(n.Children)
		}
	}
	walk(h.Nodes)
	if len(s.nodes) == 0 {
		return uiScreen{}, errors.New("UI dump has no nodes")
	}
	return s, nil
}

// checkDumpOutput rejects a failed dump, since uiautomator then leaves the previous file in place.
func checkDumpOutput(out string) error {
	if !strings.Contains(out, "UI hierchary dumped to:") {
		return fmt.Errorf("uiautomator dump failed: %s", strings.TrimSpace(out))
	}
	return nil
}

func (s uiScreen) find(match func(uiNode) bool) (uiNode, bool) {
	for _, n := range s.nodes {
		if match(n) {
			return n, true
		}
	}
	return uiNode{}, false
}

func (s uiScreen) has(match func(uiNode) bool) bool {
	_, ok := s.find(match)
	return ok
}

func (s uiScreen) pkg() string {
	return s.nodes[0].Package
}

func withText(text string) func(uiNode) bool {
	return func(n uiNode) bool { return n.Text == text }
}

func withDesc(desc string) func(uiNode) bool {
	return func(n uiNode) bool { return n.Desc == desc }
}

func withClass(class string) func(uiNode) bool {
	return func(n uiNode) bool { return n.Class == class }
}

func withResourceIDSuffix(suffix string) func(uiNode) bool {
	return func(n uiNode) bool { return strings.HasSuffix(n.ResourceID, suffix) }
}

var boundsRE = regexp.MustCompile(`^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$`)

func (n uiNode) center() (x, y int, err error) {
	m := boundsRE.FindStringSubmatch(n.Bounds)
	if m == nil {
		return 0, 0, fmt.Errorf("unexpected bounds %q", n.Bounds)
	}
	var v [4]int
	for i := range v {
		v[i], _ = strconv.Atoi(m[i+1])
	}
	return (v[0] + v[2]) / 2, (v[1] + v[3]) / 2, nil
}

func dumpUI(adb adbRunner) (uiScreen, error) {
	adb.runOutputAllowError("shell", "rm", "-f", uiDumpPath)
	out, err := adb.runOutputAllowError("shell", "uiautomator", "dump", uiDumpPath)
	if err != nil {
		return uiScreen{}, fmt.Errorf("uiautomator dump: %v\n%s", err, out)
	}
	if err := checkDumpOutput(out); err != nil {
		return uiScreen{}, err
	}
	data, err := adb.runOutputAllowError("exec-out", "cat", uiDumpPath)
	if err != nil {
		return uiScreen{}, fmt.Errorf("reading UI dump: %v\n%s", err, data)
	}
	return parseUIDump(data)
}

func pollUI(t *testing.T, adb adbRunner, what string, step func(uiScreen) bool) uiScreen {
	t.Helper()
	deadline := time.Now().Add(*waitTimeout)
	var last uiScreen
	var lastErr error
	for time.Now().Before(deadline) {
		s, err := dumpUI(adb)
		if err != nil {
			lastErr = err
		} else {
			last = s
			if step(s) {
				return s
			}
		}
		time.Sleep(500 * time.Millisecond)
	}
	logUIDiagnostics(t, adb, last, lastErr)
	t.Fatalf("timed out after %v waiting for %s", *waitTimeout, what)
	return uiScreen{}
}

func waitForNode(t *testing.T, adb adbRunner, what string, match func(uiNode) bool) uiNode {
	t.Helper()
	var found uiNode
	pollUI(t, adb, what, func(s uiScreen) bool {
		n, ok := s.find(match)
		found = n
		return ok
	})
	return found
}

func tap(t *testing.T, adb adbRunner, n uiNode) {
	t.Helper()
	x, y, err := n.center()
	if err != nil {
		t.Fatal(err)
	}
	adb.run(t, "shell", "input", "tap", strconv.Itoa(x), strconv.Itoa(y))
}

func typeInto(t *testing.T, adb adbRunner, field uiNode, text string) {
	t.Helper()
	tap(t, adb, field)
	waitForNode(t, adb, "a focused text field", func(n uiNode) bool { return n.Class == editTextClass && n.Focused })
	adb.run(t, "shell", "input", "text", text)
	waitForNode(t, adb, fmt.Sprintf("a text field showing %q", text), func(n uiNode) bool { return n.Class == editTextClass && n.Text == text })
}

func prepareDeviceForUI(t *testing.T, adb adbRunner) {
	t.Helper()
	adb.run(t, "shell", "svc", "power", "stayon", "true")
	adb.run(t, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
	adb.run(t, "shell", "wm", "dismiss-keyguard")
	for _, setting := range []string{"window_animation_scale", "transition_animation_scale", "animator_duration_scale"} {
		adb.run(t, "shell", "settings", "put", "global", setting, "0")
	}
}

// keepAllRecordings also keeps the videos of passing cases, for use on a dev branch only.
const keepAllRecordings = false

func recordScreen(t *testing.T, adb adbRunner) {
	t.Helper()
	const remote = "/data/local/tmp/tailscale-screen.mp4"
	adb.runOutputAllowError("shell", "rm", "-f", remote)
	// t.Context ends before cleanups run, so the recorder gets its own deadline to finish the file.
	ctx, cancel := context.WithTimeout(context.Background(), 4*time.Minute)
	var out bytes.Buffer
	rec := exec.CommandContext(ctx, adb.path, adb.args("shell", "screenrecord", "--size", "720x1280", "--bit-rate", "2000000", remote)...)
	rec.Stdout, rec.Stderr = &out, &out
	if err := rec.Start(); err != nil {
		cancel()
		t.Logf("screenrecord did not start: %v", err)
		return
	}
	t.Cleanup(func() {
		defer cancel()
		adb.runOutputAllowError("shell", "pkill", "-INT", "screenrecord")
		timer := time.AfterFunc(15*time.Second, cancel)
		err := rec.Wait()
		timer.Stop()
		if !t.Failed() && !keepAllRecordings {
			return
		}
		if err != nil {
			t.Logf("screenrecord: %v\n%s", err, out.Bytes())
		}
		if pulled, err := adb.runOutputAllowError("pull", remote, filepath.Join(t.ArtifactDir(), "screen.mp4")); err != nil {
			t.Logf("adb pull %s: %v\n%s", remote, err, pulled)
		}
	})
}

func logUIDiagnostics(t *testing.T, adb adbRunner, last uiScreen, lastErr error) {
	t.Helper()
	if lastErr != nil {
		t.Logf("last UI dump error: %v", lastErr)
	}
	if len(last.nodes) > 0 {
		var visible []string
		for _, n := range last.nodes {
			if n.Text != "" {
				visible = append(visible, fmt.Sprintf("text=%q", n.Text))
			}
			if n.Desc != "" {
				visible = append(visible, fmt.Sprintf("desc=%q", n.Desc))
			}
		}
		t.Logf("last screen (package %s): %s\n%s", last.pkg(), strings.Join(visible, " "), last.raw)
	}
	for _, args := range [][]string{
		{"logcat", "-d", "-t", "500"},
		{"logcat", "-d", "-b", "crash"},
		{"logcat", "-d", "-b", "system", "-s", "ActivityTaskManager"},
		{"shell", "dumpsys activity activities | grep -E 'ResumedActivity'"},
	} {
		out, err := adb.runOutputAllowError(args...)
		t.Logf("adb %v (err=%v):\n%s", args, err, out)
	}
}

const sampleUIDump = `<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0"><node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.example.test" content-desc="" checkable="false" checked="false" focused="false" bounds="[0,0][1080,2400]"><node index="0" text="Title" resource-id="" class="android.widget.TextView" package="com.example.test" content-desc="" checkable="false" checked="false" focused="false" bounds="[84,876][996,953]" /><node index="1" text="" resource-id="" class="android.widget.EditText" package="com.example.test" content-desc="Field" checkable="false" checked="false" focused="true" bounds="[84,1000][996,1100]" /><node index="2" text="" resource-id="" class="android.widget.Switch" package="com.example.test" content-desc="" checkable="true" checked="true" focused="false" bounds="[84,1200][300,1300]"><node index="0" text="On" resource-id="" class="android.widget.TextView" package="com.example.test" content-desc="" checkable="false" checked="false" focused="false" bounds="[100,1220][200,1280]" /></node><node index="3" text="OK" resource-id="com.example.test:id/ok" class="android.widget.Button" package="com.example.test" content-desc="" checkable="false" checked="false" focused="false" bounds="[780,1404][954,1530]" /></node></hierarchy>`

func TestParseUIDump(t *testing.T) {
	s, err := parseUIDump(sampleUIDump)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := len(s.nodes), 6; got != want {
		t.Fatalf("got %d nodes; want %d", got, want)
	}
	if got, want := s.pkg(), "com.example.test"; got != want {
		t.Errorf("pkg = %q; want %q", got, want)
	}

	ok, found := s.find(withResourceIDSuffix(":id/ok"))
	if !found || ok.Text != "OK" {
		t.Errorf("ok button = %+v, found=%v; want text OK", ok, found)
	}
	x, y, err := ok.center()
	if err != nil || x != 867 || y != 1467 {
		t.Errorf("ok button center = (%d, %d, %v); want (867, 1467, nil)", x, y, err)
	}
	if field, found := s.find(withClass(editTextClass)); !found || !field.Focused || field.Desc != "Field" {
		t.Errorf("EditText = %+v, found=%v; want focused with desc Field", field, found)
	}
	if !s.has(func(n uiNode) bool { return n.Checkable && n.Checked }) {
		t.Errorf("missing the checked switch")
	}
	if !s.has(withText("On")) {
		t.Errorf("missing a nested node")
	}
	if !s.has(withText("Title")) || !s.has(withDesc("Field")) {
		t.Errorf("missing Title text or Field desc")
	}
	if s.has(withText("missing")) {
		t.Errorf("found a node that is not in the dump")
	}

	if _, _, err := (uiNode{Bounds: "[1,2][3]"}).center(); err == nil {
		t.Errorf("center accepted malformed bounds")
	}
	if _, err := parseUIDump(`<hierarchy rotation="0"></hierarchy>`); err == nil {
		t.Errorf("parseUIDump accepted a dump with no nodes")
	}
	if _, err := parseUIDump("not xml"); err == nil {
		t.Errorf("parseUIDump accepted invalid XML")
	}

	if err := checkDumpOutput("UI hierchary dumped to: " + uiDumpPath + "\n"); err != nil {
		t.Errorf("checkDumpOutput rejected a successful dump: %v", err)
	}
	for _, out := range []string{"ERROR: could not get idle state.\n", "ERROR: null root node returned by UiTestAutomationBridge.\n"} {
		if err := checkDumpOutput(out); err == nil {
			t.Errorf("checkDumpOutput accepted %q", out)
		}
	}
}
