// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"encoding/base64"
	"encoding/json"
	"iter"

	"tailscale.com/ipn"
)

// stateStore is the Go interface for a persistent storage
// backend by androidx.security.crypto.EncryptedSharedPreferences (see
// App.java).
type stateStore struct {
	// appCtx is the global Android app context.
	appCtx AppContext
}

func newStateStore(appCtx AppContext) *stateStore {
	return &stateStore{
		appCtx: appCtx,
	}
}

func (s *stateStore) All() iter.Seq2[ipn.StateKey, []byte] {
	rawJSON := s.appCtx.GetStateStoreKeysJSON()
	var keys []string
	if err := json.Unmarshal([]byte(rawJSON), &keys); err != nil {
		return func(yield func(ipn.StateKey, []byte) bool) {}
	}
	return func(yield func(ipn.StateKey, []byte) bool) {
		for _, k := range keys {
			blob, err := s.ReadState(ipn.StateKey(k))
			if err != nil {
				continue
			}
			if !yield(ipn.StateKey(k), blob) {
				return
			}
		}
	}
}

// compile-time assertion that store must implement ipn.StateStore to give immediate feedback on interface drift.
var _ ipn.StateStore = (*stateStore)(nil)

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
	b64, err := s.appCtx.DecryptFromPref(key)
	if err != nil {
		return nil, err
	}
	if b64 == "" {
		return nil, nil
	}
	return base64.RawStdEncoding.DecodeString(b64)
}

func (s *stateStore) write(key string, value []byte) error {
	bs64 := base64.RawStdEncoding.EncodeToString(value)
	return s.appCtx.EncryptToPref(key, bs64)
}
