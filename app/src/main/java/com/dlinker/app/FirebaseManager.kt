package com.dlinker.app

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.google.firebase.Firebase
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    private val functions: FirebaseFunctions = Firebase.functions("asia-east1")

    /**
     * 向 Cloud Function 請求空投 (新手入金)
     */
    suspend fun requestAirdrop(walletAddress: String, publicKey: String): Result<String> {
        val data = hashMapOf(
            "address" to walletAddress,
            "publicKey" to publicKey
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

    /**
     * 發起轉帳交易
     */
    suspend fun transfer(from: String, to: String, amount: String, signature: String): Result<String> {
        val data = hashMapOf(
            "from" to from,
            "to" to to,
            "amount" to amount,
            "signature" to signature
        )

        return try {
            val result = functions
                .getHttpsCallable("transfer")
                .call(data)
                .await()

            val response = result.data as Map<*, *>
            val success = response["success"] as? Boolean ?: false
            val message = response["message"] as? String ?: "未知錯誤"
            val txHash = response["txHash"] as? String ?: ""

            if (success) {
                Result.success(txHash)
            } else {
                Result.failure(Exception(message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
