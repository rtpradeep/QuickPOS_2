package com.example.quickpos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val hardwareScanner = HardwareBarcodeScanner()
    var isPriceDialogOpen: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val printerManager = ThermalPrinterManager(this)
        setContent {
            MaterialTheme {
                PosScreen(
                    printerManager = printerManager,
                    hardwareScanner = hardwareScanner,
                    onDialogStateChanged = { isPriceDialogOpen = it }
                )
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (hardwareScanner.onKeyEvent(event, isPriceDialogOpen)) return true
        return super.dispatchKeyEvent(event)
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosScreen(
    printerManager: ThermalPrinterManager,
    hardwareScanner: HardwareBarcodeScanner,
    onDialogStateChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val cartItems = remember { mutableStateListOf<PosItem>() }
    var selectedPayment by remember { mutableStateOf(PaymentMethod.CASH) }
    var selectedPrinterSize by remember { mutableStateOf(PrinterSize.THREE_INCH) }

    val priceCache = remember { BarcodePriceCache(context) }
    val salesTracker = remember { DailySalesTracker(context) }
    val scanFeedback = remember { ScanFeedbackManager(context) }

    DisposableEffect(Unit) {
        onDispose { scanFeedback.release() }
    }

    var dailySummary by remember { mutableStateOf(salesTracker.getTodaySummary()) }
    var showSummaryDialog by remember { mutableStateOf(false) }
    var isPrintingDayReport by remember { mutableStateOf(false) }

    var scannedBarcode by remember { mutableStateOf<String?>(null) }
    var editingCartIndex by remember { mutableStateOf<Int?>(null) }
    var itemNameInput by remember { mutableStateOf("") }
    var itemPriceInput by remember { mutableStateOf("") }
    var itemQtyInput by remember { mutableStateOf("1") }

    var showPrinterDialog by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf<List<BluetoothDevice>>(emptyList()) }

    val storeInfo = remember { StoreInfo() }

    LaunchedEffect(scannedBarcode) {
        onDialogStateChanged(scannedBarcode != null)
    }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) {
            pairedDevices = printerManager.getPairedPrinters()
            showPrinterDialog = true
        } else {
            Toast.makeText(context, "Bluetooth permissions required", Toast.LENGTH_SHORT).show()
        }
    }

    val scanner = remember {
        GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder().enableAutoZoom().build())
    }

    fun handleBarcodeScanned(rawValue: String) {
        val cleanCode = rawValue.trim()
        val existingIndex = cartItems.indexOfFirst { it.barcode == cleanCode }
        if (existingIndex >= 0) {
            scanFeedback.playScanSuccess()
            val existing = cartItems[existingIndex]
            cartItems[existingIndex] = existing.copy(qty = existing.qty + 1)
            Toast.makeText(context, "${existing.name} (x${existing.qty + 1})", Toast.LENGTH_SHORT).show()
            return
        }

        val remembered = priceCache.getRememberedItem(cleanCode)
        if (remembered != null) {
            scanFeedback.playScanSuccess()
            cartItems.add(PosItem(remembered.barcode, remembered.name, remembered.price, 1))
            Toast.makeText(context, "Added ${remembered.name} - ₹${remembered.price}", Toast.LENGTH_SHORT).show()
            return
        }

        scanFeedback.playPriceRequiredAlert()
        editingCartIndex = null
        scannedBarcode = cleanCode
        itemNameInput = "Item ${cartItems.size + 1}"
        itemPriceInput = ""
        itemQtyInput = "1"
    }

    LaunchedEffect(hardwareScanner) {
        hardwareScanner.scannedBarcodes.collect { code -> handleBarcodeScanned(code) }
    }

    fun submitScannedItem() {
        val price = itemPriceInput.toDoubleOrNull()
        val qty = itemQtyInput.toIntOrNull() ?: 1
        val code = scannedBarcode ?: return

        if (price != null && price > 0.0) {
            val defaultLabel = if (code == "MANUAL") "Loose Item ${cartItems.size + 1}" else "Item ${cartItems.size + 1}"
            val finalName = itemNameInput.ifBlank { defaultLabel }
            val finalQty = qty.coerceAtLeast(1)

            if (code != "MANUAL") {
                priceCache.rememberItem(code, finalName, price)
            }

            val editIdx = editingCartIndex
            if (editIdx != null && editIdx in cartItems.indices) {
                cartItems[editIdx] = PosItem(code, finalName, price, finalQty)
            } else {
                cartItems.add(PosItem(code, finalName, price, finalQty))
            }

            scanFeedback.playScanSuccess()
            editingCartIndex = null
            scannedBarcode = null
        } else {
            Toast.makeText(context, "Enter a valid price", Toast.LENGTH_SHORT).show()
        }
    }

    val grandTotal = cartItems.sumOf { it.total }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QuickPOS") },
                actions = {
                    OutlinedButton(
                        onClick = {
                            dailySummary = salesTracker.getTodaySummary()
                            showSummaryDialog = true
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("Day Report (₹${String.format("%.0f", dailySummary.grandTotal)})")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 1. Action Buttons Row (Scan + Loose Item)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        scanner.startScan()
                            .addOnSuccessListener { barcode -> handleBarcodeScanned(barcode.rawValue ?: "UNKNOWN") }
                            .addOnFailureListener { e -> Toast.makeText(context, "Scan cancelled: ${e.message}", Toast.LENGTH_SHORT).show() }
                    },
                    modifier = Modifier.weight(1.4f).height(54.dp)
                ) {
                    Text("SCAN BARCODE", fontWeight = FontWeight.Bold)
                }

                FilledTonalButton(
                    onClick = {
                        editingCartIndex = null
                        scannedBarcode = "MANUAL"
                        itemNameInput = "Loose Item ${cartItems.size + 1}"
                        itemPriceInput = ""
                        itemQtyInput = "1"
                    },
                    modifier = Modifier.weight(1f).height(54.dp)
                ) {
                    Text("+ LOOSE ITEM", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text("Cart Items (${cartItems.size})", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // 2. Cart List
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                itemsIndexed(cartItems) { index, item ->
                    Card(
                        onClick = {
                            editingCartIndex = index
                            scannedBarcode = item.barcode
                            itemNameInput = item.name
                            itemPriceInput = item.price.toString()
                            itemQtyInput = item.qty.toString()
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, fontWeight = FontWeight.Bold)
                                Text("Code: ${item.barcode} (Tap to edit)", style = MaterialTheme.typography.bodySmall)
                                Text("${item.qty} x ₹${item.price}", style = MaterialTheme.typography.bodyMedium)
                            }
                            Text("₹${String.format("%.2f", item.total)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            TextButton(onClick = { cartItems.removeAt(index) }) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 3. Payment Mode & Printer Selectors
            PaymentAndPrinterSelectors(
                selectedPayment = selectedPayment,
                onPaymentSelected = { selectedPayment = it },
                selectedPrinterSize = selectedPrinterSize,
                onPrinterSizeSelected = { selectedPrinterSize = it }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 4. Grand Total & Checkout Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Grand Total", style = MaterialTheme.typography.bodyMedium)
                    Text("₹${String.format("%.2f", grandTotal)}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }

                Button(
                    enabled = cartItems.isNotEmpty(),
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            btPermissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN))
                        } else {
                            pairedDevices = printerManager.getPairedPrinters()
                            showPrinterDialog = true
                        }
                    },
                    modifier = Modifier.height(54.dp)
                ) {
                    Text("PRINT RECEIPT")
                }
            }
        }
    }

    // Dialogs
    if (scannedBarcode != null) {
        PriceEntryDialog(
            barcode = scannedBarcode!!,
            itemName = itemNameInput,
            onItemNameChange = { itemNameInput = it },
            itemPrice = itemPriceInput,
            onItemPriceChange = { itemPriceInput = it },
            itemQty = itemQtyInput,
            onItemQtyChange = { itemQtyInput = it },
            onSubmit = ::submitScannedItem,
            onDismiss = { scannedBarcode = null }
        )
    }

    if (showSummaryDialog) {
        DaySummaryDialog(
            summary = dailySummary,
            onWhatsAppShare = { salesTracker.shareSummaryToWhatsApp(storeInfo, dailySummary) },
            onCsvExport = { salesTracker.exportSummaryToCsv(storeInfo) },
            onPrintReport = {
                showSummaryDialog = false
                isPrintingDayReport = true
                pairedDevices = printerManager.getPairedPrinters()
                showPrinterDialog = true
            },
            onResetDay = {
                salesTracker.resetToday()
                dailySummary = salesTracker.getTodaySummary()
                Toast.makeText(context, "Daily totals reset", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSummaryDialog = false }
        )
    }

    if (showPrinterDialog) {
        PrinterSelectionDialog(
            devices = pairedDevices,
            isDayReport = isPrintingDayReport,
            onDeviceSelected = { device ->
                showPrinterDialog = false
                scope.launch {
                    if (isPrintingDayReport) {
                        isPrintingDayReport = false
                        printerManager.printDayEndReport(device, storeInfo, dailySummary, selectedPrinterSize)
                            .onSuccess { Toast.makeText(context, "Z-Report Printed!", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "Print error: ${it.message}", Toast.LENGTH_LONG).show() }
                    } else {
                        val billTotal = cartItems.sumOf { it.total }
                        val itemCount = cartItems.sumOf { it.qty }
                        val paymentUsed = selectedPayment

                        printerManager.printReceipt(device, storeInfo, cartItems.toList(), paymentUsed, selectedPrinterSize)
                            .onSuccess {
                                salesTracker.recordSale(billTotal, itemCount, paymentUsed)
                                dailySummary = salesTracker.getTodaySummary()
                                Toast.makeText(context, "Receipt Printed & Sale Logged!", Toast.LENGTH_SHORT).show()
                                cartItems.clear()
                            }
                            .onFailure { Toast.makeText(context, "Print error: ${it.message}", Toast.LENGTH_LONG).show() }
                    }
                }
            },
            onDismiss = {
                showPrinterDialog = false
                isPrintingDayReport = false
            }
        )
    }
}

@Composable
fun PaymentAndPrinterSelectors(
    selectedPayment: PaymentMethod,
    onPaymentSelected: (PaymentMethod) -> Unit,
    selectedPrinterSize: PrinterSize,
    onPrinterSizeSelected: (PrinterSize) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Payment Mode", style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PaymentMethod.entries.forEach { method ->
                FilterChip(
                    selected = selectedPayment == method,
                    onClick = { onPaymentSelected(method) },
                    label = { Text(method.label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text("Thermal Printer Size", style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrinterSize.entries.forEach { size ->
                FilterChip(
                    selected = selectedPrinterSize == size,
                    onClick = { onPrinterSizeSelected(size) },
                    label = { Text(size.label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun PriceEntryDialog(
    barcode: String,
    itemName: String,
    onItemNameChange: (String) -> Unit,
    itemPrice: String,
    onItemPriceChange: (String) -> Unit,
    itemQty: String,
    onItemQtyChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    val priceFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        delay(100)
        priceFocusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (barcode == "MANUAL") "Add Loose / Unbarcoded Item" else "Add Scanned Item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (barcode != "MANUAL") {
                    Text("Scanned Barcode: $barcode", fontWeight = FontWeight.SemiBold)
                }
                OutlinedTextField(
                    value = itemPrice,
                    onValueChange = onItemPriceChange,
                    label = { Text("Unit Price (₹)") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(priceFocusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                onSubmit(); true
                            } else false
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() })
                )
                OutlinedTextField(
                    value = itemQty,
                    onValueChange = onItemQtyChange,
                    label = { Text("Quantity") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                                onSubmit(); true
                            } else false
                        },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() })
                )
                OutlinedTextField(
                    value = itemName,
                    onValueChange = onItemNameChange,
                    label = { Text("Item Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() })
                )
            }
        },
        confirmButton = {
            Button(onClick = onSubmit) { Text("Add to Bill (Enter)") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun DaySummaryDialog(
    summary: DailySummary,
    onWhatsAppShare: () -> Unit,
    onCsvExport: () -> Unit,
    onPrintReport: () -> Unit,
    onResetDay: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Day-End Summary (${summary.date})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Cash Drawer", style = MaterialTheme.typography.labelMedium)
                        Text("₹${String.format("%.2f", summary.cashTotal)} (${summary.cashBillCount} bills)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("UPI Settlements", style = MaterialTheme.typography.labelMedium)
                        Text("₹${String.format("%.2f", summary.upiTotal)} (${summary.upiBillCount} bills)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Text("Total Bills: ${summary.totalBillCount}")
                Text("Total Items Sold: ${summary.totalItemsSold}")
                Text("Net Collection: ₹${String.format("%.2f", summary.grandTotal)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

                Spacer(modifier = Modifier.height(4.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onWhatsAppShare, modifier = Modifier.weight(1f)) {
                        Text("WhatsApp")
                    }
                    OutlinedButton(onClick = onCsvExport, modifier = Modifier.weight(1f)) {
                        Text("Export CSV")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onPrintReport) { Text("Print Z-Report") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onResetDay) {
                    Text("Reset Day", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
fun PrinterSelectionDialog(
    devices: List<BluetoothDevice>,
    isDayReport: Boolean,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isDayReport) "Print Day-End Z-Report" else "Select Bluetooth Printer") },
        text = {
            if (devices.isEmpty()) {
                Text("No paired Bluetooth thermal printers found. Please pair your 3-inch or 4-inch printer in Android Bluetooth Settings first.")
            } else {
                LazyColumn {
                    itemsIndexed(devices) { _, device ->
                        TextButton(
                            onClick = { onDeviceSelected(device) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${device.name ?: "Unknown"} (${device.address})")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}