// Copyright (c) 2024 Tailscale Inc & AUTHORS All rights reserved.
// Use of this source code is governed by a BSD-style
// license that can be found in the LICENSE file.

package com.tailscale.ipn

import android.util.Log
import com.tailscale.ipn.ui.model.*
import kotlinx.coroutines.*
import android.app.Application
import java.net.NetworkInterface
import java.net.InterfaceAddress

class MyAppApplication : Application() {

    companion object {
        init {
            System.loadLibrary("repository")
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Perform application-wide initialization here.

        // Example: Initialize a logging library
        initializeLogging()

        // Example: Setup global error handling
        setupGlobalErrorHandler()
    }

    private fun initializeLogging() {
        // Assuming a hypothetical logging setup
        Log.d("MyAppApplication", "Logging initialized.")
    }

    private fun setupGlobalErrorHandler() {
        // Setup a custom uncaught exception handler for the application
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Log.e("MyAppApplication", "Uncaught exception in thread: ${thread.name}", e)
            // Here, you could report fatal errors to an analytics server, for example.
        }
    }

    // Returns details of the interfaces in the system, encoded as a single string for ease
        // of JNI transfer over to the Go environment.
        //
        // Example:
        // rmnet_data0 10 2000 true false false false false | fe80::4059:dc16:7ed3:9c6e%rmnet_data0/64
        // dummy0 3 1500 true false false false false | fe80::1450:5cff:fe13:f891%dummy0/64
        // wlan0 30 1500 true true false false true | fe80::2f60:2c82:4163:8389%wlan0/64 10.1.10.131/24
        // r_rmnet_data0 21 1500 true false false false false | fe80::9318:6093:d1ad:ba7f%r_rmnet_data0/64
        // rmnet_data2 12 1500 true false false false false | fe80::3c8c:44dc:46a9:9907%rmnet_data2/64
        // r_rmnet_data1 22 1500 true false false false false | fe80::b6cd:5cb0:8ae6:fe92%r_rmnet_data1/64
        // rmnet_data1 11 1500 true false false false false | fe80::51f2:ee00:edce:d68b%rmnet_data1/64
        // lo 1 65536 true false true false false | ::1/128 127.0.0.1/8
        // v4-rmnet_data2 68 1472 true true false true true | 192.0.0.4/32
        //
        // Where the fields are:
        // name ifindex mtu isUp hasBroadcast isLoopback isPointToPoint hasMulticast | ip1/N ip2/N ip3/N;
    fun getInterfacesAsString(): String {
        val interfaces: List<NetworkInterface> = try {
            NetworkInterface.getNetworkInterfaces().toList()
        } catch (e: Exception) {
            return ""
        }
    
        val sb = StringBuilder()
        for (nif in interfaces) {
            try {
                // Android doesn't have a supportsBroadcast() but the Go net.Interface wants
                // one, so we say the interface has broadcast if it has multicast.
                sb.append(String.format(java.util.Locale.ROOT, "%s %d %d %b %b %b %b %b |", nif.name,
                        nif.index, nif.mtu, nif.isUp, nif.supportsMulticast(),
                        nif.isLoopback, nif.isPointToPoint, nif.supportsMulticast()))
    
                for (ia in nif.interfaceAddresses) {
                    // InterfaceAddress == hostname + "/" + IP
                    val parts = ia.toString().split("/", limit = 0)
                    if (parts.size > 1) {
                        sb.append(String.format(java.util.Locale.ROOT, "%s/%d ", parts[1], ia.networkPrefixLength))
                    }
                }
            } catch (e: Exception) {
                // TODO: should log the exception not silently suppress it.
                continue
            }
            sb.append("\n")
        }
    
        return sb.toString()
    }    
}
