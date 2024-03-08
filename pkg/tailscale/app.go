// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"unsafe"

	jnipkg "github.com/tailscale/tailscale-android/pkg/jni"
	"github.com/tailscale/tailscale-android/pkg/localapiservice"
	"tailscale.com/ipn/ipnlocal"
	"tailscale.com/types/logid"
)

// #include <jni.h>
import "C"

type App struct {
	jvm *jnipkg.JVM
	// appCtx is a global reference to the com.tailscale.ipn.App instance.
	appCtx jnipkg.Object

	store             *stateStore
	logIDPublicAtomic atomic.Pointer[logid.PublicID]

	localAPI *localapiservice.LocalAPIService
	backend  *ipnlocal.LocalBackend
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
