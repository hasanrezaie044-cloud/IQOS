package com.example.iqoscontroller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID

@SuppressLint("MissingPermission")
class BleIqosTransport(
    private val context: Context,
    private val listener: IqosTransport.Listener
) : IqosTransport {

    override val transportType: IqosTransport.Type = IqosTransport.Type.BLE
    override var isConnected: Boolean = false
        private set

    override val snapshot = DeviceSnapshot()

    private val unknownFrameHistory by lazy { UnknownFrameHistory(context) }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false

    /**
     * Resolved once per connection by searching EVERY discovered service for the SCP
     * characteristic, instead of assuming it lives in a hard-coded service.
     */
    private var scpCharacteristic: BluetoothGattCharacteristic? = null
    private var batteryCharacteristic: BluetoothGattCharacteristic? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable { stopScan(timedOut = true) }

    private val prefs = context.getSharedPreferences("iqos_app_settings", Context.MODE_PRIVATE)

    /** Set of write operations waiting for a read-back confirmation from the device. */
    private class PendingWrite(val write: VerifiedWrite, val attempt: Int)

    private var pendingWrite: PendingWrite? = null
    private val verifyTimeoutRunnable = Runnable { resolvePendingWrite(null) }

    private var reconnectAttempts = 0
    private var userRequestedDisconnect = false

    /** Automatically re-establish the link if the device drops out unexpectedly. */
    var autoReconnectEnabled: Boolean = true

    private val rssiPollRunnable = object : Runnable {
        override fun run() {
            if (isConnected) {
                bluetoothGatt?.readRemoteRssi()
                mainHandler.postDelayed(this, RSSI_POLL_INTERVAL_MS)
            }
        }
    }

    private val gattQueue = GattQueue(onOperationTimeout = {
        AppLogger.w("BLE", "GATT operation timeout, processing next in queue")
    })

    companion object {
        private const val SCAN_TIMEOUT_MS = 15000L
        private const val VERIFY_TIMEOUT_MS = 4500L
        private const val RSSI_POLL_INTERVAL_MS = 5000L
        private const val DIRECT_CONNECT_TIMEOUT_MS = 9000L
        private const val PREFERRED_MTU = 247
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val KEY_LAST_ADDRESS = "last_device_address"

        /** Standard GATT Device Information characteristics, all read-only. */
        private val DEVICE_INFO_CHARS = linkedMapOf(
            "00002a29-0000-1000-8000-00805f9b34fb" to "سازنده",
            "00002a24-0000-1000-8000-00805f9b34fb" to "شماره مدل",
            "00002a25-0000-1000-8000-00805f9b34fb" to "شماره سریال",
            "00002a27-0000-1000-8000-00805f9b34fb" to "نسخه سخت‌افزار",
            "00002a26-0000-1000-8000-00805f9b34fb" to "نسخه فریم‌ور",
            "00002a28-0000-1000-8000-00805f9b34fb" to "نسخه نرم‌افزار"
        )
    }

    // -------------------------------------------------------------------------------------------
    // Connection lifecycle
    // -------------------------------------------------------------------------------------------

    override fun connect() {
        userRequestedDisconnect = false
        reconnectAttempts = 0
        startConnectionAttempt()
    }

    private fun startConnectionAttempt() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("بلوتوث دستگاه خاموش است")
            publishState(DeviceState.ERROR)
            return
        }
        if (!hasPermissions()) {
            listener.onError("مجوزهای بلوتوث داده نشده است")
            publishState(DeviceState.ERROR)
            return
        }

        // Fast path: reconnect straight to the device we used last time. Scanning is slow and,
        // on many phones, an already-bonded IQOS stops advertising, so a scan can never find it.
        val lastAddress = prefs.getString(KEY_LAST_ADDRESS, null)
        val known = lastAddress?.let { address ->
            adapter.bondedDevices?.firstOrNull { it.address == address }
                ?: runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        }

        if (known != null) {
            AppLogger.i("BLE", "Direct reconnect to remembered device ${known.address}")
            listener.onDiagnosticInfo("Connect Mode", "اتصال مستقیم به دستگاه قبلی")
            connectToGatt(known)
            mainHandler.postDelayed({
                if (!isConnected && bluetoothGatt != null && scpCharacteristic == null) {
                    AppLogger.w("BLE", "Direct reconnect did not become ready, falling back to scan")
                    closeGatt()
                    startScan()
                }
            }, DIRECT_CONNECT_TIMEOUT_MS)
            return
        }

        startScan()
    }

    /**
     * Connects directly to a specific remembered address, bypassing scan entirely. Used by the
     * multi-device switcher so tapping a saved device tries that exact device instead of whatever
     * the generic scan happens to find first.
     */
    fun connectToAddress(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("بلوتوث دستگاه خاموش است")
            publishState(DeviceState.ERROR)
            return
        }
        if (!hasPermissions()) {
            listener.onError("مجوزهای بلوتوث داده نشده است")
            publishState(DeviceState.ERROR)
            return
        }

        userRequestedDisconnect = false
        reconnectAttempts = 0
        stopScan(timedOut = false)
        cancelPendingVerification()
        mainHandler.removeCallbacks(rssiPollRunnable)
        gattQueue.clear()
        closeGatt()

        val device = adapter.bondedDevices?.firstOrNull { it.address == address }
            ?: runCatching { adapter.getRemoteDevice(address) }.getOrNull()

        if (device != null) {
            AppLogger.i("BLE", "Direct connect to selected device ${device.address}")
            listener.onDiagnosticInfo("Connect Mode", "اتصال مستقیم به دستگاه انتخاب‌شده")
            connectToGatt(device)
        } else {
            listener.onError("دستگاه یافت نشد - ممکن است در محدوده نباشد")
            publishState(DeviceState.ERROR)
        }
    }

    /**
     * Background/companion mode: queues a connection with the Android Bluetooth stack itself
     * (`autoConnect = true`) instead of trying to connect right now. The OS will silently
     * complete the connection whenever the device is actually reachable again (comes back into
     * range, starts advertising, finishes charging, etc.), without this app needing to run its
     * own polling loop or repeated scans - which is both the officially recommended pattern for
     * "reconnect whenever the companion device shows up" and far lighter on battery than an
     * app-level scan loop. Used only by [IqosBackgroundService]; the interactive "Connect" button
     * keeps using [connectToAddress]/[connect] (autoConnect=false) exactly as before, since that
     * one is tuned for "connect right now" and changing it would change existing UI behaviour.
     */
    fun connectToAddressAutoReconnect(address: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("بلوتوث دستگاه خاموش است")
            publishState(DeviceState.ERROR)
            return
        }
        if (!hasPermissions()) {
            listener.onError("مجوزهای بلوتوث داده نشده است")
            publishState(DeviceState.ERROR)
            return
        }

        userRequestedDisconnect = false
        reconnectAttempts = 0
        cancelPendingVerification()
        mainHandler.removeCallbacks(rssiPollRunnable)
        gattQueue.clear()
        closeGatt()

        val device = adapter.bondedDevices?.firstOrNull { it.address == address }
            ?: runCatching { adapter.getRemoteDevice(address) }.getOrNull()

        if (device != null) {
            AppLogger.i("BLE", "Queuing persistent background connect to ${device.address}")
            listener.onDiagnosticInfo("Connect Mode", "اتصال پس‌زمینه (منتظر ورود دستگاه به محدوده)")
            connectToGatt(device, autoConnect = true)
        } else {
            listener.onError("دستگاه یافت نشد - ممکن است در محدوده نباشد")
            publishState(DeviceState.ERROR)
        }
    }

    override fun disconnect() {
        userRequestedDisconnect = true
        stopScan(timedOut = false)
        cancelPendingVerification()
        mainHandler.removeCallbacks(rssiPollRunnable)
        gattQueue.clear()
        closeGatt()
        snapshot.state = DeviceState.DISCONNECTED
        snapshot.transport = transportType
        snapshot.resetLinkState()
        listener.onStateChanged(DeviceState.DISCONNECTED, transportType)
        listener.onSnapshotUpdated(snapshot)
    }

    private fun closeGatt() {
        val gatt = bluetoothGatt
        bluetoothGatt = null
        scpCharacteristic = null
        batteryCharacteristic = null
        isConnected = false

        if (gatt != null) {
            try {
                AppLogger.i("BLE", "Disconnecting GATT")
                gatt.disconnect()
                gatt.close()
            } catch (e: Exception) {
                AppLogger.e("BLE", "Error closing GATT", e)
            }
        }
    }

    fun hasPermissions(): Boolean {
        // API 31+: BLUETOOTH_SCAN carries neverForLocation, so only Bluetooth permissions are
        // required - location must NOT be required here or scanning silently breaks.
        // API <=30: ACCESS_FINE_LOCATION is the only way to get BLE scan results at all.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onError("بلوتوث دستگاه خاموش است")
            publishState(DeviceState.ERROR)
            return
        }

        if (!hasPermissions()) {
            listener.onError("مجوزهای بلوتوث داده نشده است")
            publishState(DeviceState.ERROR)
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onError("اسکنر بلوتوث در دسترس نیست")
            return
        }

        stopScan(timedOut = false)
        cancelPendingVerification()
        mainHandler.removeCallbacks(rssiPollRunnable)
        gattQueue.clear()
        closeGatt()

        isScanning = true
        publishState(DeviceState.SCANNING)
        AppLogger.i("BLE", "SCAN_STARTED")

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setLegacy(false)
                }
            }
            .build()

        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)

        try {
            scanner.startScan(emptyList<ScanFilter>(), scanSettings, scanCallback)
        } catch (e: Exception) {
            isScanning = false
            AppLogger.e("BLE", "Failed to start BLE scan", e)
            listener.onError("خطا در شروع اسکن: ${e.localizedMessage}")
            publishState(DeviceState.ERROR)
        }
    }

    private fun stopScan(timedOut: Boolean = false) {
        if (!isScanning) return
        isScanning = false
        mainHandler.removeCallbacks(scanTimeoutRunnable)

        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            AppLogger.i("BLE", "SCAN_STOPPED")
        } catch (_: Exception) {}

        if (timedOut && bluetoothGatt == null) {
            // Last resort before giving up: a bonded IQOS often does not advertise at all.
            val bonded = bluetoothAdapter?.bondedDevices?.firstOrNull { device ->
                IqosProtocol.DEVICE_NAME_TOKENS.any { token ->
                    (device.name ?: "").contains(token, ignoreCase = true)
                }
            }
            if (bonded != null) {
                AppLogger.i("BLE", "Scan found nothing, trying bonded device ${bonded.address}")
                listener.onDiagnosticInfo("Connect Mode", "اتصال به دستگاه جفت‌شده (بدون اسکن)")
                connectToGatt(bonded)
                return
            }

            publishState(DeviceState.DISCONNECTED)
            listener.onError("دستگاه IQOS در اسکن یافت نشد")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result?.let { handleScanResult(it) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            results?.forEach { handleScanResult(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            AppLogger.e("BLE", "Scan failed: $errorCode")
            mainHandler.post {
                publishState(DeviceState.ERROR)
                listener.onError("خطای اسکن بلوتوث: $errorCode")
            }
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val rawName = device.name ?: result.scanRecord?.deviceName ?: ""
        val address = device.address ?: ""
        val serviceUuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()

        val matchesUuid = serviceUuids.any { it == IqosProtocol.RAB_SERVICE_UUID || it == IqosProtocol.RRP_SERVICE_UUID }
        val matchesName = IqosProtocol.DEVICE_NAME_TOKENS.any { token ->
            rawName.contains(token, ignoreCase = true)
        }

        if (matchesUuid || matchesName) {
            AppLogger.i("BLE", "MATCHED: '$rawName' [$address] rssi=${result.rssi}")
            snapshot.rssi = result.rssi
            stopScan(timedOut = false)
            connectToGatt(device)
        }
    }

    private fun connectToGatt(device: BluetoothDevice, autoConnect: Boolean = false) {
        AppLogger.i("BLE", "CONNECTING to ${device.address} (autoConnect=$autoConnect)")

        val name = device.name
        snapshot.deviceName = name
        snapshot.address = device.address
        snapshot.bondState = when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> "جفت‌شده"
            BluetoothDevice.BOND_BONDING -> "در حال جفت‌سازی"
            else -> "جفت‌نشده"
        }
        if (snapshot.model == IqosDeviceModel.UNKNOWN) {
            snapshot.model = IqosDeviceModel.fromName(name)
        }

        listener.onDiagnosticInfo("Device Name", name ?: "Unknown")
        listener.onDiagnosticInfo("Address", device.address)
        listener.onDiagnosticInfo("Detected Model", snapshot.model.displayName)
        publishState(DeviceState.CONNECTING)

        prefs.edit().putString(KEY_LAST_ADDRESS, device.address).apply()

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, autoConnect, gattCallback)
        }
    }

    private fun publishState(state: DeviceState) {
        snapshot.state = state
        snapshot.transport = transportType
        listener.onStateChanged(state, transportType)
        listener.onSnapshotUpdated(snapshot)
    }

    // -------------------------------------------------------------------------------------------
    // GATT callbacks
    // -------------------------------------------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            listener.onDiagnosticInfo("GATT Status", "$status")

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                AppLogger.i("BLE", "CONNECTED to GATT. Discovering services...")
                listener.onDiagnosticInfo("GATT State", "CONNECTED")
                reconnectAttempts = 0
                snapshot.connectionStartedAt = System.currentTimeMillis()
                mainHandler.post {
                    publishState(DeviceState.CONNECTED)
                    publishState(DeviceState.DISCOVERING_SERVICES)
                }
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                AppLogger.w("BLE", "DISCONNECTED from GATT (status=$status)")
                listener.onDiagnosticInfo("GATT State", "DISCONNECTED")
                handleUnexpectedDisconnect(status)
            } else if (status != BluetoothGatt.GATT_SUCCESS) {
                handleUnexpectedDisconnect(status)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) {
                AppLogger.e("BLE", "Service discovery failed (status=$status)")
                handleUnexpectedDisconnect(status)
                return
            }

            publishGattTree(gatt)

            // THE important fix: find the characteristic wherever it actually lives. The old code
            // looked it up inside a hard-coded service and gave up (silently) when the device
            // exposed a different one - which is exactly why commands "did nothing".
            scpCharacteristic = findCharacteristic(gatt, IqosProtocol.SCP_CONTROL_POINT_UUID)
            batteryCharacteristic = findCharacteristic(gatt, IqosProtocol.DEVICE_BATTERY_CHAR_UUID)
                ?: findCharacteristic(gatt, IqosProtocol.STANDARD_BATTERY_LEVEL_CHAR_UUID)

            val scp = scpCharacteristic
            if (scp == null) {
                AppLogger.e("BLE", "SCP control characteristic not present on this device")
                mainHandler.post {
                    publishState(DeviceState.ERROR)
                    listener.onError("مشخصه کنترل SCP روی این دستگاه پیدا نشد")
                }
                return
            }

            AppLogger.i("BLE", "SCP characteristic resolved in service ${scp.service?.uuid}")
            listener.onDiagnosticInfo("SCP Service", scp.service?.uuid?.toString() ?: "?")
            listener.onDiagnosticInfo("SCP Properties", describeProperties(scp.properties))

            isConnected = true
            mainHandler.post { publishState(DeviceState.READY) }

            requestMtu(gatt)
            enableAllNotifications(gatt)
            readDeviceInformation(gatt)
            refreshFullSnapshot()

            mainHandler.removeCallbacks(rssiPollRunnable)
            mainHandler.post(rssiPollRunnable)
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            gattQueue.onOperationCompleted()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                snapshot.mtu = mtu
                AppLogger.i("BLE", "MTU negotiated: $mtu")
                listener.onDiagnosticInfo("MTU", "$mtu")
                listener.onSnapshotUpdated(snapshot)
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                snapshot.rssi = rssi
                listener.onDiagnosticInfo("RSSI", "$rssi dBm")
                listener.onSnapshotUpdated(snapshot)
            }
        }

        // Legacy overload: the framework calls the value-carrying one on API 33+ instead.
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            gattQueue.onOperationCompleted()
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic != null) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: ByteArray(0)
                processIncomingBytes(characteristic, data, "READ")
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            gattQueue.onOperationCompleted()
            if (status == BluetoothGatt.GATT_SUCCESS) {
                processIncomingBytes(characteristic, value, "READ")
            }
        }

        // Legacy overload: the framework calls the value-carrying one on API 33+ instead.
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic != null) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: ByteArray(0)
                processIncomingBytes(characteristic, data, "NOTIFY")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processIncomingBytes(characteristic, value, "NOTIFY")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?,
            status: Int
        ) {
            gattQueue.onOperationCompleted()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.w("GATT", "Write FAILED status=$status on ${characteristic?.uuid}")
                listener.onDiagnosticInfo("Last Write Status", "خطا ($status)")
            } else {
                AppLogger.d("GATT", "Write completed on ${characteristic?.uuid}")
                listener.onDiagnosticInfo("Last Write Status", "موفق")
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            gattQueue.onOperationCompleted()
            AppLogger.d("GATT", "Descriptor write completed status=$status")
        }
    }

    private fun handleUnexpectedDisconnect(status: Int) {
        val wasConnected = isConnected
        cancelPendingVerification()
        mainHandler.removeCallbacks(rssiPollRunnable)
        gattQueue.clear()
        closeGatt()

        snapshot.state = DeviceState.DISCONNECTED
        snapshot.resetLinkState()
        listener.onStateChanged(DeviceState.DISCONNECTED, transportType)
        listener.onSnapshotUpdated(snapshot)

        if (userRequestedDisconnect || !autoReconnectEnabled) return
        if (!wasConnected && status == BluetoothGatt.GATT_SUCCESS) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            AppLogger.w("BLE", "Giving up after $reconnectAttempts reconnect attempts")
            return
        }

        reconnectAttempts++
        val delay = 1500L * reconnectAttempts
        AppLogger.i("BLE", "Auto-reconnect attempt $reconnectAttempts in ${delay}ms")
        listener.onDiagnosticInfo("Auto Reconnect", "تلاش $reconnectAttempts از $MAX_RECONNECT_ATTEMPTS")
        mainHandler.postDelayed({ startConnectionAttempt() }, delay)
    }

    // -------------------------------------------------------------------------------------------
    // Discovery helpers
    // -------------------------------------------------------------------------------------------

    private fun findCharacteristic(gatt: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? {
        gatt.services?.forEach { service ->
            service.characteristics?.forEach { characteristic ->
                if (characteristic.uuid == uuid) return characteristic
            }
        }
        return null
    }

    private fun describeProperties(properties: Int): String {
        val parts = mutableListOf<String>()
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) parts += "READ"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) parts += "WRITE"
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) parts += "WRITE_NR"
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) parts += "NOTIFY"
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) parts += "INDICATE"
        return if (parts.isEmpty()) "-" else parts.joinToString("/")
    }

    private fun publishGattTree(gatt: BluetoothGatt) {
        val lines = mutableListOf<String>()
        gatt.services?.forEach { service ->
            lines += "SERVICE ${service.uuid}"
            service.characteristics?.forEach { characteristic ->
                lines += "   CHAR ${characteristic.uuid}  [${describeProperties(characteristic.properties)}]"
            }
        }
        AppLogger.i("BLE", "Discovered ${gatt.services?.size ?: 0} services")
        listener.onDiagnosticInfo("Services", "${gatt.services?.size ?: 0}")
        listener.onGattTreeUpdated(lines)
    }

    private fun requestMtu(gatt: BluetoothGatt) {
        // Some of the real command sequences are 20 bytes, which does not fit the default MTU
        // payload of 20 bytes together with any header, so ask for a larger one up front.
        gattQueue.enqueue {
            if (!gatt.requestMtu(PREFERRED_MTU)) {
                gattQueue.onOperationCompleted()
            }
        }
    }

    private fun enableAllNotifications(gatt: BluetoothGatt) {
        val targets = mutableListOf<BluetoothGattCharacteristic>()
        scpCharacteristic?.let { targets += it }
        batteryCharacteristic?.let { if (!targets.contains(it)) targets += it }

        // Subscribe to anything else that can notify: harmless, and it surfaces live data the
        // hard-coded list never asked for.
        gatt.services?.forEach { service ->
            service.characteristics?.forEach { characteristic ->
                val canNotify = characteristic.properties and
                    (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                if (canNotify && !targets.contains(characteristic)) targets += characteristic
            }
        }

        targets.forEach { characteristic ->
            gattQueue.enqueue {
                AppLogger.i("BLE", "Enabling notifications on ${characteristic.uuid}")
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(IqosProtocol.CCCD_DESCRIPTOR_UUID)
                if (descriptor == null) {
                    gattQueue.onOperationCompleted()
                    return@enqueue
                }
                val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                }
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        descriptor.value = value
                        gatt.writeDescriptor(descriptor)
                    }
                }
                if (!ok) gattQueue.onOperationCompleted()
            }
        }
    }

    private fun readDeviceInformation(gatt: BluetoothGatt) {
        DEVICE_INFO_CHARS.keys.forEach { uuidString ->
            val characteristic = findCharacteristic(gatt, UUID.fromString(uuidString)) ?: return@forEach
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return@forEach
            gattQueue.enqueue {
                if (!gatt.readCharacteristic(characteristic)) gattQueue.onOperationCompleted()
            }
        }
    }

    /** Reads every readable characteristic on the device, for the diagnostics screen. */
    override fun exploreDevice() {
        val gatt = bluetoothGatt ?: return
        publishGattTree(gatt)
        gatt.services?.forEach { service ->
            service.characteristics?.forEach { characteristic ->
                if (characteristic.uuid == IqosProtocol.SCP_CONTROL_POINT_UUID) return@forEach
                if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return@forEach
                gattQueue.enqueue {
                    if (!gatt.readCharacteristic(characteristic)) gattQueue.onOperationCompleted()
                }
            }
        }
        AppLogger.i("BLE", "Full device exploration queued")
    }

    override fun requestSignalStrength() {
        bluetoothGatt?.readRemoteRssi()
    }

    // -------------------------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------------------------

    private fun writeScp(packet: ByteArray, tag: String) {
        val gatt = bluetoothGatt ?: return
        val characteristic = scpCharacteristic ?: return

        gattQueue.enqueue {
            AppLogger.i("PROTOCOL", "TX $tag: ${IqosProtocol.toHexString(packet)}")
            listener.onRawPacketReceived("TX", IqosProtocol.toHexString(packet))
            FrameForensics.record("TX", characteristic.uuid.toString(), packet)
            FrameForensics.noteCommandSent(tag)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    packet,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    characteristic.value = packet
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    gatt.writeCharacteristic(characteristic)
                }
            }
            if (!ok) {
                AppLogger.e("PROTOCOL", "Write could not be started for $tag")
                gattQueue.onOperationCompleted()
            }
        }
    }

    private fun ensureReady(labelFa: String, tag: String): Boolean {
        if (isConnected && scpCharacteristic != null && bluetoothGatt != null) return true
        listener.onCommandResult(
            CommandResult(tag, labelFa, CommandResult.Status.NOT_CONNECTED, "دستگاه متصل نیست")
        )
        listener.onError("دستگاه متصل نیست")
        return false
    }

    override fun refreshDiagnosis() {
        if (bluetoothGatt == null || scpCharacteristic == null) return
        AppLogger.i("PROTOCOL", "Sending Diagnosis sequence: Telemetry -> Timestamp -> Battery Voltage")
        IqosProtocol.ALL_DIAGNOSIS_COMMANDS.forEach { writeScp(it, "Diagnosis") }
        readBatteryCharacteristic()
    }

    override fun refreshFullSnapshot() {
        val gatt = bluetoothGatt ?: return
        if (scpCharacteristic == null) return
        AppLogger.i("PROTOCOL", "Reading full device snapshot (${IqosCommands.FULL_SNAPSHOT_SEQUENCE.size} frames)")
        IqosCommands.FULL_SNAPSHOT_SEQUENCE.forEach { (label, packet) -> writeScp(packet, label) }
        readBatteryCharacteristic()
        gatt.readRemoteRssi()
    }

    private fun readBatteryCharacteristic() {
        val gatt = bluetoothGatt ?: return
        val characteristic = batteryCharacteristic ?: return
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return
        gattQueue.enqueue {
            if (!gatt.readCharacteristic(characteristic)) gattQueue.onOperationCompleted()
        }
    }

    override fun sendCommand(command: IqosProtocol.CommandOpcode, value: Byte) {
        if (!ensureReady(command.descriptionFa, command.name)) return
        val packet = IqosProtocol.buildPacket(command, value)
        AppLogger.i("PROTOCOL", "Writing Command ${command.name}")
        writeScp(packet, command.name)
    }

    override fun sendRawSequence(packets: List<ByteArray>, tag: String) {
        if (!ensureReady(tag, tag)) return
        AppLogger.i("PROTOCOL", "Sending $tag sequence (${packets.size} packets)")
        packets.forEach { writeScp(it, tag) }
    }

    /** Sends a write and then proves (or disproves) it by reading the value back. */
    override fun submit(write: VerifiedWrite) {
        if (!ensureReady(write.labelFa, write.tag)) return

        write.packets.forEach { writeScp(it, write.tag) }

        if (write.matcher == null || write.verifyPackets.isEmpty()) {
            listener.onCommandResult(
                CommandResult(
                    write.tag, write.labelFa, CommandResult.Status.SENT_UNVERIFIABLE,
                    "فرمان ارسال شد؛ این قابلیت فرمان خواندن وضعیت ندارد"
                )
            )
            return
        }

        cancelPendingVerification()
        pendingWrite = PendingWrite(write, 1)
        write.verifyPackets.forEach { writeScp(it, "${write.tag}/verify") }
        mainHandler.postDelayed(verifyTimeoutRunnable, VERIFY_TIMEOUT_MS)
    }

    private fun cancelPendingVerification() {
        mainHandler.removeCallbacks(verifyTimeoutRunnable)
        pendingWrite = null
    }

    /** [matched] null means the read-back never arrived. */
    private fun resolvePendingWrite(matched: Boolean?) {
        val pending = pendingWrite ?: return
        mainHandler.removeCallbacks(verifyTimeoutRunnable)
        pendingWrite = null

        val write = pending.write

        if (matched == true) {
            listener.onCommandResult(
                CommandResult(write.tag, write.labelFa, CommandResult.Status.CONFIRMED, "دستگاه مقدار جدید را تأیید کرد")
            )
            return
        }

        val retry = write.retryPackets
        if (matched == false && retry != null && pending.attempt == 1) {
            AppLogger.w("PROTOCOL", "${write.tag} rejected, retrying with corrected frame")
            listener.onDiagnosticInfo("${write.tag} Retry", write.retryNoteFa ?: "تلاش دوم")
            pendingWrite = PendingWrite(write, 2)
            retry.forEach { writeScp(it, "${write.tag}/retry") }
            write.verifyPackets.forEach { writeScp(it, "${write.tag}/verify") }
            mainHandler.postDelayed(verifyTimeoutRunnable, VERIFY_TIMEOUT_MS)
            return
        }

        val status = if (matched == false) CommandResult.Status.REJECTED else CommandResult.Status.TIMEOUT
        val detail = if (matched == false) {
            "دستگاه پاسخ داد ولی مقدار عوض نشد" + (if (pending.attempt > 1) " (حتی با چک‌سام اصلاح‌شده)" else "")
        } else {
            "پاسخ خواندن وضعیت در ${VERIFY_TIMEOUT_MS / 1000} ثانیه نرسید"
        }
        listener.onCommandResult(CommandResult(write.tag, write.labelFa, status, detail))
    }

    // -------------------------------------------------------------------------------------------
    // Reading
    // -------------------------------------------------------------------------------------------

    private fun processIncomingBytes(
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
        source: String
    ) {
        val hex = IqosProtocol.toHexString(data)
        AppLogger.i("PROTOCOL", "BLE RX $source [${characteristic.uuid}]: $hex")
        listener.onRawPacketReceived("BLE", hex)
        FrameForensics.record("RX", characteristic.uuid.toString(), data)

        val uuidString = characteristic.uuid.toString().lowercase()

        // Standard Device Information strings
        val infoLabel = DEVICE_INFO_CHARS[uuidString]
        if (infoLabel != null) {
            val text = String(data, Charsets.UTF_8).trim { it.code <= 0x20 }
            when (uuidString) {
                "00002a29-0000-1000-8000-00805f9b34fb" -> snapshot.manufacturer = text
                "00002a24-0000-1000-8000-00805f9b34fb" -> {
                    snapshot.modelNumber = text
                    if (snapshot.model == IqosDeviceModel.UNKNOWN) {
                        snapshot.model = IqosDeviceModel.fromName(text)
                    }
                }
                "00002a25-0000-1000-8000-00805f9b34fb" -> snapshot.serialNumber = text
                "00002a27-0000-1000-8000-00805f9b34fb" -> snapshot.hardwareRevision = text
                "00002a26-0000-1000-8000-00805f9b34fb" -> snapshot.firmwareRevision = text
                "00002a28-0000-1000-8000-00805f9b34fb" -> snapshot.softwareRevision = text
            }
            FrameForensics.classify(characteristic.uuid.toString(), hex, "DEVICE_INFO:$infoLabel")
            listener.onDiagnosticInfo(infoLabel, text)
            mainHandler.post { listener.onSnapshotUpdated(snapshot) }
            return
        }

        val isBatteryChar = characteristic.uuid == IqosProtocol.DEVICE_BATTERY_CHAR_UUID ||
            characteristic.uuid == IqosProtocol.STANDARD_BATTERY_LEVEL_CHAR_UUID

        val response = if (isBatteryChar) {
            IqosResponse.decodeBatteryCharacteristic(data)
        } else {
            IqosResponse.decode(data)
        }

        // Forensics classification is derived purely from which decoder path actually executed -
        // never from a guess about what the bytes "probably" mean. A characteristic that isn't
        // the SCP control point and isn't the battery characteristic still gets forced through
        // the SCP decoder below (existing behaviour, unchanged) purely so nothing is silently
        // dropped; the classification records that fact so it's visible during analysis instead
        // of being indistinguishable from a genuine SCP protocol violation.
        val decoderPath = when {
            isBatteryChar -> "BATTERY_DECODER"
            characteristic.uuid == IqosProtocol.SCP_CONTROL_POINT_UUID -> "SCP_DECODER"
            else -> "SCP_DECODER_MISROUTED"
        }
        FrameForensics.classify(characteristic.uuid.toString(), hex, "${response::class.simpleName}:$decoderPath")
        if (response is IqosResponse.Unknown && characteristic.uuid == IqosProtocol.SCP_CONTROL_POINT_UUID) {
            // Group by the first 4 bytes (frame marker + kind + header + register) so an evolving
            // family of undocumented SCP replies is tracked as one entry, not one per unique
            // payload - this is exactly what revealed the C0/90/0A telemetry-shaped family.
            val firstBytes = data.take(4).joinToString(" ") { String.format("%02X", it) }
            unknownFrameHistory.record("SCP:$firstBytes", hex, "SCP_UNKNOWN_HEADER")
        }

        val recognised = snapshot.applyResponse(response)

        if (!recognised && !isBatteryChar && characteristic.uuid != IqosProtocol.SCP_CONTROL_POINT_UUID) {
            // Unknown data from a characteristic we do not model: keep it visible instead of
            // throwing it away, so anything extra the hardware exposes can still be inspected.
            val ascii = data.map { b ->
                val c = b.toInt() and 0xFF
                if (c in 0x20..0x7E) c.toChar() else '.'
            }.joinToString("")
            snapshot.extraGattValues[characteristic.uuid.toString()] = "$hex   |$ascii|"
            unknownFrameHistory.record("CHAR:${characteristic.uuid}", hex, "UNMODELED_CHARACTERISTIC")
        }

        mainHandler.post {
            // Keep the original callbacks firing exactly as before so nothing that depended on
            // them changes behaviour.
            when (response) {
                is IqosResponse.BatteryLevel ->
                    listener.onBatteryReceived(response.percent, snapshot.batteryVolts())

                is IqosResponse.BatteryVoltage -> {
                    val percent = snapshot.displayPercent()
                    if (percent != null) listener.onBatteryReceived(percent, response.volts)
                }

                is IqosResponse.Telemetry -> {
                    response.puffCount?.let { listener.onPuffCountReceived(it) }
                    response.daysUsed?.let { listener.onDaysUsedReceived(it) }
                }

                is IqosResponse.Timestamp -> listener.onDaysUsedReceived(response.daysUsed)

                else -> {}
            }

            listener.onFrameDecoded(response)
            listener.onSnapshotUpdated(snapshot)

            // Resolve a pending verification, if this frame answers it.
            pendingWrite?.let { pending ->
                val matched = pending.write.matcher?.invoke(response)
                if (matched != null) resolvePendingWrite(matched)
            }
        }
    }
}
