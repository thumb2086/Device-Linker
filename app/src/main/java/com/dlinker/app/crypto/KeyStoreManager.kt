package com.dlinker.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
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

    fun getPublicKey(): ByteArray {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.certificate.publicKey.encoded
    }

    fun signData(data: ByteArray): String {
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(entry.privateKey)
            update(data)
            sign()
        }
        return Base64.encodeToString(signature, Base64.NO_WRAP)
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
            // 注意：某些舊設備可能不支援 secp256k1，此處優先嘗試 secp256k1 (Ethereum 標準)
            // 若失敗則退而求其次使用 secp256r1
            try {
                setAlgorithmParameterSpec(ECGenParameterSpec("secp256k1"))
            } catch (e: Exception) {
                setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            }
            setDigests(KeyProperties.DIGEST_SHA256)
            setUserAuthenticationRequired(false)
            build()
        }

        keyPairGenerator.initialize(parameterSpec)
        return keyPairGenerator.generateKeyPair()
    }
}
