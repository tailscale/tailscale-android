// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause
package com.tailscale.ipn.util

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import kotlin.random.Random

class NoSuchKeyException : Exception("no key found matching the provided ID")
class HardwareKeysNotSupported : Exception("hardware-backed keys are not supported on this device")

// HardwareKeyStore implements the callbacks necessary to implement key.HardwareAttestationKey on
// the Go side. It uses KeyStore with a StrongBox processor.
class HardwareKeyStore() {
    // keyStoreKeys should be a singleton. Even if multiple HardwareKeyStores are created, we should
    // not create distinct underlying key maps.
    companion object {
        val keyStoreKeys: HashMap<String, KeyPair> by lazy {
            HashMap<String, KeyPair>()
        }
    }
    val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun newID(): String {
        var id: String
        do {
            id = Random.nextBytes(4).toHexString()
        } while (keyStoreKeys.containsKey(id))
        return id
    }

    fun createKey(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw HardwareKeysNotSupported()
        }
        val id = newID()
        val kpg: KeyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore"
        )
        val parameterSpec: KeyGenParameterSpec = KeyGenParameterSpec.Builder(
            id, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).run {
            // Use DIGEST_NONE because hashing is done on the Go side.
            setDigests(KeyProperties.DIGEST_NONE)
            setIsStrongBoxBacked(true)
            build()
        }

        kpg.initialize(parameterSpec)

        val kp = kpg.generateKeyPair()
        keyStoreKeys[id] = kp
        return id
    }

    fun releaseKey(id: String) {
        keyStoreKeys.remove(id)
    }

    fun sign(id: String, data: ByteArray): ByteArray {
        val key = keyStoreKeys[id]
        if (key == null) {
            throw NoSuchKeyException()
        }
        // Use NONEwithECDSA because hashing is done on the Go side.
        return Signature.getInstance("NONEwithECDSA").run {
            initSign(key.private)
            update(data)
            sign()
        }
    }

    fun public(id: String): ByteArray {
        val key = keyStoreKeys[id]
        if (key == null) {
            throw NoSuchKeyException()
        }
        return key.public.encoded
    }

    fun load(id: String) {
        if (keyStoreKeys[id] != null) {
            // Already loaded.
            return
        }
        val entry: KeyStore.Entry = keyStore.getEntry(id, null)
        if (entry !is KeyStore.PrivateKeyEntry) {
            throw NoSuchKeyException()
        }
        keyStoreKeys[id] = KeyPair(entry.certificate.publicKey, entry.privateKey)
    }
}