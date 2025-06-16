// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"io"
	"log"
)

// adaptInputStream wraps a libtailscale.InputStream into an io.ReadCloser.
// It launches a goroutine to stream reads into a pipe.
func adaptInputStream(in InputStream) io.ReadCloser {
	if in == nil {
		return nil
	}
	r, w := io.Pipe()
	go func() {
		defer w.Close()
		for {
			b, err := in.Read()
			if err != nil {
				log.Printf("error reading from inputstream: %s", err)
			}
			if b == nil {
				return
			}
			w.Write(b)
		}
	}()
	return r
}
