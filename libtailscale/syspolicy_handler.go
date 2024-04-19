// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"errors"
	"log"

	"tailscale.com/util/syspolicy"
)

// syspolicyHandler is a syspolicy handler for the Android version of the Tailscale client,
// which lets the main networking code read values set via the Android RestrictionsManager.
type syspolicyHandler struct {
	a *App
}

func (h syspolicyHandler) ReadString(key string) (string, error) {
	if key == "" {
		return "", syspolicy.ErrNoSuchKey
	}
	retVal, err := h.a.appCtx.GetSyspolicyStringValue(key)
	if err != nil && !errors.Is(err, syspolicy.ErrNoSuchKey) {
		log.Printf("syspolicy: failed to get string value via gomobile: %v", err)
	}
	return retVal, err
}

func (h syspolicyHandler) ReadBoolean(key string) (bool, error) {
	if key == "" {
		return false, syspolicy.ErrNoSuchKey
	}
	retVal, err := h.a.appCtx.GetSyspolicyBooleanValue(key)
	if err != nil && !errors.Is(err, syspolicy.ErrNoSuchKey) {
		log.Printf("syspolicy: failed to get bool value via gomobile: %v", err)
	}
	return retVal, err
}

func (h syspolicyHandler) ReadUInt64(key string) (uint64, error) {
	if key == "" {
		return 0, syspolicy.ErrNoSuchKey
	}
	// TODO(angott): drop ReadUInt64 everywhere. We are not using it.
	log.Fatalf("ReadUInt64 is not implemented on Android")
	return 0, nil
}
