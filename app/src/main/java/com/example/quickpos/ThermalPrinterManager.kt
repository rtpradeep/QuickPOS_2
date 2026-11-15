package com.example.quickpos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

enum class PrinterSize(val label: String, val charWidth: Int) {
    THREE_INCH("3 Inch (80mm)", 48),
    FOUR_INCH("4 Inch (104mm)", 64)
}

enum class PaymentMethod(val label: String) {
    CASH("CASH"),
    UPI("UPI")
}

data class PosItem(
    val barcode: String,
    val name: String,
    val price: Double,
    val qty: Int
) {
    val total: Double get() = price * qty
}

data class StoreInfo(
    val name: String = "SHARMA GENERAL STORE",
    val addressLine1: String = "Station Road, Civil Lines",
    val addressLine2: String = "Etawah, UP - 206001",
    val phone: String = "+91 98765 43210",
    val taxId: String = "GSTIN: 09ABCDE1234F1Z5",
    val upiId: String = "9876543210@ybl",
    val logoBitmap: Bitmap? = null
)

class ThermalPrinterManager(private val context: Context) {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @SuppressLint("MissingPermission")
    fun getPairedPrinters(): List<BluetoothDevice> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.bondedDevices?.toList() ?: emptyList()
    }

    @SuppressLint("MissingPermission")
    suspend fun printReceipt(device: BluetoothDevice, storeInfo: StoreInfo, items: List<PosItem>, paymentMethod: PaymentMethod, printerSize: PrinterSize): Result<Unit> = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(sppUuid)
            socket.connect()
            val out = socket.outputStream
            out.write(buildEscPosReceipt(storeInfo, items, paymentMethod, printerSize))
            out.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun printDayEndReport(device: BluetoothDevice, storeInfo: StoreInfo, summary: DailySummary, printerSize: PrinterSize): Result<Unit> = withContext(Dispatchers.IO) {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createRfcommSocketToServiceRecord(sppUuid)
            socket.connect()
            val out = socket.outputStream
            out.write(buildDayEndReportBytes(storeInfo, summary, printerSize))
            out.flush()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    fun generateUpiQrBitmap(upiId: String, payeeName: String, amount: Double, sizePx: Int = 240): Bitmap {
        val formattedAmount = String.format(Locale.US, "%.2f", amount)
        val uri = Uri.Builder().scheme("upi").authority("pay")
            .appendQueryParameter("pa", upiId.trim())
            .appendQueryParameter("pn", payeeName.trim())
            .appendQueryParameter("am", formattedAmount)
            .appendQueryParameter("cu", "INR")
            .appendQueryParameter("tn", "POS Bill Payment")
            .build().toString()

        val alignedSize = ((sizePx + 7) / 8) * 8
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, alignedSize, alignedSize, hints)
        val pixels = IntArray(alignedSize * alignedSize)
        for (y in 0 until alignedSize) {
            for (x in 0 until alignedSize) {
                pixels[y * alignedSize + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(alignedSize, alignedSize, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, alignedSize, 0, 0, alignedSize, alignedSize)
        }
    }

    private fun buildEscPosReceipt(storeInfo: StoreInfo, items: List<PosItem>, paymentMethod: PaymentMethod, printerSize: PrinterSize): ByteArray {
        val width = printerSize.charWidth
        val divider = "-".repeat(width) + "\n"
        val dateStr = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date())

        val qtyWidth = 6
        val priceWidth = 10
        val totalWidth = 12
        val nameWidth = width - (qtyWidth + priceWidth + totalWidth)

        val out = ByteArrayOutputStream()
        fun writeBytes(b: ByteArray) = out.write(b)
        fun writeText(t: String) = out.write(t.toByteArray(Charsets.UTF_8))

        writeBytes(byteArrayOf(0x1B, 0x40, 0x1B, 0x61, 0x01))
        storeInfo.logoBitmap?.let {
            val maxW = if (printerSize == PrinterSize.FOUR_INCH) 320 else 256
            writeBytes(bitmapToRaster(it, maxW))
            writeText("\n")
        }

        writeBytes(byteArrayOf(0x1B, 0x45, 0x01, 0x1D, 0x21, 0x11))
        writeText("${storeInfo.name}\n")
        writeBytes(byteArrayOf(0x1D, 0x21, 0x00, 0x1B, 0x45, 0x00))

        if (storeInfo.addressLine1.isNotBlank()) writeText("${storeInfo.addressLine1}\n")
        if (storeInfo.addressLine2.isNotBlank()) writeText("${storeInfo.addressLine2}\n")
        if (storeInfo.phone.isNotBlank()) writeText("Tel: ${storeInfo.phone}\n")
        if (storeInfo.taxId.isNotBlank()) writeText("${storeInfo.taxId}\n")
        writeText("Date: $dateStr\n$divider")

        writeBytes(byteArrayOf(0x1B, 0x61, 0x00, 0x1B, 0x45, 0x01))
        writeText(String.format("%-${nameWidth}s%${qtyWidth}s%${priceWidth}s%${totalWidth}s\n", "ITEM", "QTY", "RATE", "TOTAL"))
        writeBytes(byteArrayOf(0x1B, 0x45, 0x00))
        writeText(divider)

        var grandTotal = 0.0
        for (item in items) {
            grandTotal += item.total
            val cleanName = if (item.name.length > nameWidth - 1) item.name.take(nameWidth - 1) else item.name
            writeText(String.format(Locale.US, "%-${nameWidth}s%${qtyWidth}d%${priceWidth}.2f%${totalWidth}.2f\n", cleanName, item.qty, item.price, item.total))
            if (item.barcode.isNotBlank() && item.barcode != "MANUAL") writeText("  [${item.barcode}]\n")
        }
        writeText(divider)

        writeBytes(byteArrayOf(0x1B, 0x45, 0x01))
        val totLabel = "GRAND TOTAL:"
        val totVal = String.format(Locale.US, "%.2f", grandTotal)
        writeText(totLabel + " ".repeat((width - totLabel.length - totVal.length).coerceAtLeast(1)) + totVal + "\n")
        writeBytes(byteArrayOf(0x1B, 0x45, 0x00))
        writeText("PAYMENT MODE: ${paymentMethod.label}\n$divider")

        if (paymentMethod == PaymentMethod.UPI && grandTotal > 0.0 && storeInfo.upiId.isNotBlank()) {
            writeBytes(byteArrayOf(0x1B, 0x61, 0x01, 0x1B, 0x45, 0x01))
            writeText("SCAN TO PAY EXACT AMOUNT\n")
            writeBytes(byteArrayOf(0x1B, 0x45, 0x00))
            val qrSize = if (printerSize == PrinterSize.FOUR_INCH) 288 else 240
            writeBytes(bitmapToRaster(generateUpiQrBitmap(storeInfo.upiId, storeInfo.name, grandTotal, qrSize), qrSize))
            writeText("\nUPI ID: ${storeInfo.upiId}\nPayable: Rs. $totVal\n$divider")
        }

        writeBytes(byteArrayOf(0x1B, 0x61, 0x01))
        writeText("Thank You for Shopping!\n\n\n")
        writeBytes(byteArrayOf(0x1D, 0x56, 0x41, 0x10))
        return out.toByteArray()
    }

    private fun buildDayEndReportBytes(storeInfo: StoreInfo, summary: DailySummary, printerSize: PrinterSize): ByteArray {
        val width = printerSize.charWidth
        val divider = "-".repeat(width) + "\n"
        val dDiv = "=".repeat(width) + "\n"
        val timeStr = SimpleDateFormat("dd-MM-yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        val out = ByteArrayOutputStream()

        fun writeBytes(b: ByteArray) = out.write(b)
        fun writeText(t: String) = out.write(t.toByteArray(Charsets.UTF_8))
        fun row(l: String, v: String) = l + " ".repeat((width - l.length - v.length).coerceAtLeast(1)) + v + "\n"

        writeBytes(byteArrayOf(0x1B, 0x40, 0x1B, 0x61, 0x01, 0x1B, 0x45, 0x01, 0x1D, 0x21, 0x11))
        writeText("${storeInfo.name}\n")
        writeBytes(byteArrayOf(0x1D, 0x21, 0x00))
        writeText("DAY-END Z-REPORT\n")
        writeBytes(byteArrayOf(0x1B, 0x45, 0x00))
        if (storeInfo.addressLine1.isNotBlank()) writeText("${storeInfo.addressLine1}\n")
        writeText("Report Date: ${summary.date}\nPrinted At: $timeStr\n$dDiv")

        writeBytes(byteArrayOf(0x1B, 0x61, 0x00, 0x1B, 0x45, 0x01))
        writeText(row("PAYMENT MODE", "AMOUNT (INR)"))
        writeBytes(byteArrayOf(0x1B, 0x45, 0x00))
        writeText(divider)
        writeText(row("CASH SALES (${summary.cashBillCount} Bills)", String.format(Locale.US, "%.2f", summary.cashTotal)))
        writeText(row("UPI SALES  (${summary.upiBillCount} Bills)", String.format(Locale.US, "%.2f", summary.upiTotal)))
        writeText(divider)
        writeText(row("Total Bills Generated:", "${summary.totalBillCount}"))
        writeText(row("Total Items Sold:", "${summary.totalItemsSold}"))
        writeText(dDiv)

        writeBytes(byteArrayOf(0x1B, 0x45, 0x01))
        writeText(row("NET COLLECTION:", "Rs. " + String.format(Locale.US, "%.2f", summary.grandTotal)))
        writeBytes(byteArrayOf(0x1B, 0x45, 0x00))
        writeText("$dDiv\nCashier Sign: ________________\n\n")

        writeBytes(byteArrayOf(0x1B, 0x61, 0x01))
        writeText("--- END OF DAY REPORT ---\n\n\n")
        writeBytes(byteArrayOf(0x1D, 0x56, 0x41, 0x10))
        return out.toByteArray()
    }

    private fun bitmapToRaster(source: Bitmap, maxWidthPx: Int): ByteArray {
        val targetWidth = ((maxWidthPx + 7) / 8) * 8
        val targetHeight = (targetWidth * (source.height.toFloat() / source.width.toFloat())).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
        val widthBytes = targetWidth / 8
        val stream = ByteArrayOutputStream()

        stream.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00, (widthBytes and 0xFF).toByte(), ((widthBytes shr 8) and 0xFF).toByte(), (targetHeight and 0xFF).toByte(), ((targetHeight shr 8) and 0xFF).toByte()))
        for (y in 0 until targetHeight) {
            for (xByte in 0 until widthBytes) {
                var slice = 0
                for (bit in 0 until 8) {
                    val pixel = scaled.getPixel(xByte * 8 + bit, y)
                    if (Color.alpha(pixel) > 128 && (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel)) < 150) {
                        slice = slice or (1 shl (7 - bit))
                    }
                }
                stream.write(slice)
            }
        }
        return stream.toByteArray()
    }
}