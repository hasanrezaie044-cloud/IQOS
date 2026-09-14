package com.example.iqoscontroller

import android.os.Handler
import android.os.Looper
import java.util.LinkedList
import java.util.Queue

/**
 * Robust FIFO Queue for Android BluetoothGatt operations.
 * Prevents overlapping asynchronous GATT calls and enforces a safety timeout per operation.
 */
class GattQueue(private val onOperationTimeout: () -> Unit) {

    private val queue: Queue<Runnable> = LinkedList()
    private var isBusy = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activeTimeoutRunnable: Runnable? = null

    companion object {
        private const val OPERATION_TIMEOUT_MS = 5000L
    }

    @Synchronized
    fun enqueue(operation: Runnable) {
        queue.add(operation)
        if (!isBusy) {
            processNext()
        }
    }

    @Synchronized
    fun onOperationCompleted() {
        cancelTimeout()
        isBusy = false
        processNext()
    }

    @Synchronized
    private fun processNext() {
        val nextOp = queue.poll()
        if (nextOp != null) {
            isBusy = true
            scheduleTimeout()
            mainHandler.post(nextOp)
        } else {
            isBusy = false
        }
    }

    private fun scheduleTimeout() {
        cancelTimeout()
        val timeoutRunnable = Runnable {
            AppLogger.w("GATT", "GATT Operation timed out after ${OPERATION_TIMEOUT_MS}ms")
            onOperationCompleted()
            onOperationTimeout()
        }
        activeTimeoutRunnable = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, OPERATION_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        activeTimeoutRunnable?.let {
            mainHandler.removeCallbacks(it)
            activeTimeoutRunnable = null
        }
    }

    @Synchronized
    fun clear() {
        cancelTimeout()
        queue.clear()
        isBusy = false
    }
}
