package com.dlinker.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    private const val VERCEL_BASE_URL = "https://device-linker-api.vercel.app/api/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun requestAirdrop(walletAddress: String, publicKey: String, signature: String): Result<String> {
        return callVercel("airdrop", JSONObject().apply {
            put("address", walletAddress)
            put("publicKey", publicKey)
            put("signature", signature)
        })
    }

    suspend fun syncBalance(walletAddress: String): Result<String> {
        val result = callVercel("get-balance", JSONObject().apply {
            put("address", walletAddress)
        })
        return result.mapCatching { 
            val json = JSONObject(it)
            if (json.has("balance")) json.getString("balance")
            else throw Exception(json.optString("message", "未知餘額錯誤"))
        }
    }

    suspend fun transfer(from: String, to: String, amount: String, signature: String): Result<String> {
        return callVercel("transfer", JSONObject().apply {
            put("from", from)
            put("to", to)
            put("amount", amount)
            put("signature", signature)
        })
    }

    private suspend fun callVercel(endpoint: String, json: JSONObject): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = VERCEL_BASE_URL + endpoint
                Log.d(TAG, "📡 Sending to: $url")

                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(url).post(body).build()

                client.newCall(request).execute().use { response ->
                    val responseData = response.body?.string() ?: ""
                    Log.d(TAG, "📥 Response ($endpoint): ${response.code}")
                    
                    if (response.isSuccessful) {
                        Result.success(responseData)
                    } else {
                        // 💡 增強：解析 Vercel 的 details 欄位
                        val errorDetails = try { JSONObject(responseData).optString("details", "") } catch (e: Exception) { "" }
                        Log.e(TAG, "Vercel Error: $responseData")
                        Result.failure(Exception("Vercel 請求失敗: $errorDetails"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
