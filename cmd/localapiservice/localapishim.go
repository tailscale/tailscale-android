// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package localapiservice

import (
	"context"
	"io"
	"strings"
	"time"
	"unsafe"

	"github.com/tailscale/tailscale-android/cmd/jni"
)

// #include <jni.h>
import "C"

// Shims the LocalApiClient class from the Kotlin side to the Go side's LocalAPIService.
var shim struct {
	// localApiClient is a global reference to the com.tailscale.ipn.ui.localapi.LocalApiClient class.
	clientClass jni.Class

	// Typically a shared LocalAPIService instance.
	service *LocalAPIService
}

//export Java_com_tailscale_ipn_ui_localapi_LocalApiClient_doRequest
func Java_com_tailscale_ipn_ui_localapi_LocalApiClient_doRequest(
	env *C.JNIEnv,
	cls C.jclass,
	jpath C.jstring,
	jmethod C.jstring,
	jbody C.jstring,
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
	bodyStr := jni.GoString(jenv, jni.String(bodyRef))
	defer jni.DeleteGlobalRef(jenv, bodyRef)

	resp := doLocalAPIRequest(pathStr, methodStr, bodyStr)

	jrespBody := jni.JavaString(jenv, resp)
	respBody := jni.Value(jrespBody)
	cookie := jni.Value(jcookie)
	onResponse := jni.GetMethodID(jenv, shim.clientClass, "onResponse", "(Ljava/lang/String;Ljava/lang/String;)V")

	jni.CallVoidMethod(jenv, jni.Object(cls), onResponse, respBody, cookie)
}

func doLocalAPIRequest(path string, method string, body string) string {
	if shim.service == nil {
		return "{\"error\":\"Not Ready\"}"
	}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	var reader io.Reader = nil
	if len(body) > 0 {
		reader = strings.NewReader(body)
	}

	r, err := shim.service.Call(ctx, method, path, reader)
	defer r.Body().Close()

	if err != nil {
		return "{\"error\":\"" + err.Error() + "\"}"
	}
	respBytes, err := io.ReadAll(r.Body())
	if err != nil {
		return "{\"error\":\"" + err.Error() + "\"}"
	}
	return string(respBytes)
}

// Assign a localAPIService to our shim for handling incoming localapi requests from the Kotlin side.
func SetLocalAPIService(s *LocalAPIService) {
	shim.service = s
}

// Loads the Kotlin-side LocalApiClient class and stores it in a global reference.
func ConfigureLocalApiJNIHandler(jvm *jni.JVM, appCtx jni.Object) error {
	return jni.Do(jvm, func(env *jni.Env) error {
		loader := jni.ClassLoaderFor(env, appCtx)
		cl, err := jni.LoadClass(env, loader, "com.tailscale.ipn.ui.localapi.LocalApiClient")
		if err != nil {
			return err
		}
		shim.clientClass = jni.Class(jni.NewGlobalRef(env, jni.Object(cl)))
		return nil
	})
}
