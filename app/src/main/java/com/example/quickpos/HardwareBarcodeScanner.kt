package com.example.quickpos

import android.view.KeyEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class HardwareBarcodeScanner {
    private val _scannedBarcodes = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val scannedBarcodes: SharedFlow<String> = _scannedBarcodes.asSharedFlow()
    private val buffer = StringBuilder()
    private var lastEventTime = 0L

    fun onKeyEvent(event: KeyEvent, isModalDialogOpen: Boolean): Boolean {
        if (event.deviceId <= 0 || KeyEvent.isModifierKey(event.keyCode)) return false
        if (event.action != KeyEvent.ACTION_DOWN) {
            return (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) && buffer.isNotEmpty()
        }
        val now = System.currentTimeMillis()
        if (now - lastEventTime > 80L) buffer.clear()
        lastEventTime = now

        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (buffer.length >= 3) {
                val code = buffer.toString().trim()
                buffer.clear()
                _scannedBarcodes.tryEmit(code)
                return true
            }
            buffer.clear()
            return false
        }
        val char = event.unicodeChar
        if (char > 0 && !Character.isISOControl(char)) {
            buffer.append(char.toChar())
            return !isModalDialogOpen
        }
        return false
    }
}