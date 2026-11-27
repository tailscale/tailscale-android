// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package androidvmtest

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"io"
	"path/filepath"
	"slices"
	"strings"
	"testing"
	"time"

	"tailscale.com/client/tailscale/apitype"
	"tailscale.com/tailcfg"
)

func taildropSend(t *testing.T, e *androidEnv) {
	name, content := taildropFile(t, "send", 4<<10)
	shareFromProbe(t, e, name, content)
	waitForPeerFile(t, e, name)
	e.checkPeerFile(t, name, []byte(content))
}

func taildropReceive(t *testing.T, e *androidEnv) {
	showMainScreen(t, e)
	waitForConnected(t, e)
	name, content := taildropFile(t, "receive", 32<<10)
	sent := e.peerSendsFile(t, name, []byte(content))
	pickTaildropFolder(t, e)
	if err := <-sent; err != nil {
		t.Fatalf("tsnet peer PushFile: %v", err)
	}
	checkDeviceFile(t, e, "/sdcard/Documents/"+name, content)
}

func shareFromProbe(t *testing.T, e *androidEnv, name, content string) {
	t.Helper()
	tailscale := appString(t, strAppName)
	shareTitle := appString(t, strShare)
	e.adb.startActivity(t, probePackage+"/.ShareFileActivity", "--es", "name", name, "--es", "content", content)
	var polls, tappedAt, misses int
	pollUI(t, e.adb, "the tsnet peer on Tailscale's share screen", func(s uiScreen) bool {
		polls++
		switch pkg := s.pkg(); {
		case pkg == androidPackage && s.has(withText(shareTitle)):
			if n, ok := s.find(withText(peerHostname)); ok {
				tap(t, e.adb, n)
				return true
			}
		case pkg == "android" || strings.HasSuffix(pkg, ".intentresolver"):
			if n, ok := s.find(withText(tailscale)); ok {
				// A second tap could land on the share screen as it opens, so wait a few polls first.
				if tappedAt == 0 || polls-tappedAt >= 4 {
					tap(t, e.adb, n)
					tappedAt = polls
				}
				return false
			}
			if misses++; misses%3 == 0 {
				swipeUp(t, e.adb)
			}
		}
		return false
	})
}

func waitForPeerFile(t *testing.T, e *androidEnv, name string) {
	t.Helper()
	failures := []string{appString(t, strTaildropShareFailedTitle), appString(t, strTaildropShareFailedShort)}
	pollUI(t, e.adb, "the tsnet peer to receive "+name, func(s uiScreen) bool {
		for _, f := range failures {
			if s.has(withText(f)) {
				t.Fatalf("Tailscale's share screen shows %q", f)
			}
		}
		return e.peerHasFile(t, name)
	})
}

func pickTaildropFolder(t *testing.T, e *androidEnv) {
	t.Helper()
	saved := appString(t, strTaildropSavedInDirectory)
	steps := []func(uiNode) bool{
		withText(appString(t, strTaildropDirPickerButton)),
		withTextFold("Use this folder"),
		withTextPrefixFold("Allow access to"),
		withTextFold("Allow"),
		withTextFold("Documents"),
	}
	pollUIFor(t, e.adb, "the Taildrop folder picked and the received-file banner", 3*time.Minute, func(s uiScreen) bool {
		if s.pkg() == androidPackage && s.has(withText(saved)) {
			return true
		}
		for _, match := range steps {
			if n, ok := s.find(func(n uiNode) bool { return n.Enabled && match(n) }); ok {
				tap(t, e.adb, n)
				return false
			}
		}
		return false
	})
	waitForNode(t, e.adb, "the banner title", withText(strings.ToUpper(appString(t, strTaildropFileReceived))))
}

func (e *androidEnv) peerHasFile(t *testing.T, name string) bool {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	files, err := e.peerClient(t).WaitingFiles(ctx)
	if err != nil {
		t.Fatalf("tsnet peer WaitingFiles: %v", err)
	}
	return slices.ContainsFunc(files, func(f apitype.WaitingFile) bool { return f.Name == name })
}

func (e *androidEnv) checkPeerFile(t *testing.T, name string, want []byte) {
	t.Helper()
	lc := e.peerClient(t)
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	rc, size, err := lc.GetWaitingFile(ctx, name)
	if err != nil {
		t.Fatalf("tsnet peer GetWaitingFile(%q): %v", name, err)
	}
	got, err := io.ReadAll(rc)
	rc.Close()
	if err != nil {
		t.Fatalf("reading %q on the tsnet peer: %v", name, err)
	}
	if size != int64(len(want)) || !bytes.Equal(got, want) {
		t.Fatalf("tsnet peer got %d bytes (size %d) for %q; want the %d bytes the device sent", len(got), size, name, len(want))
	}
	if err := lc.DeleteWaitingFile(ctx, name); err != nil {
		t.Logf("tsnet peer DeleteWaitingFile(%q): %v", name, err)
	}
}

func (e *androidEnv) peerSendsFile(t *testing.T, name string, data []byte) <-chan error {
	t.Helper()
	lc := e.peerClient(t)
	var target tailcfg.StableNodeID
	var lastErr error
	for deadline := time.Now().Add(*waitTimeout); target == "" && time.Now().Before(deadline); {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		targets, err := lc.FileTargets(ctx)
		cancel()
		lastErr = err
		for _, ft := range targets {
			if ft.Node.Hostinfo.OS() == "android" {
				target = ft.Node.StableID
			}
		}
		if target == "" {
			time.Sleep(time.Second)
		}
	}
	if target == "" {
		t.Fatalf("the device never became a Taildrop target for the tsnet peer (last FileTargets error: %v)", lastErr)
	}
	// The device holds the transfer until a folder is picked, so PushFile runs in the background.
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	t.Cleanup(cancel)
	done := make(chan error, 1)
	go func() {
		done <- lc.PushFile(ctx, target, int64(len(data)), name, bytes.NewReader(data))
	}()
	return done
}

func taildropFile(t *testing.T, kind string, n int) (name, content string) {
	t.Helper()
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return fmt.Sprintf("taildrop-%s-%d.bin", kind, time.Now().UnixNano()), hex.EncodeToString(b)
}

func checkDeviceFile(t *testing.T, e *androidEnv, path, want string) {
	t.Helper()
	t.Cleanup(func() { e.adb.runAllowError(t, "shell", "rm", "-f", path) })
	for deadline := time.Now().Add(*waitTimeout); time.Now().Before(deadline); time.Sleep(time.Second) {
		if got, err := e.adb.runOutputAllowError("exec-out", "cat", path); err == nil && got == want {
			return
		}
	}
	found, _ := e.adb.runOutputAllowError("shell", "find", "/sdcard", "-name", filepath.Base(path))
	t.Fatalf("%s never had the %d bytes the tsnet peer sent; find /sdcard shows:\n%s", path, len(want), found)
}
