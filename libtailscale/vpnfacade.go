// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package libtailscale

import (
	"sync"

	"tailscale.com/net/dns"
	"tailscale.com/wgengine/router"
)

var (
	_ router.Router      = (*VPNFacade)(nil)
	_ dns.OSConfigurator = (*VPNFacade)(nil)
)

// VPNFacade is an implementation of both wgengine.Router and
// dns.OSConfigurator. When ReconfigureVPN is called by the backend, SetBoth
// gets called.
type VPNFacade struct {
	SetBoth func(rcfg *router.Config, dcfg *dns.OSConfig) error

	// GetBaseConfigFunc optionally specifies a function to return the current DNS
	// config in response to GetBaseConfig.
	//
	// If nil, reading the current config isn't supported and GetBaseConfig()
	// will return ErrGetBaseConfigNotSupported.
	GetBaseConfigFunc func() (dns.OSConfig, error)

	// InitialMTU is the MTU the tun should be initialized with.
	// Zero means don't change the MTU from the default. This MTU
	// is applied only once, shortly after the TUN is created, and
	// ignored thereaftef.
	InitialMTU uint32

	mu        sync.Mutex     // protects all the following
	didSetMTU bool           // if we set the MTU already
	rcfg      *router.Config // last applied router config
	dcfg      *dns.OSConfig  // last applied DNS config
}

// Up implements wgengine.router.
func (vf *VPNFacade) Up() error {
	return nil // TODO: check that all callers have no need for initialization
}

// Set implements wgengine.router.
func (vf *VPNFacade) Set(rcfg *router.Config) error {
	vf.mu.Lock()
	defer vf.mu.Unlock()
	if vf.rcfg.Equal(rcfg) {
		return nil
	}
	if vf.didSetMTU == false {
		vf.didSetMTU = true
		rcfg.NewMTU = int(vf.InitialMTU)
	}
	vf.rcfg = rcfg
	return nil
}

// UpdateMagicsockPort implements wgengine.Router. This implementation
// does nothing and returns nil because this router does not currently need
// to know what the magicsock UDP port is.
func (vf *VPNFacade) UpdateMagicsockPort(_ uint16, _ string) error {
	return nil
}

// SetDNS implements dns.OSConfigurator.
func (vf *VPNFacade) SetDNS(dcfg dns.OSConfig) error {
	vf.mu.Lock()
	defer vf.mu.Unlock()
	if vf.dcfg != nil && vf.dcfg.Equal(dcfg) {
		return nil
	}
	vf.dcfg = &dcfg
	return nil
}

// Implements dns.OSConfigurator.
func (vf *VPNFacade) SupportsSplitDNS() bool {
	return false
}

// Implements dns.OSConfigurator.
func (vf *VPNFacade) GetBaseConfig() (dns.OSConfig, error) {
	if vf.GetBaseConfigFunc == nil {
		return dns.OSConfig{}, dns.ErrGetBaseConfigNotSupported
	}
	return vf.GetBaseConfigFunc()
}

// Implements wgengine.router and dns.OSConfigurator.
func (vf *VPNFacade) Close() error {
	return vf.SetBoth(nil, nil) // TODO: check if makes sense
}

// ReconfigureVPN is the method value passed to wgengine.Config.ReconfigureVPN.
func (vf *VPNFacade) ReconfigureVPN() error {
	vf.mu.Lock()
	defer vf.mu.Unlock()

	return vf.SetBoth(vf.rcfg, vf.dcfg)
}
