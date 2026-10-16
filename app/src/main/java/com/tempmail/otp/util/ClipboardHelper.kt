package com.tempmail.otp.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

/**
 * Utility to copy text to the Android system clipboard.
 * Uses ClipboardManager.setPrimaryClip() — the standard Android API.
 */
object ClipboardHelper {

    fun copyText(context: Context, label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
