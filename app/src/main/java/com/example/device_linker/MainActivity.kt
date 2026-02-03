package com.example.device_linker

import android.annotation.SuppressLint
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.device_linker.crypto.KeyStoreManager
import com.example.device_linker.crypto.deriveAddress
import com.example.device_linker.ui.theme.DeviceLinkerTheme
import com.dlinker.app.FirebaseManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 初始化 KeyStore 金鑰
        try {
            KeyStoreManager.getOrCreateKeyPair()
        } catch (e: Exception) {
            Log.e("D-Linker", "KeyStore initialization failed", e)
        }

        setContent {
            DeviceLinkerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DeviceLinkerApp()
                }
            }
        }
    }
}

@SuppressLint("HardwareIds")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceLinkerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { Firebase.firestore }

    val androidId = remember { 
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) 
    }
    val derivedAddress = remember(androidId) { deriveAddress(androidId) }
    
    var balance by remember { mutableStateOf("0.00") }
    var isLoading by remember { mutableStateOf(false) }
    var txHash by remember { mutableStateOf("") }

    // 監聽 Firestore 餘額變動
    DisposableEffect(derivedAddress) {
        val docRef = db.collection("users").document(derivedAddress)
        val listener = docRef.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.w("D-Linker", "Listen failed.", e)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                balance = snapshot.getString("balance") ?: "0.00"
            }
        }
        onDispose { listener.remove() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("D-Linker Dashboard", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { 
                        scope.launch {
                            isLoading = true
                            FirebaseManager.syncBalance(derivedAddress)
                            isLoading = false
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 資產看板
            AssetCard(balance = balance, address = derivedAddress)

            Spacer(modifier = Modifier.height(24.dp))

            // 操作按鈕區
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ActionButton(
                    text = "收款", 
                    icon = Icons.Default.AccountBalanceWallet,
                    onClick = { Toast.makeText(context, "即將推出收款 QR Code", Toast.LENGTH_SHORT).show() }
                )
                ActionButton(
                    text = "掃描", 
                    icon = Icons.Default.QrCodeScanner,
                    onClick = { Toast.makeText(context, "即將推出掃描器", Toast.LENGTH_SHORT).show() }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 領取禮包按鈕
            if (isLoading) {
                CircularProgressIndicator()
                Text("處理中...", modifier = Modifier.padding(top = 8.dp))
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            val result = FirebaseManager.requestAirdrop(derivedAddress)
                            isLoading = false
                            
                            result.onSuccess { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                            }.onFailure { err ->
                                Toast.makeText(context, "失敗: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("領取 100 DLINK 測試幣", fontSize = 16.sp)
                }
            }

            if (txHash.isNotEmpty()) {
                Card(
                    modifier = Modifier.padding(top = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
                ) {
                    Text(
                        text = "最新交易成功",
                        modifier = Modifier.padding(8.dp),
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }
    }
}

@Composable
fun AssetCard(balance: String, address: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("我的資產", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Text("$balance DLINK", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            
            Column {
                Text("設備錢包地址", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
                Text(address, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(60.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(icon, contentDescription = text, modifier = Modifier.size(28.dp))
        }
        Text(text, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.SemiBold)
    }
}
