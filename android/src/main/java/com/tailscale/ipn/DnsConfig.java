// Copyright (c) 2021 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.lang.reflect.Method;

import java.net.InetAddress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

// Tailscale DNS Config retrieval
//
// Tailscale's DNS support can either override the local DNS servers with a set of servers
// configured in the admin panel, or supplement the local DNS servers with additional
// servers for specific domains like example.com.beta.tailscale.net. In the non-override mode,
// we need to retrieve the current set of DNS servers from the platform. These will typically
// be the DNS servers received from DHCP.
//
// Importantly, after the Tailscale VPN comes up it will set a DNS server of 100.100.100.100
// but we still want to retrieve the underlying DNS servers received from DHCP. If we roam
// from Wi-Fi to LTE, we want the DNS servers received from LTE.

public class DnsConfig {
	private String dnsConfigs;

	// getDnsConfigAsString returns the current DNS configuration as a multiline string:
	// line[0] DNS server addresses separated by spaces
	// line[1] search domains separated by spaces
	//
	// For example:
	// 8.8.8.8 8.8.4.4
	// example.com
	//
	// an empty string means the current DNS configuration could not be retrieved.
	String getDnsConfigAsString() {
		return getDnsConfigs().trim();
	}

	private String getDnsConfig(){
		synchronized(this) {
			return this.dnsConfigs;
		}
	}

	void updateDNSFromNetwork(String dnsConfigs){
		synchronized(this) {
			this.dnsConfigs = dnsConfigs;
		}
	}

	NetworkRequest getDNSConfigNetworkRequest(){
		// Request networks that are able to reach the Internet.
		return new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
	}
}
