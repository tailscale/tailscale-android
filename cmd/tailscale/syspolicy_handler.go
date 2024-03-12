// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

import (
	"log"

	"github.com/tailscale/tailscale-android/cmd/jni"
	"tailscale.com/util/syspolicy"
)

// androidHandler is a syspolicy handler for the Android version of the Tailscale client,
// which lets the main networking code read values set via the Android RestrictionsManager.
type androidHandler struct {
	a *App
}

func (h androidHandler) ReadString(key string) (string, error) {
	if key == "" {
		return "", syspolicy.ErrNoSuchKey
	}
	retVal := ""
	err := jni.Do(h.a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, h.a.appCtx)
		m := jni.GetMethodID(env, cls, "getSyspolicyStringValue", "(Ljava/lang/String;)Ljava/lang/String;")
		strObj, err := jni.CallObjectMethod(env, h.a.appCtx, m, jni.Value(jni.JavaString(env, key)))
		if err != nil {
			return err
		}
		retVal = jni.GoString(env, jni.String(strObj))
		return nil
	})
	if err != nil {
		log.Printf("syspolicy: failed to get string value via JNI: %v", err)
	}
	return retVal, err
}

func (h androidHandler) ReadBoolean(key string) (bool, error) {
	if key == "" {
		return false, syspolicy.ErrNoSuchKey
	}
	retVal := false
	err := jni.Do(h.a.jvm, func(env *jni.Env) error {
		cls := jni.GetObjectClass(env, h.a.appCtx)
		m := jni.GetMethodID(env, cls, "getSyspolicyBooleanValue", "(Ljava/lang/String;)Z")
		b, err := jni.CallBooleanMethod(env, h.a.appCtx, m, jni.Value(jni.JavaString(env, key)))
		retVal = b
		return err
	})
	if err != nil {
		log.Printf("syspolicy: failed to get bool value via JNI: %v", err)
	}
	return retVal, err
}

func (h androidHandler) ReadUInt64(key string) (uint64, error) {
	if key == "" {
		return 0, syspolicy.ErrNoSuchKey
	}
	// TODO(angott): drop ReadUInt64 everywhere. We are not using it.
	log.Fatalf("ReadUInt64 is not implemented on Android")
	return 0, nil
}
