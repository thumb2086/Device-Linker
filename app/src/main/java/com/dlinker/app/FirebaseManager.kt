package com.dlinker.app

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.functions
import com.google.firebase.Firebase
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    
    // 初始化 Firebase Functions
    private val functions: FirebaseFunctions by lazy {
        val f = Firebase.functions
        try {
            // 修正：直接使用 BuildConfig.DEBUG 並確保其可用
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Connecting to Firebase Emulator (10.0.2.2:5001)...")
                f.useEmulator("10.0.2.2", 5001)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to emulator", e)
        }
        f
    }

    /**
     * 向 Cloud Function 請求空投 (新手入金)
     */
    suspend fun requestAirdrop(walletAddress: String, publicKey: String, signature: String): Result<String> {
        val data = hashMapOf(
            "address" to walletAddress,
            "publicKey" to publicKey,
            "signature" to signature
        )

        return try {
            Log.d(TAG, "Calling requestAirdrop for address: $walletAddress")
            
            // 移除不支援的 setTimeout，使用預設的超時設定
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
            Log.e(TAG, "Error in requestAirdrop", e)
            if (e is FirebaseFunctionsException) {
                Log.e(TAG, "Functions error code: ${e.code}, message: ${e.message}")
            }
            Result.failure(e)
        }
    }

    /**
     * 手動請求餘額同步
     */
    suspend fun syncBalance(walletAddress: String): Result<String> {
        val data = hashMapOf("address" to walletAddress)

        return try {
            Log.d(TAG, "Calling syncBalance for address: $walletAddress")
            
            val result = functions
                .getHttpsCallable("syncBalance")
                .call(data)
                .await()

            val response = result.data as Map<*, *>
            val balance = response["balance"] as? String ?: "0"
            Result.success(balance)
        } catch (e: Exception) {
            Log.e(TAG, "Error in syncBalance", e)
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
            Log.d(TAG, "Calling transfer from: $from to: $to")
            
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
            Log.e(TAG, "Error in transfer", e)
            Result.failure(e)
        }
    }
}
