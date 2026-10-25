// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintf(os.Stderr, "usage: %s URL\n", os.Args[0])
		os.Exit(2)
	}
	client := &http.Client{Timeout: 30 * time.Second}
	res, err := client.Get(os.Args[1])
	if err != nil {
		fmt.Fprintf(os.Stderr, "GET %s: %v\n", os.Args[1], err)
		os.Exit(1)
	}
	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	if err != nil {
		fmt.Fprintf(os.Stderr, "reading response body: %v\n", err)
		os.Exit(1)
	}
	fmt.Printf("status=%d\nbody=%s\n", res.StatusCode, body)
}
