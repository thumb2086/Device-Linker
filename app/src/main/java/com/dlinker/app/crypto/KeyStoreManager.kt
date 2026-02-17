package com.dlinker.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec

object KeyStoreManager {

    private const val TAG = "KeyStoreManager"
    private const val PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "DLinkerHardwareKey"

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply {
        load(null)
    }

    fun getOrCreateKeyPair(): KeyPair {
        return try {
            if (keyStore.containsAlias(KEY_ALIAS)) {
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
                if (entry != null) {
                    KeyPair(entry.certificate.publicKey, entry.privateKey)
                } else {
                    generateNewKeyPair()
                }
            } else {
                generateNewKeyPair()
            }
        } catch (e: Exception) {
            Log.e(TAG, "getOrCreateKeyPair failed, regenerating...", e)
            generateNewKeyPair()
        }
    }

    fun getPublicKey(): ByteArray {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            getOrCreateKeyPair()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("無法取得 KeyStore 進入點")
        
        return entry.certificate.publicKey.encoded
    }

    fun signData(data: ByteArray): String {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            getOrCreateKeyPair()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("簽名失敗：金鑰不存在")

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

        // 嘗試使用 secp256k1
        return try {
            val specK1 = buildSpec("secp256k1")
            keyPairGenerator.initialize(specK1)
            keyPairGenerator.generateKeyPair()
        } catch (e: Exception) {
            Log.w(TAG, "secp256k1 unsupported on this device, falling back to secp256r1")
            // 回退到 secp256r1 (P-256)
            val specR1 = buildSpec("secp256r1")
            keyPairGenerator.initialize(specR1)
            keyPairGenerator.generateKeyPair()
        }
    }

    private fun buildSpec(curveName: String): KeyGenParameterSpec {
        return KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            setAlgorithmParameterSpec(ECGenParameterSpec(curveName))
            setDigests(KeyProperties.DIGEST_SHA256)
            setUserAuthenticationRequired(false)
        }.build()
    }
}
