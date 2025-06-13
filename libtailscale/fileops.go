// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package libtailscale

import (
	"fmt"
	"io"
)

// AndroidFileOps implements the ShareFileHelper interface using the Android helper.
type AndroidFileOps struct {
	helper ShareFileHelper
}

func NewAndroidFileOps(helper ShareFileHelper) *AndroidFileOps {
	return &AndroidFileOps{helper: helper}
}

func (ops *AndroidFileOps) OpenFileURI(filename string) string {
	return ops.helper.OpenFileURI(filename)
}

func (ops *AndroidFileOps) OpenFileWriter(filename string) (io.WriteCloser, string, error) {
	uri := ops.helper.OpenFileURI(filename)
	outputStream := ops.helper.OpenFileWriter(filename)
	if outputStream == nil {
		return nil, uri, fmt.Errorf("failed to open SAF output stream for %s", filename)
	}
	return outputStream, uri, nil
}

func (ops *AndroidFileOps) RenamePartialFile(partialUri, targetName string) (string, error) {
	newURI := ops.helper.RenamePartialFile(partialUri, targetName)
	if newURI == "" {
		return "", fmt.Errorf("failed to rename partial file via SAF")
	}
	return newURI, nil
}
