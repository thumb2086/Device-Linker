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
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.coroutineScope
import androidx.work.*
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
import java.util.concurrent.TimeUnit

// 定義導航狀態
enum class Screen { Dashboard, History, Contacts }

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

        setupBalanceCheckWorker()

        setContent {
            DeviceLinkerTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var currentScreen by remember { mutableStateOf(Screen.Dashboard) }
                    var preFilledAddress by remember { mutableStateOf("") }
                    var isPickingForTransfer by remember { mutableStateOf(false) }
                    var isMigrationMode by remember { mutableStateOf(false) }

                    when (currentScreen) {
                        Screen.Dashboard -> DashboardScreen(
                            initialAddress = preFilledAddress,
                            isMigration = isMigrationMode,
                            onIsMigrationChange = { isMigrationMode = it },
                            onNavigateToHistory = { currentScreen = Screen.History },
                            onNavigateToContacts = { 
                                isPickingForTransfer = false
                                isMigrationMode = false
                                currentScreen = Screen.Contacts 
                            },
                            onPickFromContacts = {
                                isPickingForTransfer = true
                                currentScreen = Screen.Contacts
                            },
                            onAddressUsed = { preFilledAddress = "" }
                        )
                        Screen.History -> {
                            BackHandler { currentScreen = Screen.Dashboard }
                            HistoryScreen(onBack = { currentScreen = Screen.Dashboard })
                        }
                        Screen.Contacts -> {
                            BackHandler { currentScreen = Screen.Dashboard }
                            ContactsScreen(
                                isSelectionMode = isPickingForTransfer,
                                onBack = { currentScreen = Screen.Dashboard },
                                onSelect = { addr ->
                                    preFilledAddress = addr
                                    currentScreen = Screen.Dashboard
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setupBalanceCheckWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val balanceWorkRequest = PeriodicWorkRequestBuilder<BalanceCheckWorker>(15, TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork("BalanceUpdateCheck", ExistingPeriodicWorkPolicy.KEEP, balanceWorkRequest)
    }
}

private suspend fun updateBalanceAndNotify(context: Context, address: String, onUpdate: (String) -> Unit) {
    DLinkerApi.syncBalance(address).onSuccess { newBalanceStr ->
        val newBalance = newBalanceStr.toDoubleOrNull() ?: 0.0
        val sharedPrefs = context.getSharedPreferences("DLinkerPrefs", Context.MODE_PRIVATE)
        val lastBalance = sharedPrefs.getString("last_known_balance", "0.0")?.toDoubleOrNull() ?: 0.0

        if (newBalance > lastBalance) {
            NotificationHelper.sendBalanceNotification(context, newBalance - lastBalance, newBalance)
        }

        onUpdate(newBalanceStr)
        sharedPrefs.edit().putString("last_known_balance", newBalanceStr).apply()
    }
}

@SuppressLint("HardwareIds")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    initialAddress: String,
    isMigration: Boolean,
    onIsMigrationChange: (Boolean) -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToContacts: () -> Unit,
    onPickFromContacts: () -> Unit,
    onAddressUsed: () -> Unit
) {
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
    var showSettingsDialog by remember { mutableStateOf(false) }

    // 用於確認對話框的狀態
    var showAuthConfirm by remember { mutableStateOf<String?>(null) }
    var showBetConfirm by remember { mutableStateOf<Triple<String, String, String>?>(null) } // gameId, side, amount

    LaunchedEffect(initialAddress) {
        if (initialAddress.isNotEmpty()) {
            destinationAddress = initialAddress
            showAddressInputDialog = true
            onAddressUsed()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { _ -> }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) showScanner = true
        else Toast.makeText(context, context.getString(R.string.camera_permission_required), Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val addr = withContext(Dispatchers.IO) {
            try { getAddressFromPublicKey(KeyStoreManager.getPublicKey()) } catch (e: Exception) { "0xError" }
        }
        derivedAddress = addr

        if (addr.startsWith("0x") && addr.length > 10) {
            DLinkerApi.syncBalance(addr).onSuccess { 
                balance = it
                context.getSharedPreferences("DLinkerPrefs", Context.MODE_PRIVATE).edit().putString("last_known_balance", it).apply()
            }
            while(true) {
                updateBalanceAndNotify(context, addr) { balance = it }
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
                            updateBalanceAndNotify(context, derivedAddress) { balance = it }
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
                ActionButton(stringResource(R.string.wallet_auth), Icons.Default.QrCodeScanner) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showScanner = true
                    else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
                ActionButton(stringResource(R.string.transfer), Icons.AutoMirrored.Filled.Send) { 
                    onIsMigrationChange(false)
                    showAddressInputDialog = true 
                }
                ActionButton(stringResource(R.string.migration), Icons.Default.SwapHoriz) { 
                    onIsMigrationChange(true)
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
                                    val signature = KeyStoreManager.signData(derivedAddress.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
                                    DLinkerApi.requestAirdrop(derivedAddress.trim().lowercase(Locale.ROOT), pubKey, signature).onSuccess { 
                                        withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.airdrop_request_sent), Toast.LENGTH_SHORT).show() }
                                        delay(2000)
                                        updateBalanceAndNotify(context, derivedAddress) { balance = it }
                                    }
                                }
                            } finally { isLoading = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) { Text(stringResource(R.string.request_test_coins, tokenSymbol)) }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NavigationCard(stringResource(R.string.transaction_history), Icons.Default.History) { onNavigateToHistory() }
                NavigationCard("通訊錄", Icons.Default.ContactPage) { onNavigateToContacts() }
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }

    if (showAddressInputDialog) {
        AddressInputDialog(isMigration = isMigration, preFilled = destinationAddress,
            onDismiss = { showAddressInputDialog = false; destinationAddress = "" },
            onScanRequest = {
                showAddressInputDialog = false
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) showScanner = true
                else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            },
            onSelectFromContacts = {
                showAddressInputDialog = false
                onPickFromContacts()
            }
        ) { addr -> destinationAddress = addr; showAddressInputDialog = false; showTransferDialog = true }
    }
    if (showTransferDialog) {
        TransferDialog(toAddress = destinationAddress, symbol = tokenSymbol, isMigration = isMigration,
            amount = if(isMigration) balance else "10", onDismiss = { showTransferDialog = false }) { amountInput ->
            scope.launch {
                isLoading = true; showTransferDialog = false
                try {
                    withContext(Dispatchers.IO) {
                        val cleanTo = destinationAddress.trim().lowercase(Locale.ROOT).replace("0x", "")
                        val cleanFrom = derivedAddress.trim().lowercase(Locale.ROOT)
                        val signature = KeyStoreManager.signData("transfer:$cleanTo:${amountInput.trim().replace(".0", "")}".toByteArray(Charsets.UTF_8))
                        DLinkerApi.transfer(cleanFrom, destinationAddress.trim(), amountInput.trim(), signature, Base64.encodeToString(KeyStoreManager.getPublicKey(), Base64.NO_WRAP))
                            .onSuccess { 
                                withContext(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.transfer_success), Toast.LENGTH_LONG).show() }
                                delay(3000); updateBalanceAndNotify(context, derivedAddress) { balance = it }
                            }
                    }
                } finally { isLoading = false; onIsMigrationChange(false) }
            }
        }
    }
    if (showReceiptDialog) ReceiptDialog(address = derivedAddress) { showReceiptDialog = false }
    if (showSettingsDialog) SettingsDialog(onDismiss = { showSettingsDialog = false })
    
    // 登入授權確認對話框
    showAuthConfirm?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { showAuthConfirm = null },
            title = { Text(stringResource(R.string.auth_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.auth_confirm_desc, sessionId, derivedAddress)) },
            confirmButton = {
                Button(onClick = {
                    val sid = sessionId
                    showAuthConfirm = null
                    scope.launch {
                        isLoading = true
                        try {
                            val pubKey = Base64.encodeToString(KeyStoreManager.getPublicKey(), Base64.NO_WRAP)
                            DLinkerApi.sendAuth(sid, derivedAddress, pubKey).onSuccess {
                                Toast.makeText(context, context.getString(R.string.auth_success), Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, context.getString(R.string.failure_message, it.message), Toast.LENGTH_LONG).show()
                            }
                        } finally { isLoading = false }
                    }
                }) { Text(stringResource(R.string.auth_confirm_button)) }
            },
            dismissButton = { TextButton(onClick = { showAuthConfirm = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    // 下注簽名確認對話框
    showBetConfirm?.let { betData ->
        AlertDialog(
            onDismissRequest = { showBetConfirm = null },
            title = { Text(stringResource(R.string.bet_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.bet_confirm_desc, "Coin Flip", betData.second, betData.third, tokenSymbol)) },
            confirmButton = {
                Button(onClick = {
                    val (gameId, side, amount) = betData
                    showBetConfirm = null
                    scope.launch {
                        isLoading = true
                        try {
                            val signMsg = "coinflip:$side:$amount"
                            val signature = KeyStoreManager.signData(signMsg.toByteArray(Charsets.UTF_8))
                            val pubKey = Base64.encodeToString(KeyStoreManager.getPublicKey(), Base64.NO_WRAP)
                            DLinkerApi.sendCoinFlip(gameId, derivedAddress, side, amount, signature, pubKey).onSuccess {
                                Toast.makeText(context, context.getString(R.string.bet_success), Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, context.getString(R.string.failure_message, it.message), Toast.LENGTH_LONG).show()
                            }
                        } finally { isLoading = false }
                    }
                }) { Text(stringResource(R.string.bet_confirm_button)) }
            },
            dismissButton = { TextButton(onClick = { showBetConfirm = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showScanner) {
        Dialog(onDismissRequest = { showScanner = false }) {
            Card(modifier = Modifier.fillMaxWidth().height(450.dp)) {
                Column {
                    Box(modifier = Modifier.weight(1f)) {
                        ScannerView(onScan = { raw ->
                            showScanner = false
                            val data = raw.trim()
                            
                            when {
                                data.startsWith("dlinker:login:") -> {
                                    showAuthConfirm = data.substringAfter("dlinker:login:")
                                }
                                data.startsWith("dlinker:coinflip:") -> {
                                    val parts = data.split(":")
                                    if (parts.size >= 5) {
                                        showBetConfirm = Triple(parts[2], parts[3], parts[4])
                                    }
                                }
                                else -> {
                                    destinationAddress = data.takeLast(42)
                                    showTransferDialog = true
                                }
                            }
                        })
                    }
                    Button({ showScanner = false }, Modifier.fillMaxWidth().padding(8.dp)) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }
}

@Composable
fun NavigationCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForwardIos, contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
fun TransferDialog(toAddress: String, symbol: String, isMigration: Boolean, amount: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var amountInput by remember(amount) { mutableStateOf(amount) }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(isSelectionMode: Boolean, onBack: () -> Unit, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { AppDatabase.getDatabase(context) }
    val contacts by db.contactDao().getAllContacts().collectAsState(initial = emptyList())

    var showAddDialog by remember { mutableStateOf(false) }
    var contactName by remember { mutableStateOf("") }
    var contactAddress by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if(isSelectionMode) "選擇聯絡人" else "通訊錄", fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add") } }
            )
        }
    ) { padding ->
        if (contacts.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("目前尚無聯絡人", color = MaterialTheme.colorScheme.outline)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(contacts) { contact ->
                    ListItem(
                        headlineContent = { Text(contact.name, fontWeight = FontWeight.Bold) },
                        supportingContent = { Text(contact.address, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline) },
                        trailingContent = {
                            if (!isSelectionMode) {
                                IconButton(onClick = { scope.launch { db.contactDao().delete(contact) } }) { Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red) }
                            }
                        },
                        modifier = Modifier.clickable {
                            if (isSelectionMode) {
                                onSelect(contact.address)
                            } else {
                                val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cb.setPrimaryClip(ClipData.newPlainText("Contact", contact.address))
                                Toast.makeText(context, "地址已複製", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    HorizontalDivider(thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("新增聯絡人") },
            text = {
                Column {
                    OutlinedTextField(value = contactName, onValueChange = { contactName = it }, label = { Text("姓名") })
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = contactAddress, onValueChange = { contactAddress = it }, label = { Text("錢包地址") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (contactName.isNotBlank() && contactAddress.isNotBlank()) {
                        scope.launch {
                            db.contactDao().insert(Contact(name = contactName, address = contactAddress))
                            showAddDialog = false; contactName = ""; contactAddress = ""
                        }
                    }
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("取消") } }
        )
    }
}

@Composable
fun AddressInputDialog(isMigration: Boolean, preFilled: String = "", onDismiss: () -> Unit, onScanRequest: () -> Unit, onSelectFromContacts: () -> Unit, onConfirm: (String) -> Unit) {
    var input by remember { mutableStateOf(preFilled) }
    LaunchedEffect(preFilled) { if (preFilled.isNotEmpty()) input = preFilled }
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(if (isMigration) stringResource(R.string.migration) else stringResource(R.string.manual_address_input), fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, label = { Text(stringResource(R.string.address_placeholder)) },
                    trailingIcon = { Row { IconButton(onClick = onSelectFromContacts) { Icon(Icons.Default.Contacts, contentDescription = "Contacts") } ; IconButton(onClick = onScanRequest) { Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan") } } },
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
        walletAddress = addr; isHistoryLoading = true
        DLinkerApi.getHistory(addr, page = 1).onSuccess { historyList = it.history; currentPage = 2; hasMoreData = it.hasMore }
        isHistoryLoading = false
    }

    val shouldLoadMore = remember { derivedStateOf { val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull() ; lastVisibleItem != null && lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 3 } }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && hasMoreData && !isHistoryLoading && walletAddress.isNotEmpty()) {
            isHistoryLoading = true
            DLinkerApi.getHistory(walletAddress, page = currentPage).onSuccess { historyList = historyList + it.history; currentPage++; hasMoreData = it.hasMore }
            isHistoryLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.transaction_history), fontWeight = FontWeight.Bold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = { scope.launch { isHistoryLoading = true; DLinkerApi.getHistory(walletAddress, page = 1).onSuccess { historyList = it.history; currentPage = 2; hasMoreData = it.hasMore }; isHistoryLoading = false } }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") } }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (historyList.isEmpty() && !isHistoryLoading) {
                Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.tx_no_history), color = MaterialTheme.colorScheme.outline)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), state = listState) {
                    items(historyList) { item -> HistoryRow(item, tokenSymbol); HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant) }
                    if (isHistoryLoading) { item { CircularProgressIndicator(modifier = Modifier.fillMaxWidth().padding(16.dp).wrapContentWidth(Alignment.CenterHorizontally)) } }
                }
            }
        }
    }
}

@Composable
fun HistoryRow(item: HistoryItem, symbol: String) {
    val isSend = item.type == "send"
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(40.dp).background(if (isSend) Color(0xFFFFEBEE).copy(alpha = if(androidx.compose.foundation.isSystemInDarkTheme()) 0.2f else 1f) else Color(0xFFE8F5E9).copy(alpha = if(androidx.compose.foundation.isSystemInDarkTheme()) 0.2f else 1f), CircleShape), contentAlignment = Alignment.Center) { Icon(if (isSend) Icons.Default.NorthEast else Icons.Default.SouthWest, contentDescription = null, tint = if (isSend) Color.Red else Color(0xFF4CAF50), modifier = Modifier.size(20.dp)) }
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
                    IconButton(onClick = { val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cb.setPrimaryClip(ClipData.newPlainText("Wallet", address)); Toast.makeText(context, context.getString(R.string.copy_success), Toast.LENGTH_SHORT).show() }, modifier = Modifier.size(24.dp)) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp)) }
                }
            }
        }
    }
}

@Composable
fun ActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) { Column(horizontalAlignment = Alignment.CenterHorizontally) { FilledIconButton(onClick = onClick, modifier = Modifier.size(56.dp), shape = RoundedCornerShape(16.dp)) { Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp)) } ; Text(text, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp)) } }

@Composable
fun ReceiptDialog(address: String, onDismiss: () -> Unit) { Dialog(onDismissRequest = onDismiss) { Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) { Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) { Text(stringResource(R.string.receive_address), fontWeight = FontWeight.Bold) ; val qr = remember(address) { QrCodeUtils.generateQrCode(address, 500) } ; if (qr != null) { androidx.compose.foundation.Image(bitmap = qr.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.size(200.dp)) } ; Text(address, fontSize = 10.sp); Button(onDismiss, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.close)) } } } } }

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
fun LanguageOption(label: String, tag: String, onClick: () -> Unit) { Surface(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 12.dp), color = Color.Transparent) { Text(label, fontSize = 16.sp) } }

private fun setAppLocale(context: Context, languageTag: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeManager = context.getSystemService(LocaleManager::class.java)
        localeManager?.applicationLocales = if (languageTag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(languageTag)
    } else {
        val appLocale = if (languageTag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(languageTag)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }
}
