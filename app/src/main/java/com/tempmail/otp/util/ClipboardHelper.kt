package com.tempmail.otp.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Utility to copy text to the Android system clipboard.
 *
 * WebSocket callbacks arrive on OkHttp reader threads, which have no Looper, and
 * `Toast.show()` throws on such threads. Everything is therefore posted to the main
 * thread, which also keeps the clipboard write itself on the UI thread.
 */
object ClipboardHelper {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun copyText(context: Context, label: String, text: String) {
        val appContext = context.applicationContext
        val copy = Runnable {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(appContext, "$label copied to clipboard", Toast.LENGTH_SHORT).show()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            copy.run()
        } else {
            mainHandler.post(copy)
        }
    }
}
