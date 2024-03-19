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

	jnipkg "github.com/tailscale/tailscale-android/pkg/jni"
	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnlocal"
)

// #include <jni.h>
import "C"

// Shims the LocalApiClient class from the Kotlin side to the Go side's LocalAPIService.
var shim struct {
	// localApiClient is a global reference to the com.tailscale.ipn.ui.localapi.LocalApiClient class.
	clientClass jnipkg.Class

	// notifierClass is a global reference to the com.tailscale.ipn.ui.notifier.Notifier class.
	notifierClass jnipkg.Class

	// Typically a shared LocalAPIService instance.
	service *LocalAPIService

	backend *ipnlocal.LocalBackend

	busWatchers map[string]func()

	jvm *jnipkg.JVM
}

//export Java_com_tailscale_ipn_ui_localapi_LocalApiClient_doRequest
func Java_com_tailscale_ipn_ui_localapi_LocalApiClient_doRequest(
	env *C.JNIEnv,
	cls C.jclass,
	jpath C.jstring,
	jmethod C.jstring,
	jbody C.jbyteArray,
	jcookie C.jstring) {

	jenv := (*jnipkg.Env)(unsafe.Pointer(env))

	// The API Path
	pathRef := jnipkg.NewGlobalRef(jenv, jnipkg.Object(jpath))
	pathStr := jnipkg.GoString(jenv, jnipkg.String(pathRef))
	defer jnipkg.DeleteGlobalRef(jenv, pathRef)

	// The HTTP verb
	methodRef := jnipkg.NewGlobalRef(jenv, jnipkg.Object(jmethod))
	methodStr := jnipkg.GoString(jenv, jnipkg.String(methodRef))
	defer jnipkg.DeleteGlobalRef(jenv, methodRef)

	// The body string.  This is optional and may be empty.
	bodyRef := jnipkg.NewGlobalRef(jenv, jnipkg.Object(jbody))
	bodyArray := jnipkg.GetByteArrayElements(jenv, jnipkg.ByteArray(bodyRef))
	defer jnipkg.DeleteGlobalRef(jenv, bodyRef)

	resp := doLocalAPIRequest(pathStr, methodStr, bodyArray)

	jrespBody := jnipkg.NewByteArray(jenv, resp)
	respBody := jnipkg.Value(jrespBody)
	cookie := jnipkg.Value(jcookie)
	onResponse := jnipkg.GetMethodID(jenv, shim.clientClass, "onResponse", "([BLjava/lang/String;)V")

	jnipkg.CallVoidMethod(jenv, jnipkg.Object(cls), onResponse, respBody, cookie)
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
func ConfigureShim(jvm *jnipkg.JVM, appCtx jnipkg.Object, s *LocalAPIService, b *ipnlocal.LocalBackend) {
	shim.busWatchers = make(map[string]func())
	shim.service = s
	shim.backend = b

	configureLocalApiJNIHandler(jvm, appCtx)

	// Let the Kotlin side know we're ready to handle requests.
	jnipkg.Do(jvm, func(env *jnipkg.Env) error {
		onReadyAPI := jnipkg.GetStaticMethodID(env, shim.clientClass, "onReady", "()V")
		jnipkg.CallStaticVoidMethod(env, shim.clientClass, onReadyAPI)

		onNotifyNot := jnipkg.GetStaticMethodID(env, shim.notifierClass, "onReady", "()V")
		jnipkg.CallStaticVoidMethod(env, shim.notifierClass, onNotifyNot)

		log.Printf("LocalAPI Shim ready")
		return nil
	})
}

// Loads the Kotlin-side LocalApiClient class and stores it in a global reference.
func configureLocalApiJNIHandler(jvm *jnipkg.JVM, appCtx jnipkg.Object) error {
	shim.jvm = jvm

	return jnipkg.Do(jvm, func(env *jnipkg.Env) error {
		loader := jnipkg.ClassLoaderFor(env, appCtx)
		cl, err := jnipkg.LoadClass(env, loader, "com.tailscale.ipn.ui.localapi.LocalApiClient")
		if err != nil {
			return err
		}
		shim.clientClass = jnipkg.Class(jnipkg.NewGlobalRef(env, jnipkg.Object(cl)))

		cl, err = jnipkg.LoadClass(env, loader, "com.tailscale.ipn.ui.notifier.Notifier")
		if err != nil {
			return err
		}
		shim.notifierClass = jnipkg.Class(jnipkg.NewGlobalRef(env, jnipkg.Object(cl)))

		return nil
	})
}

//export Java_com_tailscale_ipn_ui_notifier_Notifier_stopIPNBusWatcher
func Java_com_tailscale_ipn_ui_notifier_Notifier_stopIPNBusWatcher(
	env *C.JNIEnv,
	cls C.jclass,
	jsessionId C.jstring) {

	jenv := (*jnipkg.Env)(unsafe.Pointer(env))

	sessionIdRef := jnipkg.NewGlobalRef(jenv, jnipkg.Object(jsessionId))
	sessionId := jnipkg.GoString(jenv, jnipkg.String(sessionIdRef))
	defer jnipkg.DeleteGlobalRef(jenv, sessionIdRef)

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

	jenv := (*jnipkg.Env)(unsafe.Pointer(env))

	sessionIdRef := jnipkg.NewGlobalRef(jenv, jnipkg.Object(jsessionId))
	sessionId := jnipkg.GoString(jenv, jnipkg.String(sessionIdRef))
	defer jnipkg.DeleteGlobalRef(jenv, sessionIdRef)

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
		jnipkg.Do(shim.jvm, func(env *jnipkg.Env) error {
			jjson := jnipkg.JavaString(env, string(js))
			onNotify := jnipkg.GetMethodID(env, shim.notifierClass, "onNotify", "(Ljava/lang/String;Ljava/lang/String;)V")
			jnipkg.CallVoidMethod(env, jnipkg.Object(cls), onNotify, jnipkg.Value(jjson), jnipkg.Value(jsessionId))
			return nil
		})
		return true
	})

}
