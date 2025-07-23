// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package libtailscale

import (
	"encoding/json"
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

func (ops *AndroidFileOps) OpenWriter(name string, offset int64, perm os.FileMode) (io.WriteCloser, string, error) {
	var wc OutputStream
	if offset == 0 {
		wc = ops.helper.OpenFileWriter(name)
	} else {
		wc = ops.helper.OpenFileWriterAt(name, offset)
	}
	if wc == nil {
		return nil, "", fmt.Errorf("OpenFileWriter returned nil for %q", name)
	}
	uri := ops.helper.OpenFileURI(name)
	return wc, uri, nil
}

func (ops *AndroidFileOps) Base(pathOrURI string) string {
	if u, err := url.Parse(pathOrURI); err == nil && u.Scheme != "" {
		return path.Base(u.Path)
	}
	return path.Base(pathOrURI)
}

func (ops *AndroidFileOps) Remove(baseName string) error {
	uri := ops.helper.OpenFileURI(baseName)
	return ops.helper.DeleteFile(uri)
}

func (ops *AndroidFileOps) Rename(oldPath, newName string) (string, error) {
	tree := ops.helper.TreeURI()
	newURI := ops.helper.RenamePartialFile(oldPath, tree, newName)
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

func (ops *AndroidFileOps) ListFiles(_ string) ([]string, error) {
	// Call into your Java helper which returns a JSON array, e.g. `["foo.txt","bar.jpg"]`
	namesJSON := ops.helper.ListPartialFilesJSON("")
	// If the helper returns an empty list ("[]") or empty string, unmarshal will give you an empty slice.

	var names []string
	if err := json.Unmarshal([]byte(namesJSON), &names); err != nil {
		return nil, err
	}
	return names, nil
}
