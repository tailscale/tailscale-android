// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Command deeplink-probe fires a tailscale://navigate/<path> URI at an
// attached device three ways (BROWSABLE implicit, bare implicit, explicit
// component) and tails the relevant logcat lines so you can confirm
// DeepLinkNavigator saw the intent.
package main

import (
	"bufio"
	"flag"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

const defaultPath = "main/devices"

func main() {
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "Usage: %s [path-after-navigate]\n", filepath.Base(os.Args[0]))
		fmt.Fprintf(os.Stderr, "  default path: %s\n", defaultPath)
	}
	flag.Parse()

	tail := defaultPath
	if flag.NArg() >= 1 {
		tail = flag.Arg(0)
	}
	uri := "tailscale://navigate/" + strings.TrimPrefix(tail, "/")

	logPath := filepath.Join(os.TempDir(), "deeplink-probe.log")
	logFile, err := os.Create(logPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "cannot create %s: %v\n", logPath, err)
		os.Exit(1)
	}
	defer logFile.Close()

	tee := io.MultiWriter(os.Stdout, logFile)

	fmt.Printf("URI: %s\nLogging to %s\n\n", uri, logPath)

	if err := exec.Command("adb", "logcat", "-c").Run(); err != nil {
		fmt.Fprintf(os.Stderr, "adb logcat -c failed: %v\n", err)
	}

	fires := []struct {
		label string
		shell string
	}{
		{"implicit (BROWSABLE)", "am start -W -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d '" + uri + "'"},
		{"implicit (no category)", "am start -W -a android.intent.action.VIEW -d '" + uri + "'"},
		{"explicit component", "am start -W -n com.tailscale.ipn/.MainActivity -a android.intent.action.VIEW -d '" + uri + "'"},
	}
	for _, f := range fires {
		fmt.Fprintf(tee, "=== %s ===\n", f.label)
		cmd := exec.Command("adb", "shell", f.shell)
		cmd.Stdout = tee
		cmd.Stderr = tee
		if err := cmd.Run(); err != nil {
			fmt.Fprintf(tee, "adb failed: %v\n", err)
		}
		fmt.Fprintln(tee)
	}

	time.Sleep(time.Second)

	fmt.Fprintln(tee, "\n=== logcat ===")
	logCmd := exec.Command("adb", "logcat", "-d", "-v", "brief")
	logCmd.Stderr = tee
	logOut, err := logCmd.StdoutPipe()
	if err != nil {
		fmt.Fprintf(tee, "adb logcat pipe failed: %v\n", err)
	} else if err := logCmd.Start(); err != nil {
		fmt.Fprintf(tee, "adb logcat failed to start: %v\n", err)
	} else {
		scanner := bufio.NewScanner(logOut)
		for scanner.Scan() {
			line := scanner.Text()
			if strings.Contains(line, "Main Activity") || strings.Contains(line, "DeepLinkNavigator") {
				fmt.Fprintln(tee, line)
			}
		}
		_ = logCmd.Wait()
	}

	fmt.Printf("\nFull output: %s\n", logPath)
}
