// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package localapiservice

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log"
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

	busWatchers map[string]func()

	jvm *jni.JVM
}

//export Java_com_tailscale_ipn_ui_localapi_LocalApiClient_doRequest
func Java_com_tailscale_ipn_ui_localapi_LocalApiClient_doRequest(
	env *C.JNIEnv,
	cls C.jclass,
	jpath C.jstring,
	jmethod C.jstring,
	jbody C.jbyteArray,
	jcookie C.jstring) {

	jenv := (*jni.Env)(unsafe.Pointer(env))

	// The API Path
	pathRef := jni.NewGlobalRef(jenv, jni.Object(jpath))
	pathStr := jni.GoString(jenv, jni.String(pathRef))
	defer jni.DeleteGlobalRef(jenv, pathRef)

	// The HTTP verb
	methodRef := jni.NewGlobalRef(jenv, jni.Object(jmethod))
	methodStr := jni.GoString(jenv, jni.String(methodRef))
	defer jni.DeleteGlobalRef(jenv, methodRef)

	// The body string.  This is optional and may be empty.
	bodyRef := jni.NewGlobalRef(jenv, jni.Object(jbody))
	bodyArray := jni.GetByteArrayElements(jenv, jni.ByteArray(bodyRef))
	defer jni.DeleteGlobalRef(jenv, bodyRef)

	resp := doLocalAPIRequest(pathStr, methodStr, bodyArray)

	jrespBody := jni.NewByteArray(jenv, resp)
	respBody := jni.Value(jrespBody)
	cookie := jni.Value(jcookie)
	onResponse := jni.GetMethodID(jenv, shim.clientClass, "onResponse", "([BLjava/lang/String;)V")

	jni.CallVoidMethod(jenv, jni.Object(cls), onResponse, respBody, cookie)
}

func doLocalAPIRequest(path string, method string, body []byte) []byte {
	if shim.service == nil {
		return []byte("{\"error\":\"Not Ready\"}")
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	var reader io.Reader = nil
	if len(body) > 0 {
		reader = bytes.NewReader(body)
	}

	r, err := shim.service.Call(ctx, method, path, reader)
	defer r.Body().Close()

	if err != nil {
		return []byte("{\"error\":\"" + err.Error() + "\"}")
	}
	respBytes, err := io.ReadAll(r.Body())
	if err != nil {
		return []byte("{\"error\":\"" + err.Error() + "\"}")
	}
	return respBytes
}

// Assign a localAPIService to our shim for handling incoming localapi requests from the Kotlin side.
func ConfigureShim(jvm *jni.JVM, appCtx jni.Object, s *LocalAPIService, b *ipnlocal.LocalBackend) {
	shim.busWatchers = make(map[string]func())
	shim.service = s
	shim.backend = b

	configureLocalApiJNIHandler(jvm, appCtx)

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
func configureLocalApiJNIHandler(jvm *jni.JVM, appCtx jni.Object) error {
	shim.jvm = jvm

	return jni.Do(jvm, func(env *jni.Env) error {
		loader := jni.ClassLoaderFor(env, appCtx)
		cl, err := jni.LoadClass(env, loader, "com.tailscale.ipn.ui.localapi.LocalApiClient")
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
	cls C.jclass,
	jsessionId C.jstring) {

	jenv := (*jni.Env)(unsafe.Pointer(env))

	sessionIdRef := jni.NewGlobalRef(jenv, jni.Object(jsessionId))
	sessionId := jni.GoString(jenv, jni.String(sessionIdRef))
	defer jni.DeleteGlobalRef(jenv, sessionIdRef)

	cancel := shim.busWatchers[sessionId]
	if cancel != nil {
		log.Printf("Deregistering app layer bus watcher with sessionid: %s", sessionId)
		cancel()
		delete(shim.busWatchers, sessionId)
	} else {
		log.Printf("Error: Could not find bus watcher with sessionid: %s", sessionId)
	}
}

//export Java_com_tailscale_ipn_ui_notifier_Notifier_startIPNBusWatcher
func Java_com_tailscale_ipn_ui_notifier_Notifier_startIPNBusWatcher(
	env *C.JNIEnv,
	cls C.jclass,
	jsessionId C.jstring,
	jmask C.jint) {

	jenv := (*jni.Env)(unsafe.Pointer(env))

	sessionIdRef := jni.NewGlobalRef(jenv, jni.Object(jsessionId))
	sessionId := jni.GoString(jenv, jni.String(sessionIdRef))
	defer jni.DeleteGlobalRef(jenv, sessionIdRef)

	log.Printf("Registering app layer bus watcher with sessionid: %s", sessionId)

	ctx, cancel := context.WithCancel(context.Background())
	shim.busWatchers[sessionId] = cancel
	opts := ipn.NotifyWatchOpt(jmask)

	shim.backend.WatchNotifications(ctx, opts, func() {
		// onWatchAdded
	}, func(roNotify *ipn.Notify) bool {
		js, err := json.Marshal(roNotify)
		if err != nil {
			return true
		}
		jni.Do(shim.jvm, func(env *jni.Env) error {
			jjson := jni.JavaString(env, string(js))
			onNotify := jni.GetMethodID(env, shim.notifierClass, "onNotify", "(Ljava/lang/String;Ljava/lang/String;)V")
			jni.CallVoidMethod(env, jni.Object(cls), onNotify, jni.Value(jjson), jni.Value(jsessionId))
			return nil
		})
		return true
	})

}
