package com.dlinker.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.coroutineScope
import com.dlinker.app.crypto.KeyStoreManager
import com.dlinker.app.crypto.QrCodeUtils
import com.dlinker.app.crypto.getAddressFromPublicKey
import com.dlinker.app.ui.ScannerView
import com.dlinker.app.ui.theme.DeviceLinkerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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

    var derivedAddress by remember { mutableStateOf(context.getString(R.string.loading)) }
    var balance by remember { mutableStateOf("0.00") }
    var isLoading by remember { mutableStateOf(false) }
    var showReceiptDialog by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showAddressInputDialog by remember { mutableStateOf(false) }
    var destinationAddress by remember { mutableStateOf("") }
    var showTransferDialog by remember { mutableStateOf(false) }
    var isMigrationMode by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showScanner = true
        } else {
            Toast.makeText(context, context.getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        val addr = withContext(Dispatchers.IO) {
            try {
                val pubKey = KeyStoreManager.getPublicKey()
                getAddressFromPublicKey(pubKey)
            } catch (e: Exception) { "0xError" }
        }
        derivedAddress = addr

        if (addr.startsWith("0x") && addr.length > 10) {
            while(true) {
                FirebaseManager.syncBalance(addr).onSuccess { balance = it }
                delay(10000)
            }
        }
    }

    val transferFn: (String, String) -> Unit = { toAddr, amountStr ->
        scope.launch {
            isLoading = true
            try {
                withContext(Dispatchers.IO) {
                    val cleanTo = toAddr.trim().lowercase(Locale.ROOT).replace("0x", "")
                    val cleanFrom = derivedAddress.trim().lowercase(Locale.ROOT)
                    val cleanAmount = amountStr.trim().replace(".0", "")
                    val messageToSign = "transfer:$cleanTo:$cleanAmount"
                    val bytesToSign = messageToSign.toByteArray(Charsets.UTF_8)
                    val signature = KeyStoreManager.signData(bytesToSign)
                    val pubKeyBase64 = Base64.encodeToString(KeyStoreManager.getPublicKey(), Base64.NO_WRAP)

                    FirebaseManager.transfer(cleanFrom, toAddr.trim(), amountStr.trim(), signature, pubKeyBase64)
                        .onSuccess { 
                            withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.transfer_success), Toast.LENGTH_LONG).show() }
                            delay(5000)
                            FirebaseManager.syncBalance(derivedAddress).onSuccess { balance = it }
                        }
                        .onFailure {
                            withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.failure_message, it.message), Toast.LENGTH_LONG).show() }
                        }
                }
            } finally { 
                isLoading = false
                showTransferDialog = false
                isMigrationMode = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_dashboard_title), fontWeight = FontWeight.Bold) },
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
                ActionButton(stringResource(R.string.receive), Icons.Default.AccountBalanceWallet) { showReceiptDialog = true }
                ActionButton(stringResource(R.string.scan), Icons.Default.QrCodeScanner) { 
                    when (PackageManager.PERMISSION_GRANTED) {
                        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) -> showScanner = true
                        else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
                ActionButton(stringResource(R.string.transfer), Icons.Default.Send) { 
                    isMigrationMode = false
                    showAddressInputDialog = true 
                }
                ActionButton(stringResource(R.string.migration), Icons.Default.SwapHoriz) { 
                    isMigrationMode = true
                    showAddressInputDialog = true
                }
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
                                    val signature = KeyStoreManager.signData(cleanAddr.toByteArray(Charsets.UTF_8))
                                    
                                    FirebaseManager.requestAirdrop(cleanAddr, pubKey, signature)
                                        .onSuccess { 
                                            withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.airdrop_request_sent), Toast.LENGTH_SHORT).show() }
                                            delay(5000)
                                            FirebaseManager.syncBalance(derivedAddress).onSuccess { balance = it }
                                        }
                                        .onFailure {
                                            withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.failure_message, it.message), Toast.LENGTH_LONG).show() }
                                        }
                                }
                            } finally { isLoading = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.request_test_coins, tokenSymbol)) }
            }
        }
    }

    if (showAddressInputDialog) {
        AddressInputDialog(
            isMigration = isMigrationMode,
            onDismiss = { showAddressInputDialog = false }
        ) { addr ->
            destinationAddress = addr.trim()
            showAddressInputDialog = false
            showTransferDialog = true
        }
    }
    if (showTransferDialog) {
        val amountToTransfer = if(isMigrationMode) balance else "10"
        TransferDialog(
            toAddress = destinationAddress, 
            symbol = tokenSymbol, 
            isMigration = isMigrationMode,
            amount = amountToTransfer,
            onDismiss = { showTransferDialog = false }
        ) { amountInput ->
            transferFn(destinationAddress, amountInput)
        }
    }
    if (showReceiptDialog) ReceiptDialog(address = derivedAddress) { showReceiptDialog = false }
    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }) {
            Card(modifier = Modifier.fillMaxWidth().height(450.dp)) {
                Column {
                    Box(modifier = Modifier.weight(1f)) {
                        ScannerView(onScan = { raw ->
                            val addr = raw.trim().takeLast(42)
                            if (addr.startsWith("0x")) {
                                destinationAddress = addr
                                showScanner = false
                                showTransferDialog = true
                            }
                        })
                    }
                    Button(onClick = { showScanner = false }, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(stringResource(R.string.close))
                    }
                }
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
                Text(stringResource(R.string.my_assets), fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Text(stringResource(R.string.balance_format, balance, symbol), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column {
                Text(stringResource(R.string.device_wallet_address), fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(address, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Wallet", address))
                        Toast.makeText(context, context.getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
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
fun AddressInputDialog(isMigration: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    val title = if (isMigration) stringResource(R.string.migration) else stringResource(R.string.manual_address_input)
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = input, 
                    onValueChange = { input = it }, 
                    label = { Text(stringResource(R.string.address_placeholder)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button({ onConfirm(input.trim()) }) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }
}

@Composable
fun TransferDialog(
    toAddress: String, 
    symbol: String, 
    isMigration: Boolean,
    amount: String,
    onDismiss: () -> Unit, 
    onConfirm: (String) -> Unit
) {
    var amountInput by remember { mutableStateOf(amount) }
    val title = if(isMigration) stringResource(R.string.migration_title) else stringResource(R.string.send_symbol, symbol)
    val confirmText = if(isMigration) stringResource(R.string.migration_confirm) else stringResource(R.string.confirm_send)

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.to_address, toAddress), fontSize = 10.sp, color = Color.Gray)
                if (isMigration) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.migration_desc), fontSize = 12.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = amountInput, 
                    onValueChange = { if (!isMigration) amountInput = it }, 
                    readOnly = isMigration, // 在遷移模式下不允許修改金額
                    label = { Text(stringResource(R.string.amount)) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button({ onConfirm(amountInput) }) { Text(confirmText) }
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
                Text(stringResource(R.string.receive_address), fontWeight = FontWeight.Bold)
                val qr = remember(address) { QrCodeUtils.generateQrCode(address, 500) }
                qr?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, modifier = Modifier.size(200.dp)) }
                Text(address, fontSize = 10.sp)
                Button(onDismiss, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}
