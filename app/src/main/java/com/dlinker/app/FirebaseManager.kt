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
import java.util.Locale

object FirebaseManager {
    private const val TAG = "FirebaseManager"
    private const val VERCEL_BASE_URL = "https://device-linker-api.vercel.app/api/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun requestAirdrop(walletAddress: String, publicKey: String, signature: String): Result<String> {
        val result = callVercel("airdrop", JSONObject().apply {
            put("address", walletAddress.lowercase(Locale.ROOT))
            put("publicKey", publicKey)
            put("signature", signature)
        })
        return result.mapCatching { 
            val json = JSONObject(it)
            if (json.optBoolean("success", false)) json.optString("txHash", "Success")
            else throw Exception(json.optString("error", "入金失敗"))
        }
    }

    suspend fun syncBalance(walletAddress: String): Result<String> {
        val result = callVercel("get-balance", JSONObject().apply {
            put("address", walletAddress.lowercase(Locale.ROOT))
        })
        return result.mapCatching { 
            val json = JSONObject(it)
            if (json.has("balance")) {
                val balance = json.getString("balance")
                Log.i(TAG, "Balance updated for $walletAddress: $balance")
                balance
            } else {
                throw Exception(json.optString("error", "查詢失敗"))
            }
        }
    }

    suspend fun transfer(from: String, to: String, amount: String, signature: String, publicKey: String): Result<String> {
        val result = callVercel("transfer", JSONObject().apply {
            put("from", from.lowercase(Locale.ROOT))
            put("to", to.lowercase(Locale.ROOT))
            put("amount", amount)
            put("signature", signature)
            put("publicKey", publicKey)
        })
        return result.mapCatching {
            val json = JSONObject(it)
            if (json.optBoolean("success", false)) {
                json.getString("txHash")
            } else {
                throw Exception(json.optString("error", "轉帳失敗"))
            }
        }
    }

    private suspend fun callVercel(endpoint: String, json: JSONObject): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val url = VERCEL_BASE_URL + endpoint
                Log.d(TAG, "Calling Vercel: $url | Body: $json")
                
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", "D-Linker-Android-App")
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseData = response.body?.string() ?: ""
                    Log.d(TAG, "Vercel Response ($endpoint) [${response.code}]: $responseData")
                    
                    if (response.isSuccessful) {
                        Result.success(responseData)
                    } else {
                        Log.e(TAG, "Vercel HTTP Error: ${response.code} | Data: $responseData")
                        Result.failure(Exception("HTTP ${response.code}: $responseData"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vercel Connection Exception: ${e.message}", e)
                Result.failure(e)
            }
        }
    }
}
