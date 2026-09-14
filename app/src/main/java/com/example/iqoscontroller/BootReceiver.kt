package com.example.iqoscontroller

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts [IqosBackgroundService] after a reboot - only if the user turned background sync on
 * in Settings. Does nothing otherwise. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            if (IqosBackgroundService.isEnabled(context)) {
                AppLogger.i("BACKGROUND", "Boot completed - restarting background sync service")
                IqosBackgroundService.start(context)
            }
        }
    }
}
