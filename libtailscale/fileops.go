// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package libtailscale

import (
	"fmt"
	"log"
	"syscall"
)

// AndroidFileOps implements the ShareFileHelper interface using an Android-specific helper.
type AndroidFileOps struct {
	helper ShareFileHelper
}

// NewAndroidFileOps is a constructor.
func NewAndroidFileOps(helper ShareFileHelper) *AndroidFileOps {
	return &AndroidFileOps{helper: helper}
}

// OpenFileURI calls the underlying ShareFileHelper's OpenFileURI method.
func (ops *AndroidFileOps) OpenFileURI(filename string) string {
	return ops.helper.OpenFileURI(filename)
}

// OpenFileDescriptor calls the underlying ShareFileHelper's OpenFileDescriptor method
// and duplicates the returned file descriptor so that os.File can be safely called.
func (ops *AndroidFileOps) OpenFileDescriptor(filename string) int32 {
	fd := ops.helper.OpenFileDescriptor(filename)
	if fd < 0 {
		return fd
	}
	dupFd, err := syscall.Dup(int(fd))
	if err != nil {
		log.Printf("Error duplicating file desciptor :%w", err)
		return -1
	}
	defer syscall.Close(dupFd)
	return int32(dupFd)
}

// RenamePartialFile calls the helper's RenamePartialFile and returns the new SAF URI.
func (ops *AndroidFileOps) RenamePartialFile(partialUri, targetDirUri, targetName string) (string, error) {
	newURI := ops.helper.RenamePartialFile(partialUri, targetDirUri, targetName)
	if newURI == "" {
		return "", fmt.Errorf("failed to rename partial file via SAF")
	}
	return newURI, nil
}
