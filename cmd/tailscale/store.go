// Copyright (c) 2020 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package main

import (
	"encoding/base64"

	"tailscale.com/ipn"

	"github.com/tailscale/tailscale-android/jni"
)

// stateStore is the Go interface for a persistent storage
// backend by androidx.security.crypto.EncryptedSharedPreferences (see
// App.java).
type stateStore struct {
	jvm *jni.JVM
	// appCtx is the global Android app context.
	appCtx jni.Object

	// Cached method ids on appCtx.
	encrypt jni.MethodID
	decrypt jni.MethodID
}

func newStateStore(jvm *jni.JVM, appCtx jni.Object) *stateStore {
	s := &stateStore{
		jvm:    jvm,
		appCtx: appCtx,
	}
	jni.Do(jvm, func(env *jni.Env) error {
		appCls := jni.GetObjectClass(env, appCtx)
		s.encrypt = jni.GetMethodID(
			env, appCls,
			"encryptToPref", "(Ljava/lang/String;Ljava/lang/String;)V",
		)
		s.decrypt = jni.GetMethodID(
			env, appCls,
			"decryptFromPref", "(Ljava/lang/String;)Ljava/lang/String;",
		)
		return nil
	})
	return s
}

func prefKeyFor(id ipn.StateKey) string {
	return "statestore-" + string(id)
}

func (s *stateStore) ReadString(key string, def string) (string, error) {
	data, err := s.read(key)
	if err != nil {
		return def, err
	}
	if data == nil {
		return def, nil
	}
	return string(data), nil
}

func (s *stateStore) WriteString(key string, val string) error {
	return s.write(key, []byte(val))
}

func (s *stateStore) ReadBool(key string, def bool) (bool, error) {
	data, err := s.read(key)
	if err != nil {
		return def, err
	}
	if data == nil {
		return def, nil
	}
	return string(data) == "true", nil
}

func (s *stateStore) WriteBool(key string, val bool) error {
	data := []byte("false")
	if val {
		data = []byte("true")
	}
	return s.write(key, data)
}

func (s *stateStore) ReadState(id ipn.StateKey) ([]byte, error) {
	state, err := s.read(prefKeyFor(id))
	if err != nil {
		return nil, err
	}
	if state == nil {
		return nil, ipn.ErrStateNotExist
	}
	return state, nil
}

func (s *stateStore) WriteState(id ipn.StateKey, bs []byte) error {
	prefKey := prefKeyFor(id)
	return s.write(prefKey, bs)
}

func (s *stateStore) read(key string) ([]byte, error) {
	var data []byte
	err := jni.Do(s.jvm, func(env *jni.Env) error {
		jfile := jni.JavaString(env, key)
		plain, err := jni.CallObjectMethod(env, s.appCtx, s.decrypt,
			jni.Value(jfile))
		if err != nil {
			return err
		}
		b64 := jni.GoString(env, jni.String(plain))
		if b64 == "" {
			return nil
		}
		data, err = base64.RawStdEncoding.DecodeString(b64)
		return err
	})
	return data, err
}

func (s *stateStore) write(key string, value []byte) error {
	bs64 := base64.RawStdEncoding.EncodeToString(value)
	err := jni.Do(s.jvm, func(env *jni.Env) error {
		jfile := jni.JavaString(env, key)
		jplain := jni.JavaString(env, bs64)
		err := jni.CallVoidMethod(env, s.appCtx, s.encrypt,
			jni.Value(jfile), jni.Value(jplain))
		if err != nil {
			return err
		}
		return nil
	})
	return err
}
