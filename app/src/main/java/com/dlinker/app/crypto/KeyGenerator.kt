package com.dlinker.app.crypto

import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * 根據設備公鑰推導標準以太坊地址 (secp256k1)
 */
fun getAddressFromPublicKey(publicKeyBytes: ByteArray): String {
    return try {
        // 1. 將 Android 傳回的 X.509/SPKI 編碼公鑰還原為 ECPublicKey 物件
        val keyFactory = KeyFactory.getInstance("EC")
        val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes)) as ECPublicKey
        
        // 2. 提取 X 和 Y 座標 (各 32 bytes)，這才是以太坊認定的原始公鑰內容
        val x = Numeric.toBytesPadded(publicKey.w.affineX, 32)
        val y = Numeric.toBytesPadded(publicKey.w.affineY, 32)
        
        // 3. 拼接成 64 bytes 的未壓縮公鑰 (不含 04 前綴)
        val uncompressedPubKey = x + y
        
        // 4. 使用 Web3j 工具計算 Keccak-256 並取後 20 字節
        // Keys.getAddress 內部會處理 Keccak 邏輯
        val address = Keys.getAddress(Numeric.toHexStringNoPrefix(uncompressedPubKey))
        
        // 5. 回傳帶 Checksum 的標準地址
        Keys.toChecksumAddress(address)
    } catch (e: Exception) {
        "0x" + "0".repeat(40) // 發生錯誤時回傳零位址
    }
}

/**
 * 舊有的 Seed 推導邏輯 (保留以防其他功能使用)
 */
fun deriveAddress(seed: String?): String {
    if (seed == null) return "0x" + "0".repeat(40)
    val salt = "D-Linker-Hardware-Anchor-2023"
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest((seed + salt).toByteArray())
    return "0x" + Numeric.toHexStringNoPrefix(hash.take(20).toByteArray())
}
