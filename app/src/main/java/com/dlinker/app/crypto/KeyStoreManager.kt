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
    // 升級至 V3，切換為標準 SHA256withECDSA 流程，確保硬體相容性
    private const val KEY_ALIAS = "DLinkerHardwareKey_V3"

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

    /**
     * 最終解決方案：順應硬體 TEE 行為。
     * 直接對原始數據進行 SHA256withECDSA 簽名。
     * Android KeyStore 會自動處理 SHA-256 雜湊與簽署。
     */
    fun signData(data: ByteArray): String {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            getOrCreateKeyPair()
        }
        val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("簽名失敗：金鑰不存在")

        Log.d(TAG, "Signing Data (SHA256withECDSA): ${String(data, Charsets.UTF_8)}")

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

        Log.i(TAG, "Generating new KeyPair ($KEY_ALIAS) using secp256k1 curve...")
        try {
            val specK1 = buildSpec("secp256k1")
            keyPairGenerator.initialize(specK1)
            return keyPairGenerator.generateKeyPair()
        } catch (e: Exception) {
            Log.w(TAG, "secp256k1 not supported, falling back to secp256r1", e)
            val specR1 = buildSpec("secp256r1")
            keyPairGenerator.initialize(specR1)
            return keyPairGenerator.generateKeyPair()
        }
    }

    private fun buildSpec(curveName: String): KeyGenParameterSpec {
        return KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            setAlgorithmParameterSpec(ECGenParameterSpec(curveName))
            // 啟用 SHA-256 摘要
            setDigests(KeyProperties.DIGEST_SHA256)
            setUserAuthenticationRequired(false)
        }.build()
    }
}
