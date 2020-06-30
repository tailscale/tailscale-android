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
	vpnPrepared = make(chan struct{}, 1)

	// onConnect receives global IPNService references when
	// a VPN connection is requested.
	onConnect = make(chan jni.Object)
	// onDisconnect receives global IPNService references when
	// disconnecting.
	onDisconnect = make(chan jni.Object)
	// onConnectivityChange is notified every time the network
	// conditions change.
	onConnectivityChange = make(chan struct{}, 1)

	// onPeerCreated receives global instances of Java Peer
	// instances being created.
	onPeerCreated = make(chan jni.Object)
	// onPeerDestroyed receives new global instances of Java Peer
	// instances about to be destroyed.
	onPeerDestroyed = make(chan jni.Object)
	// onGoogleToken receives google ID tokens.
	onGoogleToken = make(chan string)
)

var (
	connected atomic.Value
)

func init() {
	connected.Store(true)
}

//export Java_com_tailscale_ipn_Peer_fragmentCreated
func Java_com_tailscale_ipn_Peer_fragmentCreated(env *C.JNIEnv, this C.jobject) {
	jenv := jni.EnvFor(uintptr(unsafe.Pointer(env)))
	onPeerCreated <- jni.NewGlobalRef(jenv, jni.Object(this))
}

//export Java_com_tailscale_ipn_Peer_fragmentDestroyed
func Java_com_tailscale_ipn_Peer_fragmentDestroyed(env *C.JNIEnv, this C.jobject) {
	jenv := jni.EnvFor(uintptr(unsafe.Pointer(env)))
	onPeerDestroyed <- jni.NewGlobalRef(jenv, jni.Object(this))
}

//export Java_com_tailscale_ipn_Peer_onVPNPrepared
func Java_com_tailscale_ipn_Peer_onVPNPrepared(env *C.JNIEnv, this C.jobject) {
	select {
	case vpnPrepared <- struct{}{}:
	default:
	}
}

//export Java_com_tailscale_ipn_Peer_onSignin
func Java_com_tailscale_ipn_Peer_onSignin(env *C.JNIEnv, this C.jobject, idToken C.jstring) {
	jenv := jni.EnvFor(uintptr(unsafe.Pointer(env)))
	tok := jni.GoString(jenv, jni.String(idToken))
	onGoogleToken <- tok
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
