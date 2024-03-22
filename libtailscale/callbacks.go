// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"sync"
)

var (
	// onVPNPrepared is notified when VpnService.prepare succeeds.
	onVPNPrepared = make(chan struct{}, 1)
	// onVPNClosed is notified when VpnService.prepare fails, or when
	// the a running VPN connection is closed.
	onVPNClosed = make(chan struct{}, 1)
	// onVPNRevoked is notified whenever the VPN service is revoked.
	onVPNRevoked = make(chan struct{}, 1)

	// onVPNRequested receives global IPNService references when
	// a VPN connection is requested.
	onVPNRequested = make(chan IPNService)
	// onDisconnect receives global IPNService references when
	// disconnecting.
	onDisconnect = make(chan IPNService)

	// onGoogleToken receives google ID tokens.
	onGoogleToken = make(chan string)

	// onWriteStorageGranted is notified when we are granted WRITE_STORAGE_PERMISSION.
	onWriteStorageGranted = make(chan struct{}, 1)

	// onDNSConfigChanged is notified when the network changes and the DNS config needs to be updated.
	onDNSConfigChanged = make(chan struct{}, 1)
)

func OnShareIntent(nfiles int32, types []int32, mimes []string, items []string, names []string, sizes []int) {
	// TODO(oxtoacart): actually implement this
	// const (
	// 	typeNone   = 0
	// 	typeInline = 1
	// 	typeURI    = 2
	// )
	// jenv := (*jni.Env)(unsafe.Pointer(env))
	// var files []File
	// for i := 0; i < int(nfiles); i++ {
	// 	f := File{
	// 		Type:     FileType(types[i]),
	// 		MIMEType: mimes[i],
	// 		Name:     names[i],
	// 	}
	// 	if f.Name == "" {
	// 		f.Name = "file.bin"
	// 	}
	// 	switch f.Type {
	// 	case FileTypeText:
	// 		f.Text = items[i]
	// 		f.Size = int64(len(f.Text))
	// 	case FileTypeURI:
	// 		f.URI = items[i]
	// 		f.Size = sizes[i]
	// 	default:
	// 		panic("unknown file type")
	// 	}
	// 	files = append(files, f)
	// }
	// select {
	// case <-onFileShare:
	// default:
	// }
	// onFileShare <- files
}

func OnDnsConfigChanged() {
	select {
	case onDNSConfigChanged <- struct{}{}:
	default:
	}
}

//export Java_com_tailscale_ipn_App_onWriteStorageGranted
func OnWriteStorageGranted() {
	select {
	case onWriteStorageGranted <- struct{}{}:
	default:
	}
}

func notifyVPNPrepared() {
	select {
	case onVPNPrepared <- struct{}{}:
	default:
	}
}

func notifyVPNRevoked() {
	select {
	case onVPNRevoked <- struct{}{}:
	default:
	}
}

func notifyVPNClosed() {
	select {
	case onVPNClosed <- struct{}{}:
	default:
	}
}

var android struct {
	// mu protects all fields of this structure. However, once a
	// non-nil jvm is returned from javaVM, all the other fields may
	// be accessed unlocked.
	mu sync.Mutex

	// appCtx is the global Android App context.
	appCtx AppContext
}
