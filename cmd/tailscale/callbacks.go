// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

// JNI implementations of Java native callback methods.

import (
	"unsafe"

	"github.com/tailscale/tailscale-android/jni"
)

// #include <jni.h>
import "C"

var (
	// onVPNPrepared is notified when VpnService.prepare succeeds.
	onVPNPrepared = make(chan struct{}, 1)
	// onVPNClosed is notified when VpnService.prepare fails, or when
	// the a running VPN connection is closed.
	onVPNClosed = make(chan struct{}, 1)
	// onVPNRevoked is notified whenever the VPN service is revoked.
	onVPNRevoked = make(chan struct{}, 1)

	// onConnect receives global IPNService references when
	// a VPN connection is requested.
	onConnect = make(chan jni.Object)
	// onDisconnect receives global IPNService references when
	// disconnecting.
	onDisconnect = make(chan jni.Object)

	// onGoogleToken receives google ID tokens.
	onGoogleToken = make(chan string)

	// onFileShare receives file sharing intents.
	onFileShare = make(chan []File, 1)

	// onWriteStorageGranted is notified when we are granted WRITE_STORAGE_PERMISSION.
	onWriteStorageGranted = make(chan struct{}, 1)

	// onDNSConfigChanged is notified when the network changes and the DNS config needs to be updated.
	onDNSConfigChanged = make(chan struct{}, 1)
<<<<<<< HEAD

	// onDNSConfigChanged is notified when the network changes and the DNS config needs to be updated.
	onDNSConfigChanged = make(chan struct{}, 1)
=======
>>>>>>> 69e3c3e (use network callback to update DNS config when network changes)
)

const (
	// Request codes for Android callbacks.
	// requestSignin is for Google Sign-In.
	requestSignin C.jint = 1000 + iota
	// requestPrepareVPN is for when Android's VpnService.prepare
	// completes.
	requestPrepareVPN
)

// resultOK is Android's Activity.RESULT_OK.
const resultOK = -1

//export Java_com_tailscale_ipn_App_onVPNPrepared
func Java_com_tailscale_ipn_App_onVPNPrepared(env *C.JNIEnv, class C.jclass) {
	notifyVPNPrepared()
}

//export Java_com_tailscale_ipn_App_onWriteStorageGranted
func Java_com_tailscale_ipn_App_onWriteStorageGranted(env *C.JNIEnv, class C.jclass) {
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

//export Java_com_tailscale_ipn_IPNService_connect
func Java_com_tailscale_ipn_IPNService_connect(env *C.JNIEnv, this C.jobject) {
	jenv := (*jni.Env)(unsafe.Pointer(env))
	onConnect <- jni.NewGlobalRef(jenv, jni.Object(this))
}

//export Java_com_tailscale_ipn_IPNService_directConnect
func Java_com_tailscale_ipn_IPNService_directConnect(env *C.JNIEnv, this C.jobject) {
	requestBackend(ConnectEvent{Enable: true})
}

//export Java_com_tailscale_ipn_IPNService_disconnect
func Java_com_tailscale_ipn_IPNService_disconnect(env *C.JNIEnv, this C.jobject) {
	jenv := (*jni.Env)(unsafe.Pointer(env))
	onDisconnect <- jni.NewGlobalRef(jenv, jni.Object(this))
}

//export Java_com_tailscale_ipn_StartVPNWorker_connect
func Java_com_tailscale_ipn_StartVPNWorker_connect(env *C.JNIEnv, this C.jobject) {
	requestBackend(ConnectEvent{Enable: true})
}

//export Java_com_tailscale_ipn_StopVPNWorker_disconnect
func Java_com_tailscale_ipn_StopVPNWorker_disconnect(env *C.JNIEnv, this C.jobject) {
	requestBackend(ConnectEvent{Enable: false})
}

//export Java_com_tailscale_ipn_QuickToggleService_onTileClick
func Java_com_tailscale_ipn_QuickToggleService_onTileClick(env *C.JNIEnv, cls C.jclass) {
	requestBackend(ToggleEvent{})
}

//export Java_com_tailscale_ipn_Peer_onActivityResult0
func Java_com_tailscale_ipn_Peer_onActivityResult0(env *C.JNIEnv, cls C.jclass, act C.jobject, reqCode, resCode C.jint) {
	switch reqCode {
	case requestSignin:
		if resCode != resultOK {
			onGoogleToken <- ""
			break
		}
		jenv := (*jni.Env)(unsafe.Pointer(env))
		m := jni.GetStaticMethodID(jenv, googleClass,
			"getIdTokenForActivity", "(Landroid/app/Activity;)Ljava/lang/String;")
		idToken, err := jni.CallStaticObjectMethod(jenv, googleClass, m, jni.Value(act))
		if err != nil {
			fatalErr(err)
			break
		}
		tok := jni.GoString(jenv, jni.String(idToken))
		onGoogleToken <- tok
	case requestPrepareVPN:
		if resCode == resultOK {
			notifyVPNPrepared()
		} else {
			notifyVPNClosed()
			notifyVPNRevoked()
		}
	}
}

//export Java_com_tailscale_ipn_App_onShareIntent
func Java_com_tailscale_ipn_App_onShareIntent(env *C.JNIEnv, cls C.jclass, nfiles C.jint, jtypes C.jintArray, jmimes C.jobjectArray, jitems C.jobjectArray, jnames C.jobjectArray, jsizes C.jlongArray) {
	const (
		typeNone   = 0
		typeInline = 1
		typeURI    = 2
	)
	jenv := (*jni.Env)(unsafe.Pointer(env))
	types := jni.GetIntArrayElements(jenv, jni.IntArray(jtypes))
	mimes := jni.GetStringArrayElements(jenv, jni.ObjectArray(jmimes))
	items := jni.GetStringArrayElements(jenv, jni.ObjectArray(jitems))
	names := jni.GetStringArrayElements(jenv, jni.ObjectArray(jnames))
	sizes := jni.GetLongArrayElements(jenv, jni.LongArray(jsizes))
	var files []File
	for i := 0; i < int(nfiles); i++ {
		f := File{
			Type:     FileType(types[i]),
			MIMEType: mimes[i],
			Name:     names[i],
		}
		if f.Name == "" {
			f.Name = "file.bin"
		}
		switch f.Type {
		case FileTypeText:
			f.Text = items[i]
			f.Size = int64(len(f.Text))
		case FileTypeURI:
			f.URI = items[i]
			f.Size = sizes[i]
		default:
			panic("unknown file type")
		}
		files = append(files, f)
	}
	select {
	case <-onFileShare:
	default:
	}
	onFileShare <- files
}

<<<<<<< HEAD
//export Java_com_tailscale_ipn_App_onDnsConfigChanged
func Java_com_tailscale_ipn_App_onDnsConfigChanged(env *C.JNIEnv, cls C.jclass) {
=======
//export Java_com_tailscale_ipn_DnsConfig_onDnsConfigChanged
func Java_com_tailscale_ipn_DnsConfig_onDnsConfigChanged(env *C.JNIEnv, cls C.jclass) {
>>>>>>> 69e3c3e (use network callback to update DNS config when network changes)
	select {
	case onDNSConfigChanged <- struct{}{}:
	default:
	}
}
