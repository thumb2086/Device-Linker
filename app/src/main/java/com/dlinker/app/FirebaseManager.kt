package com.dlinker.app

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    private val functions: FirebaseFunctions = Firebase.functions("asia-east1") // 根據您的 Firebase 區域設定，預設通常是 us-central1，如果您沒設定請改回或確認

    /**
     * 向 Cloud Function 請求空投 (新手入金)
     */
    suspend fun requestAirdrop(walletAddress: String): Result<String> {
        val data = hashMapOf(
            "address" to walletAddress
        )

        return try {
            val result = functions
                .getHttpsCallable("requestAirdrop")
                .call(data)
                .await()

            val response = result.data as Map<*, *>
            val success = response["success"] as? Boolean ?: false
            val message = response["message"] as? String ?: "未知錯誤"
            
            if (success) {
                Result.success(message)
            } else {
                Result.failure(Exception(message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 手動請求餘額同步
     */
    suspend fun syncBalance(walletAddress: String): Result<String> {
        val data = hashMapOf(
            "address" to walletAddress
        )

        return try {
            val result = functions
                .getHttpsCallable("syncBalance")
                .call(data)
                .await()

            val response = result.data as Map<*, *>
            val balance = response["balance"] as? String ?: "0"
            Result.success(balance)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
