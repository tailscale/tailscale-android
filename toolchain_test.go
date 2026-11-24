// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package tailscaleandroid

import (
	"os"
	"strings"
	"testing"

	tailscaleroot "tailscale.com"
)

// TestGoToolchainRevMatchesOSS verifies that this repo's go.toolchain.rev
// matches the go.toolchain.rev of the tailscale.com module version in go.mod.
// If they differ, the binary panics at runtime when tailscale.com's
// toolchain assertion fires.
func TestGoToolchainRevMatchesOSS(t *testing.T) {
	b, err := os.ReadFile("go.toolchain.rev")
	if err != nil {
		t.Fatal(err)
	}
	got := strings.TrimSpace(string(b))
	want := strings.TrimSpace(tailscaleroot.GoToolchainRev)
	if got != want {
		t.Errorf("go.toolchain.rev = %q; tailscale.com in go.mod wants %q\n"+
			"Fix with: echo %s > go.toolchain.rev", got, want, want)
	}
}
