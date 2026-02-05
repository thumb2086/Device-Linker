package com.dlinker.app.crypto

import android.util.Base64
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.security.MessageDigest

/**
 * 根據設備 Seed 推導確定性的地址。
 * 注意：這只是用於 UI 顯示與識別，真正的交易簽名將使用 KeyStore 中的金鑰。
 */
fun deriveAddress(seed: String?): String {
    if (seed == null) return "0x0000000000000000000000000000000000000000"
    val salt = "D-Linker-Hardware-Anchor-2023"
    val saltedSeed = seed + salt
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(saltedSeed.toByteArray(Charsets.UTF_8))
    
    // 取前 20 位元組並格式化為以太坊地址
    val addressBytes = hash.take(20).toByteArray()
    return Numeric.toHexStringWithPrefixZeroPadded(Numeric.toBigInt(addressBytes), 40)
}

/**
 * 從 KeyStore 的公鑰中提取以太坊地址
 */
fun getAddressFromPublicKey(publicKeyBytes: ByteArray): String {
    // 這裡需要根據具體的橢圓曲線公鑰格式轉換為 Web3 格式地址
    // 暫時使用簡化的邏輯，後續整合 Web3j 完整轉換
    return Keys.toChecksumAddress(Keys.getAddress(Numeric.toHexStringNoPrefix(publicKeyBytes)))
}
