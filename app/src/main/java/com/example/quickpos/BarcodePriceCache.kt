package com.example.quickpos

import android.content.Context

data class RememberedItem(
    val barcode: String,
    val name: String,
    val price: Double
)

class BarcodePriceCache(context: Context) {
    private val prefs = context.getSharedPreferences("quickpos_barcode_cache", Context.MODE_PRIVATE)

    fun rememberItem(barcode: String, name: String, price: Double) {
        val cleanBarcode = barcode.trim()
        prefs.edit()
            .putString("${cleanBarcode}_name", name.trim())
            .putLong("${cleanBarcode}_price", price.toRawBits())
            .apply()
    }

    fun getRememberedItem(barcode: String): RememberedItem? {
        val cleanBarcode = barcode.trim()
        if (!prefs.contains("${cleanBarcode}_price")) return null
        val name = prefs.getString("${cleanBarcode}_name", "Item") ?: "Item"
        val price = Double.fromBits(prefs.getLong("${cleanBarcode}_price", 0.0.toRawBits()))
        return if (price > 0.0) RememberedItem(cleanBarcode, name, price) else null
    }
}