// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package libtailscale

import (
	"encoding/json"
	"io"
	"os"
	"time"

	"tailscale.com/feature/taildrop"
)

// androidFileOps implements [taildrop.FileOps] using the Android ShareFileHelper.
type androidFileOps struct {
	helper ShareFileHelper
}

var _ taildrop.FileOps = (*androidFileOps)(nil)

func newAndroidFileOps(helper ShareFileHelper) *androidFileOps {
	return &androidFileOps{helper: helper}
}

func (ops *androidFileOps) OpenWriter(name string, offset int64, _ os.FileMode) (io.WriteCloser, string, error) {
	wc, err := ops.helper.OpenFileWriter(name, offset)
	if err != nil {
		return nil, "", err
	}
	uri, err := ops.helper.GetFileURI(name)
	if err != nil {
		wc.Close()
		return nil, "", err
	}
	return wc, uri, nil
}

func (ops *androidFileOps) Remove(baseName string) error {
	uri, err := ops.helper.GetFileURI(baseName)
	if err != nil {
		return err
	}
	return ops.helper.DeleteFile(uri)
}

func (ops *androidFileOps) Rename(oldPath, newName string) (string, error) {
	return ops.helper.RenameFile(oldPath, newName)
}

func (ops *androidFileOps) ListFiles() ([]string, error) {
	namesJSON, err := ops.helper.ListFilesJSON("")
	if err != nil {
		return nil, err
	}
	var names []string
	if err := json.Unmarshal([]byte(namesJSON), &names); err != nil {
		return nil, err
	}
	return names, nil
}

func (ops *androidFileOps) OpenReader(name string) (io.ReadCloser, error) {
	in, err := ops.helper.OpenFileReader(name)
	if err != nil {
		return nil, err
	}
	return adaptInputStream(in), nil
}

func (ops *androidFileOps) Stat(name string) (os.FileInfo, error) {
	infoJSON, err := ops.helper.GetFileInfo(name)
	if err != nil {
		return nil, err
	}
	var fi androidFileInfo
	if err := json.Unmarshal([]byte(infoJSON), &fi); err != nil {
		return nil, err
	}
	return &fi, nil
}

type androidFileInfoJSON struct {
	Name    string `json:"name"`
	Size    int64  `json:"size"`
	ModTime int64  `json:"modTime"`
}

type androidFileInfo struct {
	data androidFileInfoJSON
}

// compile-time check
var _ os.FileInfo = (*androidFileInfo)(nil)

func (fi *androidFileInfo) Name() string       { return fi.data.Name }
func (fi *androidFileInfo) Size() int64        { return fi.data.Size }
func (fi *androidFileInfo) Mode() os.FileMode  { return 0o600 }
func (fi *androidFileInfo) ModTime() time.Time { return time.UnixMilli(fi.data.ModTime) }
func (fi *androidFileInfo) IsDir() bool        { return false }
func (fi *androidFileInfo) Sys() any           { return nil }
