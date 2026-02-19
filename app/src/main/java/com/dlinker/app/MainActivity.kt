package com.dlinker.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
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

// 定義導航狀態
enum class Screen { Dashboard, History }

class MainActivity : AppCompatActivity() {
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
                    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }
                    
                    if (currentScreen == Screen.Dashboard) {
                        DashboardScreen(onNavigateToHistory = { currentScreen = Screen.History })
                    } else {
                        HistoryScreen(onBack = { currentScreen = Screen.Dashboard })
                    }
                }
            }
        }
    }
}

@SuppressLint("HardwareIds")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onNavigateToHistory: () -> Unit) {
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
    var showSettingsDialog by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) showScanner = true
        else Toast.makeText(context, context.getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
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
                DLinkerApi.syncBalance(addr).onSuccess { balance = it }
                delay(15000)
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
                            DLinkerApi.syncBalance(derivedAddress).onSuccess { balance = it }
                            isLoading = false
                        }
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = { showSettingsDialog = true }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(modifier = Modifier.height(16.dp))
            AssetCard(balance = balance, address = derivedAddress, symbol = tokenSymbol)
            Spacer(modifier = Modifier.height(24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                ActionButton(stringResource(R.string.receive), Icons.Default.AccountBalanceWallet) { showReceiptDialog = true }
                ActionButton(stringResource(R.string.transfer), Icons.AutoMirrored.Filled.Send) { 
                    isMigrationMode = false
                    showAddressInputDialog = true 
                }
                ActionButton(stringResource(R.string.migration), Icons.Default.SwapHoriz) { 
                    isMigrationMode = true
                    showAddressInputDialog = true
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp))
            } else {
                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            try {
                                withContext(Dispatchers.IO) {
                                    val pubKey = Base64.encodeToString(KeyStoreManager.getPublicKey(), Base64.NO_WRAP)
                                    val cleanAddr = derivedAddress.trim().lowercase(Locale.ROOT)
                                    val signature = KeyStoreManager.signData(cleanAddr.toByteArray(Charsets.UTF_8))
                                    DLinkerApi.requestAirdrop(cleanAddr, pubKey, signature).onSuccess { 
                                        withContext(Dispatchers.Main) { 
                                            Toast.makeText(context, context.getString(R.string.airdrop_request_sent), Toast.LENGTH_SHORT).show() 
                                        }
                                        delay(3000)
                                        DLinkerApi.syncBalance(derivedAddress).onSuccess { balance = it }
                                    }
                                }
                            } finally { isLoading = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.request_test_coins, tokenSymbol)) }
            }

            Card(
                modifier = Modifier.fillMaxWidth().clickable { onNavigateToHistory() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.History, contentDescription = "History", tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(stringResource(R.string.transaction_history), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }

    if (showAddressInputDialog) {
        AddressInputDialog(isMigration = isMigrationMode, onDismiss = { showAddressInputDialog = false },
            onScanRequest = {
                showAddressInputDialog = false
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showScanner = true
                else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        ) { addr -> destinationAddress = addr; showAddressInputDialog = false; showTransferDialog = true }
    }
    if (showTransferDialog) {
        TransferDialog(toAddress = destinationAddress, symbol = tokenSymbol, isMigration = isMigrationMode, 
            amount = if(isMigrationMode) balance else "10", onDismiss = { showTransferDialog = false }) { amountInput ->
            scope.launch {
                isLoading = true
                try {
                    withContext(Dispatchers.IO) {
                        val cleanTo = destinationAddress.trim().lowercase(Locale.ROOT).replace("0x", "")
                        val cleanFrom = derivedAddress.trim().lowercase(Locale.ROOT)
                        val messageToSign = "transfer:$cleanTo:${amountInput.trim().replace(".0", "")}"
                        val signature = KeyStoreManager.signData(messageToSign.toByteArray(Charsets.UTF_8))
                        val pubKeyBase64 = Base64.encodeToString(KeyStoreManager.getPublicKey(), Base64.NO_WRAP)

                        DLinkerApi.transfer(cleanFrom, destinationAddress.trim(), amountInput.trim(), signature, pubKeyBase64)
                            .onSuccess { 
                                withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.transfer_success), Toast.LENGTH_LONG).show() }
                                delay(5000)
                                DLinkerApi.syncBalance(derivedAddress).onSuccess { balance = it }
                            }
                    }
                } finally { isLoading = false; showTransferDialog = false; isMigrationMode = false }
            }
        }
    }
    if (showReceiptDialog) ReceiptDialog(address = derivedAddress) { showReceiptDialog = false }
    if (showSettingsDialog) SettingsDialog(onDismiss = { showSettingsDialog = false })
    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }) {
            Card(modifier = Modifier.fillMaxWidth().height(450.dp)) {
                Column {
                    Box(modifier = Modifier.weight(1f)) {
                        ScannerView(onScan = { raw -> 
                            destinationAddress = raw.trim().takeLast(42)
                            showScanner = false
                            showTransferDialog = true 
                        })
                    }
                    Button({ showScanner = false }, Modifier.fillMaxWidth().padding(8.dp)) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val tokenSymbol = stringResource(id = R.string.token_symbol)
    val scope = rememberCoroutineScope()
    
    var historyList by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var isHistoryLoading by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(1) }
    var hasMoreData by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    var walletAddress by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val addr = withContext(Dispatchers.IO) { getAddressFromPublicKey(KeyStoreManager.getPublicKey()) }
        walletAddress = addr
        isHistoryLoading = true
        DLinkerApi.getHistory(addr, page = 1).onSuccess { 
            historyList = it.history
            currentPage = 2
            hasMoreData = it.hasMore
        }
        isHistoryLoading = false
    }

    val shouldLoadMore = remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisibleItem != null && lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && hasMoreData && !isHistoryLoading && walletAddress.isNotEmpty()) {
            isHistoryLoading = true
            DLinkerApi.getHistory(walletAddress, page = currentPage).onSuccess {
                historyList = historyList + it.history
                currentPage++
                hasMoreData = it.hasMore
            }
            isHistoryLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transaction_history), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            isHistoryLoading = true
                            DLinkerApi.getHistory(walletAddress, page = 1).onSuccess { 
                                historyList = it.history
                                currentPage = 2
                                hasMoreData = it.hasMore
                            }
                            isHistoryLoading = false
                        }
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (historyList.isEmpty() && !isHistoryLoading) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.tx_no_history), color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState) {
                    items(historyList) { item ->
                        HistoryRow(item, tokenSymbol)
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (isHistoryLoading) {
                        item { CircularProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp).wrapContentWidth(Alignment.CenterHorizontally)) }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryRow(item: HistoryItem, symbol: String) {
    val isSend = item.type == "send"
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(40.dp).background(if (isSend) Color(0xFFFFEBEE).copy(alpha = if(isSystemInDarkTheme()) 0.2f else 1f) else Color(0xFFE8F5E9).copy(alpha = if(isSystemInDarkTheme()) 0.2f else 1f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (isSend) Icons.Default.NorthEast else Icons.Default.SouthWest, contentDescription = null, tint = if (isSend) Color.Red else Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (isSend) stringResource(R.string.tx_send) else stringResource(R.string.tx_receive), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(item.counterParty, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.date, fontSize = 10.sp, color = MaterialTheme.colorScheme.outlineVariant)
        }
        Text((if (isSend) "-" else "+") + " ${item.amount} $symbol", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = if (isSend) MaterialTheme.colorScheme.onSurface else Color(0xFF4CAF50))
    }
}

@Composable
fun AssetCard(balance: String, address: String, symbol: String) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth().height(180.dp), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(stringResource(R.string.my_assets), fontSize = 14.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                Text(stringResource(R.string.balance_format, balance, symbol), fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Column {
                Text(stringResource(R.string.device_wallet_address), fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(address, fontSize = 10.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = {
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Wallet", address))
                        Toast.makeText(context, context.getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
                    }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp)) }
                }
            }
        }
    }
}

@Composable
fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(onClick = onClick, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp)) { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) }
        Text(text, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun AddressInputDialog(isMigration: Boolean, onDismiss: () -> Unit, onScanRequest: () -> Unit, onConfirm: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(if (isMigration) stringResource(R.string.migration) else stringResource(R.string.manual_address_input), fontWeight = FontWeight.Bold)
                OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text(stringResource(R.string.address_placeholder)) },
                    trailingIcon = { IconButton(onClick = onScanRequest) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan") } },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button({ onConfirm(input.trim()) }) { Text(stringResource(R.string.confirm)) }
                }
            }
        }
    }
}

@Composable
fun TransferDialog(toAddress: String, symbol: String, isMigration: Boolean, amount: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var amountInput by remember { mutableStateOf(amount) }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(if(isMigration) stringResource(R.string.migration_title) else stringResource(R.string.send_symbol, symbol), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.to_address, toAddress), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                if (isMigration) { Spacer(Modifier.height(8.dp)); Text(stringResource(R.string.migration_desc), fontSize = 12.sp); Spacer(Modifier.height(8.dp)) }
                OutlinedTextField(value = amountInput, onValueChange = { if (!isMigration) amountInput = it }, readOnly = isMigration, label = { Text(stringResource(R.string.amount)) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text(stringResource(R.string.cancel)) }
                    Button({ onConfirm(amountInput) }) { Text(if(isMigration) stringResource(R.string.migration_confirm) else stringResource(R.string.confirm_send)) }
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
                if (qr != null) {
                    androidx.compose.foundation.Image(
                        bitmap = qr.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier.size(200.dp)
                    )
                }
                Text(address, fontSize = 10.sp); Button(onDismiss, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.settings), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.language_settings), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(8.dp))
                LanguageOption(stringResource(R.string.lang_auto), "") { setAppLocale(context, "") }
                LanguageOption(stringResource(R.string.lang_zh_tw), "zh-TW") { setAppLocale(context, "zh-TW") }
                LanguageOption(stringResource(R.string.lang_zh_cn), "zh-CN") { setAppLocale(context, "zh-CN") }
                LanguageOption(stringResource(R.string.lang_en), "en") { setAppLocale(context, "en") }
                Spacer(Modifier.height(16.dp)); Button(onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.close)) }
            }
        }
    }
}

@Composable
fun LanguageOption(label: String, tag: String, onClick: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp), color = Color.Transparent) { Text(label, fontSize = 16.sp) }
}

private fun setAppLocale(context: Context, languageTag: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager?.applicationLocales = if (languageTag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(languageTag)
    } else {
        val appLocale = if (languageTag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(languageTag)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }
}

@Composable
fun isSystemInDarkTheme(): Boolean {
    return androidx.compose.foundation.isSystemInDarkTheme()
}
