// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"encoding/json"
	"errors"

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
	return retVal, translateHandlerError(err)
}

func (h syspolicyHandler) ReadBoolean(key string) (bool, error) {
	if key == "" {
		return false, syspolicy.ErrNoSuchKey
	}
	retVal, err := h.a.appCtx.GetSyspolicyBooleanValue(key)
	return retVal, translateHandlerError(err)
}

func (h syspolicyHandler) ReadUInt64(key string) (uint64, error) {
	if key == "" {
		return 0, syspolicy.ErrNoSuchKey
	}
	// We don't have any UInt64 policy settings as of 2024-08-06.
	return 0, errors.New("ReadUInt64 is not implemented on Android")
}

func (h syspolicyHandler) ReadStringArray(key string) ([]string, error) {
	if key == "" {
		return nil, syspolicy.ErrNoSuchKey
	}
	retVal, err := h.a.appCtx.GetSyspolicyStringArrayJSONValue(key)
	if err := translateHandlerError(err); err != nil {
		return nil, err
	}
	if retVal == "" {
		return nil, syspolicy.ErrNoSuchKey
	}
	var arr []string
	jsonErr := json.Unmarshal([]byte(retVal), &arr)
	if jsonErr != nil {
		return nil, jsonErr
	}
	return arr, err
}

func translateHandlerError(err error) error {
	if err != nil && !errors.Is(err, syspolicy.ErrNoSuchKey) && err.Error() == syspolicy.ErrNoSuchKey.Error() {
		return syspolicy.ErrNoSuchKey
	}
	return err // may be nil or non-nil
}
