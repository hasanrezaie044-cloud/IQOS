package com.example.iqoscontroller

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Keeps the app "connected" to the user's IQOS even while the app itself is closed, so puffs
 * taken while the phone is nearby (nightstand, pocket, etc.) but the app isn't open still get
 * recorded on the correct calendar day instead of silently folding into whichever day the app
 * next happens to be opened on.
 *
 * How it actually works (no magic, no guessing):
 * 1. It shares the exact same [IqosConnectionManager] singleton the foreground UI uses, so there
 *    is never more than one real BLE connection - this service and MainActivity simply both
 *    listen to it.
 * 2. It asks Android's own Bluetooth stack to reconnect automatically whenever the saved device
 *    is reachable again (`autoConnect = true` - see [BleIqosTransport.connectToAddressAutoReconnect]),
 *    which is the standard, battery-reasonable way to do this - no custom scan loop needed.
 * 3. Every time the link comes up, and then periodically while it stays up, it re-issues the same
 *    read-only "read everything" sequence the app already sends
 *    ([IqosConnectionManager.refreshFullSnapshot]) so the day's puff baseline gets refreshed
 *    before the next puff happens, not after. That is what actually fixes "puffs taken before the
 *    day's first app-open get lost".
 * 4. Every puff it reads goes through the exact same [UsageTracker.recordReading] the foreground
 *    UI uses, which already triggers [NotificationHelper]'s usage notification - so a "new tera
 *    recorded" notification appears even with the app fully closed, with no separate/duplicate
 *    notification logic needed here.
 *
 * Honesty about limits: this can only reconnect while the *phone* is within Bluetooth range of
 * the device and Bluetooth is on. It cannot recover puffs taken while the phone itself was out of
 * range, and Android's battery optimisation on some manufacturers (Samsung, Xiaomi, etc.) may
 * still kill this service unless the user exempts the app - there is no way for any app to
 * override that from code alone, only the user can grant the exemption
 * (see [MainActivity] Settings tab).
 */
class IqosBackgroundService : Service() {

    private lateinit var connectionManager: IqosConnectionManager
    private lateinit var usageTracker: UsageTracker
    private lateinit var deviceRegistry: DeviceRegistry
    private val handler = Handler(Looper.getMainLooper())

    private var refreshLoopArmed = false

    companion object {
        private const val CHANNEL_ID = "iqos_background_channel"
        private const val NOTIFICATION_ID = 2001

        /** How often to re-read the device while connected, so the daily baseline never goes
         * stale for long even if the user doesn't take a puff for a while. */
        private const val REFRESH_INTERVAL_MS = 15 * 60 * 1000L

        /** How long to wait after an unexpected drop before asking for another patient
         * (autoConnect) reconnect - deliberately not aggressive, since the whole point of
         * autoConnect is that the OS already watches for the device in the background. */
        private const val RECONNECT_REQUEUE_DELAY_MS = 45 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, IqosBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IqosBackgroundService::class.java))
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences("iqos_app_settings", Context.MODE_PRIVATE)
                .getBoolean("background_sync_enabled", false)
    }

    private val serviceListener = object : IqosConnectionManager.Listener {
        override fun onStateChanged(state: DeviceState, transportType: IqosTransport.Type) {
            updateNotification(state)
            when (state) {
                DeviceState.READY -> {
                    AppLogger.i("BACKGROUND", "Link ready - refreshing full snapshot")
                    connectionManager.refreshFullSnapshot()
                    armRefreshLoop()
                }
                DeviceState.DISCONNECTED -> {
                    refreshLoopArmed = false
                    if (isEnabled(this@IqosBackgroundService)) {
                        val address = deviceRegistry.getActiveAddress()
                        if (address != null) {
                            handler.postDelayed({
                                if (isEnabled(this@IqosBackgroundService) && !connectionManager.isConnected()) {
                                    connectionManager.connectToDeviceInBackground(address)
                                }
                            }, RECONNECT_REQUEUE_DELAY_MS)
                        }
                    }
                }
                else -> {}
            }
        }

        override fun onPuffCountReceived(totalPuffs: Int) {
            // Same call the foreground UI makes; NotificationHelper's usage alert already fires
            // from inside recordReading(), so a "new tera" notification just works here too.
            usageTracker.recordReading(totalPuffs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        connectionManager = IqosConnectionManager.getInstance(applicationContext)
        usageTracker = UsageTracker(applicationContext)
        deviceRegistry = DeviceRegistry(applicationContext)
        createNotificationChannel()
        connectionManager.addListener(serviceListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(statusText(connectionManager.snapshot.state)))

        if (!isEnabled(this)) {
            // Setting was turned off between the intent being sent and the service starting.
            stopSelf()
            return START_NOT_STICKY
        }

        if (connectionManager.isConnected()) {
            connectionManager.refreshFullSnapshot()
            armRefreshLoop()
        } else {
            val address = deviceRegistry.getActiveAddress()
            if (address != null) {
                connectionManager.connectToDeviceInBackground(address)
            } else {
                AppLogger.w("BACKGROUND", "No saved device to reconnect to - nothing to do yet")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        connectionManager.removeListener(serviceListener)
        handler.removeCallbacksAndMessages(null)
    }

    private fun armRefreshLoop() {
        if (refreshLoopArmed) return
        refreshLoopArmed = true
        scheduleNextRefresh()
    }

    private fun scheduleNextRefresh() {
        handler.postDelayed({
            if (connectionManager.isConnected() && isEnabled(this)) {
                AppLogger.i("BACKGROUND", "Periodic background refresh")
                connectionManager.refreshFullSnapshot()
                scheduleNextRefresh()
            } else {
                refreshLoopArmed = false
            }
        }, REFRESH_INTERVAL_MS)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "همگام‌سازی پس‌زمینه IQOS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "اتصال و ثبت خودکار مصرف در پس‌زمینه، حتی وقتی اپ بسته است"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun statusText(state: DeviceState): String = when (state) {
        DeviceState.READY -> "متصل - داده‌ها به‌روز است"
        DeviceState.CONNECTED, DeviceState.DISCOVERING_SERVICES -> "در حال برقراری اتصال…"
        DeviceState.CONNECTING, DeviceState.SCANNING -> "در انتظار ورود دستگاه به محدوده…"
        DeviceState.DISCONNECTED -> "قطع - به محض ورود دستگاه به محدوده وصل می‌شود"
        else -> "در حال بررسی وضعیت دستگاه"
    }

    private fun updateNotification(state: DeviceState) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(statusText(state)))
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("همگام‌سازی پس‌زمینه IQOS فعال است")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
