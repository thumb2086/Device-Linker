package com.dlinker.app

import android.annotation.SuppressLint
import android.os.Bundle
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import android.util.Base64
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.window.Dialog
import com.dlinker.app.crypto.KeyStoreManager
import com.dlinker.app.crypto.QrCodeUtils
import com.dlinker.app.crypto.getAddressFromPublicKey
import com.dlinker.app.ui.theme.DeviceLinkerTheme
import com.dlinker.app.ui.ScannerView
import kotlinx.coroutines.delay
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

    // 統一使用 KeyStore 公鑰推導的地址
    val derivedAddress = remember {
        try {
            val pubKey = KeyStoreManager.getPublicKey()
            getAddressFromPublicKey(pubKey)
        } catch (e: Exception) {
            Log.e("D-Linker", "Address derivation failed", e)
            "0xError"
        }
    }

    var balance by remember { mutableStateOf("0.00") }
    var isLoading by remember { mutableStateOf(false) }
    var txHash by remember { mutableStateOf("") }
    var showReceiptDialog by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var destinationAddress by remember { mutableStateOf("") }
    var showTransferDialog by remember { mutableStateOf(false) }

    // 自動定時同步餘額 (代替 Firestore 監聽器以避開 Firebase SDK 異常)
    LaunchedEffect(derivedAddress) {
        if (derivedAddress.startsWith("0x") && derivedAddress.length > 10) {
            while(true) {
                val result = FirebaseManager.syncBalance(derivedAddress)
                result.onSuccess { balance = it }
                delay(30000) // 每 30 秒自動刷新一次
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showScanner = true
        } else {
            Toast.makeText(context, "需要相機權限才能掃描", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("D-Linker Dashboard", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            isLoading = true
                            val result = FirebaseManager.syncBalance(derivedAddress)
                            result.onSuccess { 
                                balance = it
                                Toast.makeText(context, "餘額已更新", Toast.LENGTH_SHORT).show()
                            }
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
                    onClick = { showReceiptDialog = true }
                )
                ActionButton(
                    text = "掃描",
                    icon = Icons.Default.QrCodeScanner,
                    onClick = {
                        cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                    }
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
                            try {
                                val publicKeyStr = Base64.encodeToString(KeyStoreManager.getPublicKey(), Base64.NO_WRAP)
                                val signature = KeyStoreManager.signData(derivedAddress.toByteArray())
                                val result = FirebaseManager.requestAirdrop(derivedAddress, publicKeyStr, signature)
                                isLoading = false

                                result.onSuccess { msg ->
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    // 領取成功後手動同步一次餘額
                                    val balanceResult = FirebaseManager.syncBalance(derivedAddress)
                                    balanceResult.onSuccess { balance = it }
                                }.onFailure { err ->
                                    Toast.makeText(context, "失敗: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                isLoading = false
                                Toast.makeText(context, "錯誤: ${e.message}", Toast.LENGTH_SHORT).show()
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

    if (showReceiptDialog) {
        ReceiptDialog(address = derivedAddress) {
            showReceiptDialog = false
        }
    }

    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp),
                shape = RoundedCornerShape(24.dp)
            ) {
                ScannerView(onScan = { result ->
                    if (result.startsWith("0x") && result.length == 42) {
                        destinationAddress = result
                        showScanner = false
                        showTransferDialog = true
                    }
                })
            }
        }
    }

    if (showTransferDialog) {
        TransferDialog(
            toAddress = destinationAddress,
            onDismiss = { showTransferDialog = false },
            onConfirm = { amount ->
                scope.launch {
                    isLoading = true
                    showTransferDialog = false

                    try {
                        val message = "transfer:$destinationAddress:$amount"
                        val signature = KeyStoreManager.signData(message.toByteArray())

                        val result = FirebaseManager.transfer(
                            from = derivedAddress,
                            to = destinationAddress,
                            amount = amount,
                            signature = signature
                        )

                        isLoading = false
                        result.onSuccess { hash ->
                            txHash = hash
                            Toast.makeText(context, "轉帳成功！", Toast.LENGTH_LONG).show()
                        }.onFailure { err ->
                            Toast.makeText(context, "轉帳失敗: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        isLoading = false
                        Toast.makeText(context, "轉帳異常: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }
}

@Composable
fun TransferDialog(toAddress: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var amount by remember { mutableStateOf("10") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("發送轉帳", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(16.dp))

                Text("接收地址:", fontSize = 12.sp, color = Color.Gray)
                Text(toAddress, fontSize = 12.sp, fontWeight = FontWeight.Medium)

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金額 (DLINK)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Button(onClick = { onConfirm(amount) }) { Text("確認發送") }
                }
            }
        }
    }
}

@Composable
fun ReceiptDialog(address: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "收款地址",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                val qrBitmap = remember(address) {
                    QrCodeUtils.generateQrCode(address, 600)
                }

                qrBitmap?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(250.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    address,
                    fontSize = 12.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("關閉")
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
