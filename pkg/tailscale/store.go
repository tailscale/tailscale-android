// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package main

import (
	"encoding/base64"

	"tailscale.com/ipn"

	jnipkg "github.com/tailscale/tailscale-android/pkg/jni"
)

// stateStore is the Go interface for a persistent storage
// backend by androidx.security.crypto.EncryptedSharedPreferences (see
// App.java).
type stateStore struct {
	jvm *jnipkg.JVM
	// appCtx is the global Android app context.
	appCtx jnipkg.Object

	// Cached method ids on appCtx.
	encrypt jnipkg.MethodID
	decrypt jnipkg.MethodID
}

func newStateStore(jvm *jnipkg.JVM, appCtx jnipkg.Object) *stateStore {
	s := &stateStore{
		jvm:    jvm,
		appCtx: appCtx,
	}
	jnipkg.Do(jvm, func(env *jnipkg.Env) error {
		appCls := jnipkg.GetObjectClass(env, appCtx)
		s.encrypt = jnipkg.GetMethodID(
			env, appCls,
			"encryptToPref", "(Ljava/lang/String;Ljava/lang/String;)V",
		)
		s.decrypt = jnipkg.GetMethodID(
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
	err := jnipkg.Do(s.jvm, func(env *jnipkg.Env) error {
		jfile := jnipkg.JavaString(env, key)
		plain, err := jnipkg.CallObjectMethod(env, s.appCtx, s.decrypt,
			jnipkg.Value(jfile))
		if err != nil {
			return err
		}
		b64 := jnipkg.GoString(env, jnipkg.String(plain))
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
	err := jnipkg.Do(s.jvm, func(env *jnipkg.Env) error {
		jfile := jnipkg.JavaString(env, key)
		jplain := jnipkg.JavaString(env, bs64)
		err := jnipkg.CallVoidMethod(env, s.appCtx, s.encrypt,
			jnipkg.Value(jfile), jnipkg.Value(jplain))
		if err != nil {
			return err
		}
		return nil
	})
	return err
}
