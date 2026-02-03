package com.example.device_linker

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.device_linker.ui.theme.DeviceLinkerTheme
import com.google.firebase.functions.FirebaseFunctions
import java.security.MessageDigest

class MainActivity : ComponentActivity() {
    private lateinit var functions: FirebaseFunctions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 使用最相容的 getInstance 方式初始化，指定區域為 asia-east1
        functions = FirebaseFunctions.getInstance("asia-east1")

        setContent {
            DeviceLinkerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DeviceLinkerApp(functions)
                }
            }
        }
    }
}

@SuppressLint("HardwareIds")
@Composable
fun DeviceLinkerApp(functions: FirebaseFunctions) {
    val context = LocalContext.current
    val androidId = remember { 
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) 
    }
    val derivedAddress = remember(androidId) { deriveAddress(androidId) }
    
    var isLoading by remember { mutableStateOf(false) }
    var txHash by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("未領取") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "D-Linker 設備身分",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Hardware ID (Android ID):", fontWeight = FontWeight.SemiBold)
                Text(text = androidId ?: "Unknown", fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                
                Divider()
                
                Text(text = "推導錢包地址 (Wallet Address):", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text(text = derivedAddress, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        if (isLoading) {
            CircularProgressIndicator()
            Text(text = "正在呼叫區塊鏈中繼站...", modifier = Modifier.padding(top = 8.dp))
        } else {
            Button(
                onClick = {
                    isLoading = true
                    claimAirdrop(functions, derivedAddress) { success, result ->
                        isLoading = false
                        if (success) {
                            txHash = result
                            statusMessage = "領取成功！"
                            Toast.makeText(context, "入金成功！", Toast.LENGTH_LONG).show()
                        } else {
                            statusMessage = "錯誤: $result"
                            Toast.makeText(context, "失敗: $result", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("領取 100 DLINK 新手禮包")
            }
        }

        if (txHash.isNotEmpty()) {
            Text(
                text = "交易雜湊 (TX Hash):",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(text = txHash, fontSize = 10.sp, color = MaterialTheme.colorScheme.secondary)
        }
        
        Text(
            text = "狀態: $statusMessage",
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 14.sp
        )
    }
}

private fun claimAirdrop(
    functions: FirebaseFunctions,
    address: String,
    onResult: (Boolean, String) -> Unit
) {
    val data = hashMapOf(
        "address" to address
    )

    functions
        .getHttpsCallable("requestAirdrop")
        .call(data)
        .continueWith { task ->
            if (task.isSuccessful) {
                val result = task.result?.data as? Map<*, *>
                val txHash = result?.get("txHash") as? String ?: "Unknown Hash"
                onResult(true, txHash)
            } else {
                val error = task.exception?.message ?: "未知錯誤"
                Log.e("D-Linker", "Airdrop failed", task.exception)
                onResult(false, error)
            }
        }
}

private fun deriveAddress(seed: String?): String {
    if (seed == null) return "0x0000000000000000000000000000000000000000"
    val salt = "D-Linker-Hardware-Anchor-2023"
    val saltedSeed = seed + salt
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(saltedSeed.toByteArray(Charsets.UTF_8))
    val addressBytes = hash.take(20)
    return "0x" + addressBytes.joinToString("") { "%02x".format(it) }
}
