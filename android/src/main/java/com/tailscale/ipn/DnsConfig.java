// Copyright (c) 2021 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;

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
//
// --------------------- Android 7 and later -----------------------------------------
//
// ## getDnsConfigs
// Android provides a requestNetwork interface in the ConnectivityManager. This returns the best
// network that should be able to reach the Internet.
//
// --------------------- Releases older than Android 7 -------------------------------
//
// We support Tailscale back to Android 5. 
//
// ## getDnsServersFromSystemProperties
// Android versions prior to 8 let us retrieve the actual system DNS servers from properties.
// Later Android versions removed the properties and only return an empty string.
//
// We check the net.dns1 - net.dns4 DNS servers. If Tailscale is up the DNS server will be
// 100.100.100.100, which isn't useful, but if we get something different we'll use that.
//
// getDnsServersFromSystemProperties can only retrieve the IPv4 or IPv6 addresses of the
// configured DNS servers. We also want to know the DNS Search Domains configured, but
// we have no way to retrieve this using these interfaces. We return an empty list of
// search domains. Sorry.
//
// ## getDnsServersFromNetworkInfo
// ConnectivityManager supports an older API called getActiveNetworkInfo to return the
// active network interface. It doesn't handle VPNs, so the interface will always be Wi-Fi
// or Cellular even if Tailscale is up.
//
// For Wi-Fi interfaces we retrieve the DHCP response from the WifiManager. For Cellular
// interfaces we check for properties populated by most of the radio drivers.
//
// getDnsServersFromNetworkInfo does not have a way to retrieve the DNS Search Domains,
// so we return an empty list. Additionally, these interfaces are so old that they only
// support IPv4. We can't retrieve IPv6 DNS server addresses this way.

public class DnsConfig {
	private Context ctx;
	private String dnsConfigs;
	private final Lock lock = new ReentrantLock();

	public DnsConfig(Context ctx) {
		this.ctx = ctx;
	}

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
		String s = getDnsConfigs();
		if (!s.trim().isEmpty()) {
			return s;
		}
		if (android.os.Build.VERSION.SDK_INT >= 23) {
			// If ConnectivityManager.getAllNetworks() works, it is the
			// authoritative mechanism and we rely on it. The other methods
			// which follow involve more compromises.
			return "";
		}

		s = getDnsServersFromSystemProperties();
		if (!s.trim().isEmpty()) {
			return s;
		}
		return getDnsServersFromNetworkInfo();
	}

	public String getDnsConfigs(){
		lock.lock(); // Acquire the lock
        try {
            return this.dnsConfigs;
        } finally {
            lock.unlock(); // Release the lock
        }
	}

	// Callback which, upon finding network that meets the requirements, gets its IP addresses and caches them. 
	ConnectivityManager.NetworkCallback getDnsConfigCallback(ConnectivityManager connectivityManager){
		return new ConnectivityManager.NetworkCallback(){			
			@Override
			public void onAvailable(Network network){
				super.onAvailable(network);
				StringBuilder sb = new StringBuilder("");
				LinkProperties linkProperties = connectivityManager.getLinkProperties(network);
				List<InetAddress> dnsList = linkProperties.getDnsServers();
				for (InetAddress ip : dnsList) {
					sb.append(ip.getHostAddress()).append(" ");
				}
				lock.lock(); // Acquire the lock
				try {
					DnsConfig.this.dnsConfigs = sb.toString();
				} finally {
					lock.unlock(); // Release the lock
				}
				onDnsConfigChanged();
			}

			@Override
			public void onLost(Network network) {
				super.onLost(network);
				onDnsConfigChanged();
			}
		};
	}

	NetworkRequest getDNSConfigNetworkRequest(){
		// Request networks that are able to reach the Internet.
		return new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
	}

	// getDnsServersFromSystemProperties returns DNS servers found in system properties.
	// On Android versions prior to Android 8, we can directly query the DNS
	// servers the system is using. More recent Android releases return empty strings.
	//
	// Once Tailscale is connected these properties will return 100.100.100.100, which we
	// suppress.
	//
	// on a Nexus 4  with Android  5.1 on wifi: 2602:248:7b4a:ff60::1 10.1.10.1
	// on a Nexus 7  with Android  6.0 on wifi: 2602:248:7b4a:ff60::1 10.1.10.1
	// on a Pixel 3a with Android 12.0 on wifi:
	// on a Pixel 3a with Android 12.0 on  LTE:
	//
	// The list of DNS search domains does not appear to be available in system properties.
	String getDnsServersFromSystemProperties() {
		try {
			Class SystemProperties = Class.forName("android.os.SystemProperties");
			Method method = SystemProperties.getMethod("get", String.class);
			List<String> servers = new ArrayList<String>();
			for (String name : new String[]{"net.dns1", "net.dns2", "net.dns3", "net.dns4"}) {
				String value = (String) method.invoke(null, name);
				if (value != null && !value.isEmpty() &&
						!value.equals("100.100.100.100") &&
						!servers.contains(value)) {
					servers.add(value);
				}
			}
			return String.join(" ", servers);
		} catch (Exception e) {
			return "";
		}
	}


	public String intToInetString(int hostAddress) {
		return String.format(java.util.Locale.ROOT, "%d.%d.%d.%d",
			(0xff & hostAddress),
			(0xff & (hostAddress >> 8)),
			(0xff & (hostAddress >> 16)),
			(0xff & (hostAddress >> 24)));
	}

	// getDnsServersFromNetworkInfo retrieves DNS servers using ConnectivityManager
	// getActiveNetworkInfo() plus interface-specific mechanisms to retrieve the DNS servers.
	// Only IPv4 DNS servers are supported by this mechanism, neither the WifiManager nor the
	// interface-specific dns properties appear to populate IPv6 DNS server addresses.
	//
	// on a Nexus 4  with Android 5.1  on wifi: 10.1.10.1
	// on a Nexus 7  with Android 6.0  on wifi: 10.1.10.1
	// on a Pixel-3a with Android 12.0 on wifi: 10.1.10.1
	// on a Pixel-3a with Android 12.0 on  LTE:
	//
	// The list of DNS search domains is not available in this way.
	String getDnsServersFromNetworkInfo() {
		ConnectivityManager cMgr = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
		if (cMgr == null) {
			return "";
		}

		NetworkInfo info = cMgr.getActiveNetworkInfo();
		if (info == null) {
			return "";
		}

		Class SystemProperties;
		Method method;

		try {
			SystemProperties = Class.forName("android.os.SystemProperties");
			method = SystemProperties.getMethod("get", String.class);
		} catch (Exception e) {
			return "";
		}

		List<String> servers = new ArrayList<String>();

		switch(info.getType()) {
		case ConnectivityManager.TYPE_WIFI:
		case ConnectivityManager.TYPE_WIMAX:
			for (String name : new String[]{
				"net.wifi0.dns1", "net.wifi0.dns2", "net.wifi0.dns3", "net.wifi0.dns4",
				"net.wlan0.dns1", "net.wlan0.dns2", "net.wlan0.dns3", "net.wlan0.dns4",
				"net.eth0.dns1", "net.eth0.dns2", "net.eth0.dns3", "net.eth0.dns4",
				"dhcp.wlan0.dns1", "dhcp.wlan0.dns2", "dhcp.wlan0.dns3", "dhcp.wlan0.dns4",
				"dhcp.tiwlan0.dns1", "dhcp.tiwlan0.dns2", "dhcp.tiwlan0.dns3", "dhcp.tiwlan0.dns4"}) {
				try {
					String value = (String) method.invoke(null, name);
					if (value != null && !value.isEmpty() && !servers.contains(value)) {
						servers.add(value);
					}
				} catch (Exception e) {
					continue;
				}
			}

			WifiManager wMgr = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
			if (wMgr != null) {
				DhcpInfo dhcp = wMgr.getDhcpInfo();
				if (dhcp.dns1 != 0) {
					String value = intToInetString(dhcp.dns1);
					if (value != null && !value.isEmpty() && !servers.contains(value)) {
						servers.add(value);
					}
				}
				if (dhcp.dns2 != 0) {
					String value = intToInetString(dhcp.dns2);
					if (value != null && !value.isEmpty() && !servers.contains(value)) {
						servers.add(value);
					}
				}
			}
			return String.join(" ", servers);
		case ConnectivityManager.TYPE_MOBILE:
		case ConnectivityManager.TYPE_MOBILE_HIPRI:
			for (String name : new String[]{
				"net.rmnet0.dns1", "net.rmnet0.dns2", "net.rmnet0.dns3", "net.rmnet0.dns4",
				"net.rmnet1.dns1", "net.rmnet1.dns2", "net.rmnet1.dns3", "net.rmnet1.dns4",
				"net.rmnet2.dns1", "net.rmnet2.dns2", "net.rmnet2.dns3", "net.rmnet2.dns4",
				"net.rmnet3.dns1", "net.rmnet3.dns2", "net.rmnet3.dns3", "net.rmnet3.dns4",
				"net.rmnet4.dns1", "net.rmnet4.dns2", "net.rmnet4.dns3", "net.rmnet4.dns4",
				"net.rmnet5.dns1", "net.rmnet5.dns2", "net.rmnet5.dns3", "net.rmnet5.dns4",
				"net.rmnet6.dns1", "net.rmnet6.dns2", "net.rmnet6.dns3", "net.rmnet6.dns4",
				"net.rmnet7.dns1", "net.rmnet7.dns2", "net.rmnet7.dns3", "net.rmnet7.dns4",
				"net.pdp0.dns1", "net.pdp0.dns2", "net.pdp0.dns3", "net.pdp0.dns4",
				"net.pdpbr0.dns1", "net.pdpbr0.dns2", "net.pdpbr0.dns3", "net.pdpbr0.dns4"}) {
				try {
					String value = (String) method.invoke(null, name);
					if (value != null && !value.isEmpty() && !servers.contains(value)) {
						servers.add(value);
					}
				} catch (Exception e) {
					continue;
				}

			}
		}

		return "";
	}

	// getPreferabilityForNetwork is a utility routine which implements a priority for
	// different types of network transport, used in a heuristic to pick DNS servers to use.
	int getPreferabilityForNetwork(ConnectivityManager cMgr, Network network) {
		NetworkCapabilities nc = cMgr.getNetworkCapabilities(network);

		if (nc == null) {
			return -1;
		}
		if (nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
			// tun0 has both VPN and WIFI set, have to check VPN first and return.
			return -1;
		}

		if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
			return 0;
		} else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
			return 1;
		} else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
			return 2;
		} else {
			return 3;
		}
	}

	private static native void onDnsConfigChanged();
}
