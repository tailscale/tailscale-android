// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package libtailscale

import (
	"fmt"
	"io"
	"net/url"
	"os"
	"path"

	"tailscale.com/feature/taildrop"
)

// AndroidFileOps implements the ShareFileHelper interface using the Android helper.
type AndroidFileOps struct {
	helper ShareFileHelper
}

// compile-time assertion
var _ taildrop.FileOps = (*AndroidFileOps)(nil)

func NewAndroidFileOps(helper ShareFileHelper) *AndroidFileOps {
	return &AndroidFileOps{helper: helper}
}

func (ops *AndroidFileOps) OpenWriter(partialStr, dest string, offset int64, perm os.FileMode) (io.WriteCloser, string, error) {
	if offset != 0 {
		return nil, "", fmt.Errorf("resume unsupported in SAF mode")
	}
	partial := dest + partialStr
	wc := ops.helper.OpenFileWriter(partial)
	if wc == nil {
		return nil, "", fmt.Errorf("OpenFileWriter returned nil for %q", dest)
	}
	uri := ops.helper.OpenFileURI(partial)
	return wc, uri, nil
}

func (ops *AndroidFileOps) Base(pathOrURI string) string {
	if u, err := url.Parse(pathOrURI); err == nil && u.Scheme != "" {
		return path.Base(u.Path)
	}
	return path.Base(pathOrURI)
}

func (ops *AndroidFileOps) Join(dir, name string) string {
	return ops.helper.OpenFileURI(name)
}

func (ops *AndroidFileOps) Remove(baseName string) error {
	uri := ops.helper.OpenFileURI(baseName)
	return ops.helper.DeleteFile(uri)
}

func (ops *AndroidFileOps) Rename(partialURI, finalName string) (string, error) {
	tree := ops.helper.TreeURI()
	newURI := ops.helper.RenamePartialFile(partialURI, tree, finalName)
	if newURI == "" {
		return "", fmt.Errorf("SAF rename failed")
	}
	return newURI, nil
}

func (ops *AndroidFileOps) OpenReader(name string) (io.ReadCloser, error) {
	in := ops.helper.OpenPartialFileReader(name)
	if in == nil {
		return nil, fmt.Errorf("OpenPartialFileReader returned nil for %q", name)
	}
	// adapt the gobind InputStream to an io.ReadCloser
	return adaptInputStream(in), nil
}

func (ops *AndroidFileOps) IsDirect() bool {
	return false
}
