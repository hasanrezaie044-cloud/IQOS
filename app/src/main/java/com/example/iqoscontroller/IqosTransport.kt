package com.example.iqoscontroller

interface IqosTransport {

    enum class Type(val displayName: String) {
        NONE("بدون اتصال"),
        BLE("بلوتوث (BLE)"),
        USB("کابل (USB)")
    }

    interface Listener {
        fun onStateChanged(state: DeviceState, transportType: Type)
        fun onPuffCountReceived(totalPuffs: Int)
        fun onBatteryReceived(batteryPercent: Int, batteryVoltage: Float?)
        fun onDaysUsedReceived(daysUsed: Int)
        fun onError(message: String)
        fun onDiagnosticInfo(key: String, value: String)
        fun onRawPacketReceived(tag: String, hex: String)

        /** A single frame was decoded into a known device response. */
        fun onFrameDecoded(response: IqosResponse) {}

        /** The live device state changed (any field). */
        fun onSnapshotUpdated(snapshot: DeviceSnapshot) {}

        /** A command finished - confirmed, rejected, timed out or never sent. */
        fun onCommandResult(result: CommandResult) {}

        /** The full GATT service/characteristic tree, refreshed after discovery. */
        fun onGattTreeUpdated(lines: List<String>) {}
    }

    val transportType: Type
    val isConnected: Boolean

    /** Everything the device has actually reported over this transport. */
    val snapshot: DeviceSnapshot

    fun connect()
    fun disconnect()
    fun refreshDiagnosis()
    fun sendCommand(command: IqosProtocol.CommandOpcode, value: Byte)

    /**
     * Writes a sequence of raw SCP packets in order (used for multi-step commands like
     * Brightness or the vibration burst, where the real device firmware expects more than
     * one packet, unlike the simple single-packet commands used elsewhere).
     */
    fun sendRawSequence(packets: List<ByteArray>, tag: String)

    /** Reads every documented value from the device, not just the four diagnosis frames. */
    fun refreshFullSnapshot() { refreshDiagnosis() }

    /** Sends a command and verifies it by reading the value back from the device. */
    fun submit(write: VerifiedWrite) { sendRawSequence(write.packets, write.tag) }

    /** Requests an updated link signal strength, where the transport supports it. */
    fun requestSignalStrength() {}

    /** Enumerates and reads everything the device exposes, for the diagnostics screen. */
    fun exploreDevice() {}
}
