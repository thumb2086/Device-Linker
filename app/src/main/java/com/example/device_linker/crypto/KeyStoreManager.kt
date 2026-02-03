package com.example.device_linker.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec

object KeyStoreManager {

    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "DLinkerHardwareKey"

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply {
        load(null)
    }

    fun getOrCreateKeyPair(): KeyPair {
        return if (keyStore.containsAlias(KEY_ALIAS)) {
            // 如果金鑰已存在，則直接讀取
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
            KeyPair(entry.certificate.publicKey, entry.privateKey)
        } else {
            // 如果金鑰不存在，則生成新的金鑰對
            generateNewKeyPair()
        }
    }

    private fun generateNewKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            PROVIDER
        )

        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).run {
            setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")) // Standard for Ethereum
            setDigests(KeyProperties.DIGEST_SHA256)
            setUserAuthenticationRequired(false) // 在此範例中，我們不要求使用者解鎖
            build()
        }

        keyPairGenerator.initialize(parameterSpec)
        return keyPairGenerator.generateKeyPair()
    }
}
