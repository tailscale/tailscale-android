// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package libtailscale

import (
	"encoding/json"
	"fmt"
	"io"
	"os"
	"time"

	"tailscale.com/feature/taildrop"
)

// AndroidFileOps implements the FileOps interface using the Android ShareFileHelper.
type AndroidFileOps struct {
	helper ShareFileHelper
}

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

func (ops *AndroidFileOps) Remove(baseName string) error {
	uri := ops.helper.OpenFileURI(baseName)
	return ops.helper.DeleteFile(uri)
}

func (ops *AndroidFileOps) Rename(oldPath, newName string) (string, error) {
	tree := ops.helper.TreeURI()
	newURI := ops.helper.RenamePartialFile(oldPath, tree, newName)
	if newURI == "" {
		return "", fmt.Errorf("failed to rename partial file via SAF")
	}
	return newURI, nil
}

func (ops *AndroidFileOps) ListFiles() ([]string, error) {
	namesJSON := ops.helper.ListPartialFilesJSON("")
	var names []string
	if err := json.Unmarshal([]byte(namesJSON), &names); err != nil {
		return nil, err
	}
	return names, nil
}

func (ops *AndroidFileOps) OpenReader(name string) (io.ReadCloser, error) {
	in := ops.helper.OpenPartialFileReader(name)
	if in == nil {
		return nil, fmt.Errorf("OpenPartialFileReader returned nil for %q", name)
	}
	return adaptInputStream(in), nil
}

func (ops *AndroidFileOps) Stat(name string) (os.FileInfo, error) {
	infoJSON := ops.helper.GetFileInfo(name)
	if infoJSON == "" {
		return nil, os.ErrNotExist
	}
	var info struct {
		Name    string `json:"name"`
		Size    int64  `json:"size"`
		ModTime int64  `json:"modTime"` // Unix millis
	}
	if err := json.Unmarshal([]byte(infoJSON), &info); err != nil {
		return nil, err
	}
	return &androidFileInfo{
		name:    info.Name,
		size:    info.Size,
		modTime: time.UnixMilli(info.ModTime),
	}, nil
}

type androidFileInfo struct {
	name    string
	size    int64
	modTime time.Time
}

func (fi *androidFileInfo) Name() string       { return fi.name }
func (fi *androidFileInfo) Size() int64        { return fi.size }
func (fi *androidFileInfo) Mode() os.FileMode  { return 0o600 }
func (fi *androidFileInfo) ModTime() time.Time { return fi.modTime }
func (fi *androidFileInfo) IsDir() bool        { return false }
func (fi *androidFileInfo) Sys() interface{}   { return nil }
