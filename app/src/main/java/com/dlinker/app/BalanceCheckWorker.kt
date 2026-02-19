package com.dlinker.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.dlinker.app.crypto.KeyStoreManager
import com.dlinker.app.crypto.getAddressFromPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BalanceCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val sharedPrefs = context.getSharedPreferences("DLinkerPrefs", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val pubKey = KeyStoreManager.getPublicKey()
            val address = getAddressFromPublicKey(pubKey)

            val result = DLinkerApi.syncBalance(address)
            
            result.onSuccess { newBalanceStr ->
                val newBalance = newBalanceStr.toDoubleOrNull() ?: 0.0
                val lastBalance = sharedPrefs.getString("last_known_balance", "0.0")?.toDoubleOrNull() ?: 0.0

                if (newBalance > lastBalance) {
                    val diff = newBalance - lastBalance
                    NotificationHelper.sendBalanceNotification(applicationContext, diff, newBalance)
                }

                sharedPrefs.edit().putString("last_known_balance", newBalanceStr).apply()
            }

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
