package com.example.quickpos

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DailySummary(
    val date: String,
    val cashTotal: Double,
    val cashBillCount: Int,
    val upiTotal: Double,
    val upiBillCount: Int,
    val totalItemsSold: Int
) {
    val totalBillCount: Int get() = cashBillCount + upiBillCount
    val grandTotal: Double get() = cashTotal + upiTotal
}

class DailySalesTracker(private val context: Context) {
    private val prefs = context.getSharedPreferences("quickpos_daily_sales", Context.MODE_PRIVATE)

    private fun todayKey(): String {
        return SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date())
    }

    fun recordSale(amount: Double, itemCount: Int, paymentMethod: PaymentMethod) {
        val dateKey = todayKey()
        val current = getTodaySummary()
        val editor = prefs.edit()
        when (paymentMethod) {
            PaymentMethod.CASH -> {
                editor.putLong("${dateKey}_cash_total", (current.cashTotal + amount).toRawBits())
                editor.putInt("${dateKey}_cash_count", current.cashBillCount + 1)
            }
            PaymentMethod.UPI -> {
                editor.putLong("${dateKey}_upi_total", (current.upiTotal + amount).toRawBits())
                editor.putInt("${dateKey}_upi_count", current.upiBillCount + 1)
            }
        }
        editor.putInt("${dateKey}_items_sold", current.totalItemsSold + itemCount)
        editor.apply()
    }

    fun getTodaySummary(): DailySummary {
        val dateKey = todayKey()
        val cashTotal = Double.fromBits(prefs.getLong("${dateKey}_cash_total", 0.0.toRawBits()))
        val cashCount = prefs.getInt("${dateKey}_cash_count", 0)
        val upiTotal = Double.fromBits(prefs.getLong("${dateKey}_upi_total", 0.0.toRawBits()))
        val upiCount = prefs.getInt("${dateKey}_upi_count", 0)
        val itemsSold = prefs.getInt("${dateKey}_items_sold", 0)
        return DailySummary(dateKey, cashTotal, cashCount, upiTotal, upiCount, itemsSold)
    }

    fun resetToday() {
        val dateKey = todayKey()
        prefs.edit()
            .remove("${dateKey}_cash_total").remove("${dateKey}_cash_count")
            .remove("${dateKey}_upi_total").remove("${dateKey}_upi_count")
            .remove("${dateKey}_items_sold")
            .apply()
    }

    fun shareSummaryToWhatsApp(storeInfo: StoreInfo, summary: DailySummary) {
        val message = buildString {
            appendLine("*${storeInfo.name}*")
            appendLine("*DAY-END SALES REPORT (${summary.date})*")
            appendLine("--------------------------------")
            appendLine("*Cash Sales:* ₹${String.format(Locale.US, "%.2f", summary.cashTotal)} (${summary.cashBillCount} bills)")
            appendLine("*UPI Sales:* ₹${String.format(Locale.US, "%.2f", summary.upiTotal)} (${summary.upiBillCount} bills)")
            appendLine("--------------------------------")
            appendLine("*Total Bills:* ${summary.totalBillCount}")
            appendLine("*Total Items Sold:* ${summary.totalItemsSold}")
            appendLine("*NET COLLECTION: ₹${String.format(Locale.US, "%.2f", summary.grandTotal)}*")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            setPackage("com.whatsapp")
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                intent.setPackage("com.whatsapp.w4b")
                context.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                val fallback = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, message)
                }
                context.startActivity(Intent.createChooser(fallback, "Share Sales Report via"))
            }
        }
    }

    fun exportSummaryToCsv(storeInfo: StoreInfo) {
        try {
            val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
            val csvFile = File(reportsDir, "QuickPOS_Sales_${todayKey()}.csv")
            val allDates = prefs.all.keys.map { it.substringBefore("_") }
                .filter { it.matches(Regex("\\d{2}-\\d{2}-\\d{4}")) }.distinct().sorted().ifEmpty { listOf(todayKey()) }

            val csv = buildString {
                appendLine("Store Name,Date,Cash Bills,Cash Amount (INR),UPI Bills,UPI Amount (INR),Total Bills,Total Items,Net Collection (INR)")
                for (date in allDates) {
                    val cTot = Double.fromBits(prefs.getLong("${date}_cash_total", 0.0.toRawBits()))
                    val cCnt = prefs.getInt("${date}_cash_count", 0)
                    val uTot = Double.fromBits(prefs.getLong("${date}_upi_total", 0.0.toRawBits()))
                    val uCnt = prefs.getInt("${date}_upi_count", 0)
                    val items = prefs.getInt("${date}_items_sold", 0)
                    appendLine(String.format(Locale.US, "\"%s\",%s,%d,%.2f,%d,%.2f,%d,%d,%.2f", storeInfo.name, date, cCnt, cTot, uCnt, uTot, cCnt + uCnt, items, cTot + uTot))
                }
            }
            csvFile.writeText(csv, Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", csvFile)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(share, "Export CSV Report"))
        } catch (e: Exception) {
            Toast.makeText(context, "CSV Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}