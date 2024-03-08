// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"os"
	"path/filepath"
	"sync"
	"unsafe"

	jnipkg "github.com/tailscale/tailscale-android/pkg/jni"
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

	// onVPNRequested receives global IPNService references when
	// a VPN connection is requested.
	onVPNRequested = make(chan jnipkg.Object)
	// onDisconnect receives global IPNService references when
	// disconnecting.
	onDisconnect = make(chan jnipkg.Object)

	onConnect = make(chan ConnectEvent)

	// onGoogleToken receives google ID tokens.
	onGoogleToken = make(chan string)

	// onDNSConfigChanged is notified when the network changes and the DNS config needs to be updated.
	onDNSConfigChanged = make(chan struct{}, 1)
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

//export Java_com_tailscale_ipn_IPNService_requestVPN
func Java_com_tailscale_ipn_IPNService_requestVPN(env *C.JNIEnv, this C.jobject) {
	jenv := (*jnipkg.Env)(unsafe.Pointer(env))
	onVPNRequested <- jnipkg.NewGlobalRef(jenv, jnipkg.Object(this))
}

//export Java_com_tailscale_ipn_IPNService_connect
func Java_com_tailscale_ipn_IPNService_connect(env *C.JNIEnv, this C.jobject) {
	onConnect <- ConnectEvent{Enable: true}
}

//export Java_com_tailscale_ipn_IPNService_disconnect
func Java_com_tailscale_ipn_IPNService_disconnect(env *C.JNIEnv, this C.jobject) {
	jenv := (*jnipkg.Env)(unsafe.Pointer(env))
	onDisconnect <- jnipkg.NewGlobalRef(jenv, jnipkg.Object(this))
}

//export Java_com_tailscale_ipn_StartVPNWorker_connect
func Java_com_tailscale_ipn_StartVPNWorker_connect(env *C.JNIEnv, this C.jobject) {
	onConnect <- ConnectEvent{Enable: true}
}

//export Java_com_tailscale_ipn_StopVPNWorker_disconnect
func Java_com_tailscale_ipn_StopVPNWorker_disconnect(env *C.JNIEnv, this C.jobject) {
	onConnect <- ConnectEvent{Enable: false}
}

//export Java_com_tailscale_ipn_Peer_onActivityResult0
func Java_com_tailscale_ipn_Peer_onActivityResult0(env *C.JNIEnv, cls C.jclass, act C.jobject, reqCode, resCode C.jint) {
	switch reqCode {
	case requestSignin:
		if resCode != resultOK {
			onGoogleToken <- ""
			break
		}
		jenv := (*jnipkg.Env)(unsafe.Pointer(env))
		m := jnipkg.GetStaticMethodID(jenv, googleClass,
			"getIdTokenForActivity", "(Landroid/app/Activity;)Ljava/lang/String;")
		idToken, err := jnipkg.CallStaticObjectMethod(jenv, googleClass, m, jnipkg.Value(act))
		if err != nil {
			fatalErr(err)
			break
		}
		tok := jnipkg.GoString(jenv, jnipkg.String(idToken))
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

//export Java_com_tailscale_ipn_App_onDnsConfigChanged
func Java_com_tailscale_ipn_App_onDnsConfigChanged(env *C.JNIEnv, cls C.jclass) {
	select {
	case onDNSConfigChanged <- struct{}{}:
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
	mu  sync.Mutex
	jvm *jnipkg.JVM

	// appCtx is the global Android App context.
	appCtx C.jobject
}

func initJVM(env *C.JNIEnv, ctx C.jobject) {
	android.mu.Lock()
	defer android.mu.Unlock()
	jenv := (*jnipkg.Env)(unsafe.Pointer(env))
	res, err := jnipkg.GetJavaVM(jenv)
	if err != nil {
		panic("eror: GetJavaVM failed")
	}
	android.jvm = res
	android.appCtx = C.jobject(jnipkg.NewGlobalRef(jenv, jnipkg.Object(ctx)))
}

//export Java_com_tailscale_ipn_App_initBackend
func Java_com_tailscale_ipn_App_initBackend(env *C.JNIEnv, class C.jclass, jdataDir C.jbyteArray, context C.jobject) {
	initJVM(env, context)
	jenv := (*jnipkg.Env)(unsafe.Pointer(env))
	dirBytes := jnipkg.GetByteArrayElements(jenv, jnipkg.ByteArray(jdataDir))
	if dirBytes == nil {
		panic("runGoMain: GetByteArrayElements failed")
	}
	n := jnipkg.GetArrayLength(jenv, jnipkg.ByteArray(jdataDir))
	dataDir := C.GoStringN((*C.char)(unsafe.Pointer(&dirBytes[0])), C.int(n))

	// Set XDG_CACHE_HOME to make os.UserCacheDir work.
	if _, exists := os.LookupEnv("XDG_CACHE_HOME"); !exists {
		cachePath := filepath.Join(dataDir, "cache")
		os.Setenv("XDG_CACHE_HOME", cachePath)
	}
	// Set XDG_CONFIG_HOME to make os.UserConfigDir work.
	if _, exists := os.LookupEnv("XDG_CONFIG_HOME"); !exists {
		cfgPath := filepath.Join(dataDir, "config")
		os.Setenv("XDG_CONFIG_HOME", cfgPath)
	}
	// Set HOME to make os.UserHomeDir work.
	if _, exists := os.LookupEnv("HOME"); !exists {
		os.Setenv("HOME", dataDir)
	}

	dataDirChan <- dataDir
	main()
}
