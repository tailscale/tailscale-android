// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package localapiservice

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log"
	"runtime/debug"
	"time"
	"unsafe"

	"github.com/tailscale/tailscale-android/cmd/jni"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
)

// #include <jni.h>
import "C"

// Shims the LocalApiClient class from the Kotlin side to the Go side's LocalAPIService.
var shim struct {
	// localApiClient is a global reference to the com.tailscale.ipn.ui.localapi.LocalApiClient class.
	clientClass jni.Class

	// notifierClass is a global reference to the com.tailscale.ipn.ui.notifier.Notifier class.
	notifierClass jni.Class

	// Typically a shared LocalAPIService instance.
	service *LocalAPIService

	backend *ipnlocal.LocalBackend

	cancelWatchBus func()

	jvm *jni.JVM
}

//export Java_com_tailscale_ipn_ui_localapi_Request_doRequest
func Java_com_tailscale_ipn_ui_localapi_Request_doRequest(
	env *C.JNIEnv,
	cls C.jclass,
	jmethod C.jstring,
	jpath C.jstring,
	jbody C.jbyteArray) {

	defer func() {
		if p := recover(); p != nil {
			log.Printf("doRequest() panicked with %q, stack: %s", p, debug.Stack())
			panic(p)
		}
	}()

	jenv := (*jni.Env)(unsafe.Pointer(env))

	// The HTTP verb
	methodRef := jni.NewGlobalRef(jenv, jni.Object(jmethod))
	methodStr := jni.GoString(jenv, jni.String(methodRef))
	defer jni.DeleteGlobalRef(jenv, methodRef)

	// The API Path
	pathRef := jni.NewGlobalRef(jenv, jni.Object(jpath))
	pathStr := jni.GoString(jenv, jni.String(pathRef))
	defer jni.DeleteGlobalRef(jenv, pathRef)

	// The body string.  This is optional and may be empty.
	bodyRef := jni.NewGlobalRef(jenv, jni.Object(jbody))
	bodyArray := jni.GetByteArrayElements(jenv, jni.ByteArray(bodyRef))
	defer jni.DeleteGlobalRef(jenv, bodyRef)

	resp := doLocalAPIRequest(pathStr, methodStr, bodyArray)

	jrespBody := jni.NewByteArray(jenv, resp)
	respBody := jni.Value(jrespBody)
	onResponse := jni.GetMethodID(jenv, shim.clientClass, "onResponse", "([B)V")

	jni.CallVoidMethod(jenv, jni.Object(cls), onResponse, respBody)
}

func doLocalAPIRequest(path string, method string, body []byte) []byte {
	if shim.service == nil {
		return []byte("{\"error\":\"Not Ready\"}")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	var reader io.Reader = nil
	if len(body) > 0 {
		reader = bytes.NewReader(body)
	}

	r, err := shim.service.Call(ctx, method, path, reader)
	if err != nil {
		log.Printf("error calling %s %q: %s", method, path, err)
		return []byte("{\"error\":\"" + err.Error() + "\"}")
	}

	defer r.Body().Close()
	respBytes, err := io.ReadAll(r.Body())
	if err != nil {
		return []byte("{\"error\":\"" + err.Error() + "\"}")
	}
	return respBytes
}

// Assign a localAPIService to our shim for handling incoming localapi requests from the Kotlin side.
func ConfigureShim(jvm *jni.JVM, appCtx jni.Object, s *LocalAPIService, b *ipnlocal.LocalBackend) {
	shim.service = s
	shim.backend = b

	configureLocalAPIJNIHandler(jvm, appCtx)

	// Let the Kotlin side know we're ready to handle requests.
	jni.Do(jvm, func(env *jni.Env) error {
		onReadyAPI := jni.GetStaticMethodID(env, shim.clientClass, "onReady", "()V")
		jni.CallStaticVoidMethod(env, shim.clientClass, onReadyAPI)

		onNotifyNot := jni.GetStaticMethodID(env, shim.notifierClass, "onReady", "()V")
		jni.CallStaticVoidMethod(env, shim.notifierClass, onNotifyNot)

		log.Printf("LocalAPI Shim ready")
		return nil
	})
}

// Loads the Kotlin-side LocalApiClient class and stores it in a global reference.
func configureLocalAPIJNIHandler(jvm *jni.JVM, appCtx jni.Object) error {
	shim.jvm = jvm

	return jni.Do(jvm, func(env *jni.Env) error {
		loader := jni.ClassLoaderFor(env, appCtx)
		cl, err := jni.LoadClass(env, loader, "com.tailscale.ipn.ui.localapi.Request")
		if err != nil {
			return err
		}
		shim.clientClass = jni.Class(jni.NewGlobalRef(env, jni.Object(cl)))

		cl, err = jni.LoadClass(env, loader, "com.tailscale.ipn.ui.notifier.Notifier")
		if err != nil {
			return err
		}
		shim.notifierClass = jni.Class(jni.NewGlobalRef(env, jni.Object(cl)))

		return nil
	})
}

//export Java_com_tailscale_ipn_ui_notifier_Notifier_stopIPNBusWatcher
func Java_com_tailscale_ipn_ui_notifier_Notifier_stopIPNBusWatcher(
	env *C.JNIEnv,
	cls C.jclass) {

	if shim.cancelWatchBus != nil {
		log.Printf("Stop watching IPN bus")
		shim.cancelWatchBus()
		shim.cancelWatchBus = nil
	} else {
		log.Printf("Not watching IPN bus, nothing to cancel")
	}
}

//export Java_com_tailscale_ipn_ui_notifier_Notifier_startIPNBusWatcher
func Java_com_tailscale_ipn_ui_notifier_Notifier_startIPNBusWatcher(
	env *C.JNIEnv,
	cls C.jclass,
	jmask C.jint) {

	jenv := (*jni.Env)(unsafe.Pointer(env))

	log.Printf("Start watching IPN bus")

	ctx, cancel := context.WithCancel(context.Background())
	shim.cancelWatchBus = cancel
	opts := ipn.NotifyWatchOpt(jmask)

	shim.backend.WatchNotifications(ctx, opts, func() {
		// onWatchAdded
	}, func(roNotify *ipn.Notify) bool {
		js, err := json.Marshal(roNotify)
		if err != nil {
			return true
		}
		jni.Do(shim.jvm, func(env *jni.Env) error {
			jjson := jni.NewByteArray(jenv, js)
			onNotify := jni.GetStaticMethodID(jenv, shim.notifierClass, "onNotify", "([B)V")
			jni.CallStaticVoidMethod(jenv, shim.notifierClass, onNotify, jni.Value(jjson))
			return nil
		})
		return true
	})

}
