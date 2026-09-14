package com.example.iqoscontroller

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Standardized thread-safe logger for BLE, USB, GATT, PROTOCOL, and CONNECTION events.
 * Retains an in-memory ring-buffer of log lines for real-time display in DebugActivity.
 */
object AppLogger {

    private const val MAX_LOGS = 300
    private val logEntries = CopyOnWriteArrayList<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    var onLogUpdated: (() -> Unit)? = null

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        append("[$tag] $message")
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("[$tag] $message")
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        append("[$tag] WARN: $message")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val errStr = if (throwable != null) " - ${throwable.localizedMessage}" else ""
        append("[$tag] ERROR: $message$errStr")
    }

    private fun append(line: String) {
        val timestamp = timeFormat.format(Date())
        val entry = "$timestamp $line"
        if (logEntries.size >= MAX_LOGS) {
            logEntries.removeAt(0)
        }
        logEntries.add(entry)
        onLogUpdated?.invoke()
    }

    fun getLogs(): List<String> = logEntries.toList()

    fun clear() {
        logEntries.clear()
        onLogUpdated?.invoke()
    }
}
