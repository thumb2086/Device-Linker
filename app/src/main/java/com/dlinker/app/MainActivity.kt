package com.dlinker.app

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
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
import android.util.Base64
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.coroutineScope
import com.dlinker.app.crypto.KeyStoreManager
import com.dlinker.app.crypto.QrCodeUtils
import com.dlinker.app.crypto.getAddressFromPublicKey
import com.dlinker.app.ui.theme.DeviceLinkerTheme
import com.dlinker.app.ui.ScannerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 確保啟動時就初始化金鑰 (背景執行)
        lifecycle.coroutineScope.launch(Dispatchers.IO) {
            try {
                KeyStoreManager.getOrCreateKeyPair()
            } catch (e: Exception) {
                Log.e("MainActivity", "Key initialization error", e)
            }
        }

        setContent {
            DeviceLinkerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
    val tokenSymbol = stringResource(id = R.string.token_symbol)

    var derivedAddress by remember { mutableStateOf("載入中...") }
    var balance by remember { mutableStateOf("0.00") }
    var isLoading by remember { mutableStateOf(false) }
    var showReceiptDialog by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showAddressInputDialog by remember { mutableStateOf(false) }
    var destinationAddress by remember { mutableStateOf("") }
    var showTransferDialog by remember { mutableStateOf(false) }

    // 初始化與定期同步
    LaunchedEffect(Unit) {
        // 1. 先獲取地址 (切換到 IO 避免阻塞)
        val addr = withContext(Dispatchers.IO) {
            try {
                val pubKey = KeyStoreManager.getPublicKey()
                getAddressFromPublicKey(pubKey)
            } catch (e: Exception) { "0xError" }
        }
        derivedAddress = addr

        // 2. 開始輪詢餘額
        if (addr.startsWith("0x") && addr.length > 10) {
            while(true) {
                FirebaseManager.syncBalance(addr).onSuccess { balance = it }
                delay(10000)
            }
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
                            FirebaseManager.syncBalance(derivedAddress).onSuccess { balance = it }
                            isLoading = false
                        }
                    }) { Icon(Icons.Default.Refresh, null) }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            AssetCard(balance = balance, address = derivedAddress, symbol = tokenSymbol)
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionButton("收款", Icons.Default.AccountBalanceWallet) { showReceiptDialog = true }
                ActionButton("掃描", Icons.Default.QrCodeScanner) { showScanner = true }
                ActionButton("輸入", Icons.Default.Edit) { showAddressInputDialog = true }
            }
            Spacer(modifier = Modifier.height(32.dp))
            
            if (isLoading) CircularProgressIndicator() else {
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            try {
                                withContext(Dispatchers.IO) {
                                    val pubKey = Base64.encodeToString(KeyStoreManager.getPublicKey(), Base64.NO_WRAP)
                                    val cleanAddr = derivedAddress.trim().lowercase(Locale.ROOT)
                                    
                                    // 直接對地址字串進行 SHA256withECDSA 簽名 (UTF-8)
                                    val signature = KeyStoreManager.signData(cleanAddr.toByteArray(Charsets.UTF_8))
                                    
                                    FirebaseManager.requestAirdrop(cleanAddr, pubKey, signature)
                                        .onSuccess { 
                                            withContext(Dispatchers.Main) { Toast.makeText(context, "入金請求已送出", Toast.LENGTH_SHORT).show() }
                                            delay(5000)
                                            FirebaseManager.syncBalance(derivedAddress).onSuccess { balance = it }
                                        }
                                        .onFailure {
                                            withContext(Dispatchers.Main) { Toast.makeText(context, "失敗: ${it.message}", Toast.LENGTH_LONG).show() }
                                        }
                                }
                            } finally { isLoading = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("領取 100 $tokenSymbol 測試幣") }
            }
        }
    }

    if (showAddressInputDialog) {
        AddressInputDialog(onDismiss = { showAddressInputDialog = false }) { addr ->
            destinationAddress = addr.trim()
            showAddressInputDialog = false
            showTransferDialog = true
        }
    }
    if (showTransferDialog) {
        TransferDialog(toAddress = destinationAddress, symbol = tokenSymbol, onDismiss = { showTransferDialog = false }) { amountInput ->
            scope.launch {
                isLoading = true
                try {
                    withContext(Dispatchers.IO) {
                        // --- 符合新簽名標準協議 (v2.0) ---
                        // 1. 資料清洗：接收地址轉小寫並移除 0x
                        val cleanTo = destinationAddress.trim().lowercase(Locale.ROOT).replace("0x", "")
                        val cleanFrom = derivedAddress.trim().lowercase(Locale.ROOT)
                        
                        // 2. 金額清理：依照規則移除 .0 (確保 10.0 變成 10)
                        val cleanAmount = amountInput.trim().replace(".0", "")
                        
                        // 3. 拼湊訊息：transfer:{cleanTo}:{cleanAmount} (無暗號)
                        val messageToSign = "transfer:$cleanTo:$cleanAmount"
                        val bytesToSign = messageToSign.toByteArray(Charsets.UTF_8)
                        
                        Log.d("DeviceLinker", "New Standard Message: $messageToSign")
                        
                        // 4. 執行簽名 (SHA256withECDSA)
                        val signature = KeyStoreManager.signData(bytesToSign)
                        
                        // 5. 取得公鑰 Base64
                        val pubKeyBase64 = Base64.encodeToString(KeyStoreManager.getPublicKey(), Base64.NO_WRAP)

                        // 6. 發送轉帳請求 (to 使用原始輸入，amount 使用原始輸入，Server 會處理)
                        FirebaseManager.transfer(cleanFrom, destinationAddress.trim(), amountInput.trim(), signature, pubKeyBase64)
                            .onSuccess { 
                                withContext(Dispatchers.Main) { Toast.makeText(context, "轉帳成功！", Toast.LENGTH_LONG).show() }
                                delay(5000)
                                FirebaseManager.syncBalance(derivedAddress).onSuccess { balance = it }
                            }
                            .onFailure {
                                withContext(Dispatchers.Main) { Toast.makeText(context, "失敗: ${it.message}", Toast.LENGTH_LONG).show() }
                            }
                    }
                } finally { 
                    isLoading = false
                    showTransferDialog = false
                }
            }
        }
    }
    if (showReceiptDialog) ReceiptDialog(address = derivedAddress) { showReceiptDialog = false }
    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }) {
            Card(modifier = Modifier.fillMaxWidth().height(400.dp)) {
                ScannerView(onScan = { raw ->
                    val addr = raw.trim().takeLast(42)
                    if (addr.startsWith("0x")) {
                        destinationAddress = addr
                        showScanner = false
                        showTransferDialog = true
                    }
                })
            }
        }
    }
}

@Composable
fun AssetCard(balance: String, address: String, symbol: String) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().height(200.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("我的資產", fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Text("$balance $symbol", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column {
                Text("設備錢包地址", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(address, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Wallet", address))
                        Toast.makeText(context, "複製成功", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(60.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(icon, null, modifier = Modifier.size(28.dp))
        }
        Text(text, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun AddressInputDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("手動輸入地址", fontWeight = FontWeight.Bold)
                OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("0x...") })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("取消") }
                    Button({ onConfirm(input.trim()) }) { Text("確定") }
                }
            }
        }
    }
}

@Composable
fun TransferDialog(toAddress: String, symbol: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var amount by remember { mutableStateOf("10") }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("發送 $symbol", fontWeight = FontWeight.Bold)
                Text("至: $toAddress", fontSize = 10.sp, color = Color.Gray)
                OutlinedTextField(value = amount, onValueChange = { amount = it }, label = { Text("金額") })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("取消") }
                    Button({ onConfirm(amount) }) { Text("確認發送") }
                }
            }
        }
    }
}

@Composable
fun ReceiptDialog(address: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("收款地址", fontWeight = FontWeight.Bold)
                val qr = remember(address) { QrCodeUtils.generateQrCode(address, 500) }
                qr?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, modifier = Modifier.size(200.dp)) }
                Text(address, fontSize = 10.sp)
                Button(onDismiss, modifier = Modifier.padding(top = 16.dp)) { Text("關閉") }
            }
        }
    }
}
