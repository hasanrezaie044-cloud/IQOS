package com.example.iqoscontroller

import android.content.Context

class IqosConnectionManager private constructor(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        @Volatile private var instance: IqosConnectionManager? = null

        /** App-scoped singleton: there is only ever one real BLE/USB connection in the process,
         * shared between the foreground Activity and [IqosBackgroundService] so they never fight
         * over the same device or open two competing GATT connections. */
        fun getInstance(context: Context): IqosConnectionManager {
            return instance ?: synchronized(this) {
                instance ?: IqosConnectionManager(context).also { instance = it }
            }
        }
    }

    interface Listener {
        fun onStateChanged(state: DeviceState, transportType: IqosTransport.Type) {}
        fun onPuffCountReceived(totalPuffs: Int) {}
        fun onBatteryReceived(batteryPercent: Int, batteryVoltage: Float?) {}
        fun onDaysUsedReceived(daysUsed: Int) {}
        fun onError(message: String) {}
        fun onDiagnosticInfo(key: String, value: String) {}
        fun onRawPacketReceived(tag: String, hex: String) {}

        /** A frame was decoded into a known device response. */
        fun onFrameDecoded(response: IqosResponse) {}

        /** Live device state changed. */
        fun onSnapshotUpdated(snapshot: DeviceSnapshot) {}

        /** A command was confirmed, rejected, timed out, or never sent. */
        fun onCommandResult(result: CommandResult) {}

        /** The device's GATT service tree. */
        fun onGattTreeUpdated(lines: List<String>) {}
    }

    private val bleTransport: BleIqosTransport
    private val usbTransport: UsbIqosTransport

    private var activeTransport: IqosTransport? = null

    /** Every registered listener gets every callback - the UI (while open) and the background
     * service (always) can both be listening at the same time without stepping on each other. */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Latest full readout from whichever transport is live. */
    val snapshot: DeviceSnapshot
        get() = (activeTransport ?: bleTransport).snapshot

    val gattTree = mutableListOf<String>()

    private val internalListener = object : IqosTransport.Listener {
        override fun onStateChanged(state: DeviceState, transportType: IqosTransport.Type) {
            AppLogger.i("CONNECTION", "State -> $state via ${transportType.name}")
            listeners.forEach { it.onStateChanged(state, transportType) }

            if (state == DeviceState.DISCONNECTED && transportType == IqosTransport.Type.USB) {
                activeTransport = null
            }
        }

        override fun onPuffCountReceived(totalPuffs: Int) {
            listeners.forEach { it.onPuffCountReceived(totalPuffs) }
        }

        override fun onBatteryReceived(batteryPercent: Int, batteryVoltage: Float?) {
            listeners.forEach { it.onBatteryReceived(batteryPercent, batteryVoltage) }
        }

        override fun onDaysUsedReceived(daysUsed: Int) {
            listeners.forEach { it.onDaysUsedReceived(daysUsed) }
        }

        override fun onError(message: String) {
            listeners.forEach { it.onError(message) }
        }

        override fun onDiagnosticInfo(key: String, value: String) {
            listeners.forEach { it.onDiagnosticInfo(key, value) }
        }

        override fun onRawPacketReceived(tag: String, hex: String) {
            listeners.forEach { it.onRawPacketReceived(tag, hex) }
        }

        override fun onFrameDecoded(response: IqosResponse) {
            listeners.forEach { it.onFrameDecoded(response) }
        }

        override fun onSnapshotUpdated(snapshot: DeviceSnapshot) {
            listeners.forEach { it.onSnapshotUpdated(snapshot) }
        }

        override fun onCommandResult(result: CommandResult) {
            AppLogger.i("CONNECTION", "Command ${result.tag} -> ${result.status.name}: ${result.detailFa}")
            listeners.forEach { it.onCommandResult(result) }
        }

        override fun onGattTreeUpdated(lines: List<String>) {
            gattTree.clear()
            gattTree.addAll(lines)
            listeners.forEach { it.onGattTreeUpdated(lines) }
        }
    }

    init {
        bleTransport = BleIqosTransport(appContext, internalListener)
        usbTransport = UsbIqosTransport(appContext, internalListener)
        usbTransport.registerReceivers()
    }

    private fun transport(): IqosTransport =
        activeTransport ?: if (bleTransport.isConnected) bleTransport else usbTransport

    fun startConnection() {
        AppLogger.i("CONNECTION", "Initiating smart connection sequence")

        usbTransport.checkForConnectedDevice()
        if (usbTransport.isConnected) {
            AppLogger.i("CONNECTION", "Selected USB as primary active transport")
            activeTransport = usbTransport
            return
        }

        AppLogger.i("CONNECTION", "Starting BLE scan")
        activeTransport = bleTransport
        bleTransport.connect()
    }

    fun disconnect() {
        bleTransport.disconnect()
        usbTransport.disconnect()
        activeTransport = null
    }

    /** Multi-device support: connect directly to one of the user's previously-saved devices. */
    fun connectToDevice(address: String) {
        activeTransport = bleTransport
        bleTransport.connectToAddress(address)
    }

    /** Used only by [IqosBackgroundService]: queues a patient, OS-managed reconnect instead of
     * an immediate one, so the connection completes silently whenever the device is actually in
     * range again. See [BleIqosTransport.connectToAddressAutoReconnect]. */
    fun connectToDeviceInBackground(address: String) {
        activeTransport = bleTransport
        bleTransport.connectToAddressAutoReconnect(address)
    }

    fun refreshDiagnosis() {
        activeTransport?.refreshDiagnosis() ?: bleTransport.refreshDiagnosis()
    }

    /** Reads every documented value from the device, not just the four diagnosis frames. */
    fun refreshFullSnapshot() {
        transport().refreshFullSnapshot()
    }

    fun exploreDevice() {
        transport().exploreDevice()
    }

    fun requestSignalStrength() {
        transport().requestSignalStrength()
    }

    var autoReconnectEnabled: Boolean
        get() = bleTransport.autoReconnectEnabled
        set(value) {
            bleTransport.autoReconnectEnabled = value
        }

    fun sendCommand(command: IqosProtocol.CommandOpcode, value: Byte) {
        transport().sendCommand(command, value)
    }

    // -------------------------------------------------------------------------------------------
    // Verified feature commands
    // -------------------------------------------------------------------------------------------

    private fun submit(write: VerifiedWrite) {
        transport().submit(write)
    }

    fun setSmartGesture(enabled: Boolean) = submit(IqosOperations.smartGesture(enabled))

    fun setAutoStart(enabled: Boolean) = submit(IqosOperations.autoStart(enabled))

    fun setPauseMode(enabled: Boolean) = submit(IqosOperations.pauseMode(enabled))

    fun setFlexPuff(enabled: Boolean) = submit(IqosOperations.flexPuff(enabled))

    fun setFlexBattery(eco: Boolean) = submit(IqosOperations.flexBattery(eco))

    fun setDeviceLock(locked: Boolean) = submit(IqosOperations.deviceLock(locked))

    /** Sends the real verified multi-packet Brightness sequence (high or low). */
    fun setBrightness(high: Boolean) = submit(IqosOperations.brightness(high))

    /** Triggers (or stops) the real verified physical vibration burst ("Find My IQOS"). */
    fun setVibrationBurst(on: Boolean) = submit(IqosOperations.findMyDevice(on))

    /** Writes the four real vibration feedback triggers (plus charge-start on holder models). */
    fun setVibrationSettings(
        whenHeatingStart: Boolean,
        whenStartingToUse: Boolean,
        whenPuffEnd: Boolean,
        whenManuallyTerminated: Boolean,
        whenChargeStart: Boolean?
    ) = submit(
        IqosOperations.vibrationSettings(
            snapshot.model,
            whenHeatingStart,
            whenStartingToUse,
            whenPuffEnd,
            whenManuallyTerminated,
            whenChargeStart
        )
    )

    fun isConnected(): Boolean = bleTransport.isConnected || usbTransport.isConnected

    val activeTransportType: IqosTransport.Type
        get() = activeTransport?.transportType ?: IqosTransport.Type.NONE

    fun release() {
        disconnect()
        usbTransport.unregisterReceivers()
    }
}
