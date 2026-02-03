package com.dlinker.app

import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import com.dlinker.app.databinding.ActivityMainBinding
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val hardwareId = getHardwareId()
        val walletAddress = deriveAddress(hardwareId)

        binding.hardwareIdTextView.text = hardwareId
        binding.addressTextView.text = walletAddress
    }

    private fun getHardwareId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_id"
    }

    /**
     * 從硬體 ID 推導出唯一的區塊鏈地址
     * 邏輯：SHA-256(ANDROID_ID + SALT) -> 取前 20 字節
     */
    private fun deriveAddress(hardwareId: String): String {
        val salt = "D-Linker-Hardware-Anchor-2023" // 專案專屬鹽值，不可隨意更改
        val input = hardwareId + salt
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        
        // 將 Hash 轉為十六進位並取前 40 位 (20 bytes)
        val hexString = bytes.joinToString("") { "%02x".format(it) }
        return "0x" + hexString.take(40)
    }
}
