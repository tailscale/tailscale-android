// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package libtailscale

import (
	"fmt"
	"io"
	"io/fs"
	"net/url"
	"os"
	"path"
	"strings"

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

func (ops *AndroidFileOps) Join(dir, name string) string {
	return ops.helper.OpenFileURI(name)
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

func (ops *AndroidFileOps) ListDir(_ string) ([]fs.DirEntry, error) {
	namesJSON := ops.helper.ListPartialFilesJSON("")
	if len(namesJSON) <= 2 {
		return nil, nil
	}
	names := strings.Split(strings.Trim(namesJSON, "[]\""), "\",\"")
	des := make([]fs.DirEntry, len(names))
	for i, n := range names {
		des[i] = fakeDirEntry(n)
	}
	return des, nil
}

type fakeDirEntry string

func (f fakeDirEntry) Name() string             { return string(f) }
func (fakeDirEntry) IsDir() bool                { return false }
func (fakeDirEntry) Type() fs.FileMode          { return 0 }
func (fakeDirEntry) Info() (fs.FileInfo, error) { return nil, fs.ErrInvalid }
