// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

// JNI implementations of Java native callback methods.

import (
	"sync/atomic"
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

	// onConnect receives global IPNService references when
	// a VPN connection is requested.
	onConnect = make(chan jni.Object)
	// onDisconnect receives global IPNService references when
	// disconnecting.
	onDisconnect = make(chan jni.Object)
	// onConnectivityChange is notified every time the network
	// conditions change.
	onConnectivityChange = make(chan struct{}, 1)

	// onGoogleToken receives google ID tokens.
	onGoogleToken = make(chan string)
)

var (
	connected atomic.Value
)

func init() {
	connected.Store(true)
}

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

func notifyVPNPrepared() {
	select {
	case onVPNPrepared <- struct{}{}:
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
	jenv := jni.EnvFor(uintptr(unsafe.Pointer(env)))
	onConnect <- jni.NewGlobalRef(jenv, jni.Object(this))
}

//export Java_com_tailscale_ipn_IPNService_disconnect
func Java_com_tailscale_ipn_IPNService_disconnect(env *C.JNIEnv, this C.jobject) {
	jenv := jni.EnvFor(uintptr(unsafe.Pointer(env)))
	onDisconnect <- jni.NewGlobalRef(jenv, jni.Object(this))
}

//export Java_com_tailscale_ipn_App_onConnectivityChanged
func Java_com_tailscale_ipn_App_onConnectivityChanged(env *C.JNIEnv, cls C.jclass, newConnected C.jboolean) {
	connected.Store(newConnected == C.JNI_TRUE)
	select {
	case onConnectivityChange <- struct{}{}:
	default:
	}
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
		jenv := jni.EnvFor(uintptr(unsafe.Pointer(env)))
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
		}
	}
}
