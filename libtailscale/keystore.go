// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"

	"tailscale.com/types/key"
)

func emptyHardwareAttestationKey(appCtx AppContext) key.HardwareAttestationKey {
	return &hardwareAttestationKey{appCtx: appCtx}
}

func createHardwareAttestationKey(appCtx AppContext) (key.HardwareAttestationKey, error) {
	id, err := appCtx.HardwareAttestationKeyCreate()
	if err != nil {
		return nil, err
	}
	k := &hardwareAttestationKey{appCtx: appCtx, id: id}
	if err := k.fetchPublic(); err != nil {
		return nil, err
	}
	return k, nil
}

var hardwareAttestationKeyNotInitialized = errors.New("HardwareAttestationKey has not been initialized")

type hardwareAttestationKey struct {
	appCtx AppContext
	id     string
	// public key is always initialized in createHardwareAttestationKey and
	// UnmarshalJSON. It's only nil in emptyHardwareAttestationKey.
	public *ecdsa.PublicKey
}

func (k *hardwareAttestationKey) fetchPublic() error {
	if k.id == "" || k.appCtx == nil {
		return hardwareAttestationKeyNotInitialized
	}

	pubRaw, err := k.appCtx.HardwareAttestationKeyPublic(k.id)
	if err != nil {
		return fmt.Errorf("loading public key from KeyStore: %w", err)
	}
	pubAny, err := x509.ParsePKIXPublicKey(pubRaw)
	if err != nil {
		return fmt.Errorf("parsing public key: %w", err)
	}
	pub, ok := pubAny.(*ecdsa.PublicKey)
	if !ok {
		return fmt.Errorf("parsed key is %T, expected *ecdsa.PublicKey", pubAny)
	}
	k.public = pub
	return nil
}

func (k *hardwareAttestationKey) Public() crypto.PublicKey { return k.public }

func (k *hardwareAttestationKey) Sign(rand io.Reader, digest []byte, opts crypto.SignerOpts) (signature []byte, err error) {
	if k.id == "" || k.appCtx == nil {
		return nil, hardwareAttestationKeyNotInitialized
	}
	return k.appCtx.HardwareAttestationKeySign(k.id, digest)
}

func (k *hardwareAttestationKey) MarshalJSON() ([]byte, error) { return json.Marshal(k.id) }

func (k *hardwareAttestationKey) UnmarshalJSON(data []byte) error {
	if err := json.Unmarshal(data, &k.id); err != nil {
		return err
	}
	if err := k.appCtx.HardwareAttestationKeyLoad(k.id); err != nil {
		return fmt.Errorf("loading key with ID %q from KeyStore: %w", k.id, err)
	}
	return k.fetchPublic()
}

func (k *hardwareAttestationKey) Close() error {
	if k.id == "" || k.appCtx == nil {
		return hardwareAttestationKeyNotInitialized
	}
	return k.appCtx.HardwareAttestationKeyRelease(k.id)
}

func (k *hardwareAttestationKey) Clone() key.HardwareAttestationKey {
	return &hardwareAttestationKey{appCtx: k.appCtx, id: k.id, public: k.public}
}
