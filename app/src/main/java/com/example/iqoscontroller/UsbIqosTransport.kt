package com.example.iqoscontroller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class UsbIqosTransport(
    private val context: Context,
    private val listener: IqosTransport.Listener
) : IqosTransport {

    override val transportType: IqosTransport.Type = IqosTransport.Type.USB
    override var isConnected: Boolean = false
        private set

    override val snapshot = DeviceSnapshot()

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()

    private var activeDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inEndpoint: UsbEndpoint? = null
    private var outEndpoint: UsbEndpoint? = null

    private var isReceiverRegistered = false

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.iqoscontroller.USB_PERMISSION"
        private const val TIMEOUT_MS = 2500
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        AppLogger.i("USB", "PERMISSION_GRANTED = $granted for device: ${device?.deviceName}")

                        if (granted && device != null) {
                            openAndConfigureDevice(device)
                        } else {
                            listener.onError("مجوز دسترسی به دستگاه USB داده نشد")
                            listener.onStateChanged(DeviceState.ERROR, transportType)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    AppLogger.i("USB", "DEVICE_DETECTED: USB device attached via system event")
                    checkForConnectedDevice()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    AppLogger.i("USB", "USB device detached via system event")
                    disconnect()
                }
            }
        }
    }

    fun registerReceivers() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            isReceiverRegistered = true
        }
    }

    fun unregisterReceivers() {
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(usbReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }

    override fun connect() {
        checkForConnectedDevice()
    }

    fun checkForConnectedDevice() {
        val deviceList = usbManager.deviceList
        AppLogger.i("USB", "Scanning connected USB devices. Count=${deviceList.size}")

        if (deviceList.isEmpty()) {
            listener.onDiagnosticInfo("USB Status", "هیچ دستگاه USB متصل نیست")
            return
        }

        deviceList.values.forEach { device ->
            logDeviceDiagnostics(device)
        }

        val targetDevice = deviceList.values.firstOrNull { device ->
            device.deviceClass != 9 && (device.interfaceCount > 0)
        } ?: deviceList.values.firstOrNull()

        if (targetDevice != null) {
            AppLogger.i("USB", "Target device chosen: VID=${targetDevice.vendorId}, PID=${targetDevice.productId}")
            if (usbManager.hasPermission(targetDevice)) {
                openAndConfigureDevice(targetDevice)
            } else {
                requestUsbPermission(targetDevice)
            }
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        AppLogger.i("USB", "PERMISSION_REQUESTED for ${device.deviceName}")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun openAndConfigureDevice(device: UsbDevice) {
        AppLogger.i("USB", "DEVICE_OPENED: Opening USB connection")
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            AppLogger.e("USB", "Failed to open USB device connection!")
            listener.onError("خطا در باز کردن کانکشن USB")
            listener.onStateChanged(DeviceState.ERROR, transportType)
            return
        }

        activeDevice = device
        usbConnection = connection

        var foundInterface: UsbInterface? = null
        var foundInEp: UsbEndpoint? = null
        var foundOutEp: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null

            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                if (ep.direction == UsbConstants.USB_DIR_IN) {
                    epIn = ep
                } else if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    epOut = ep
                }
            }

            if (epIn != null || epOut != null) {
                foundInterface = iface
                foundInEp = epIn
                foundOutEp = epOut
                break
            }
        }

        if (foundInterface != null) {
            AppLogger.i("USB", "INTERFACE_FOUND: ID=${foundInterface.id}")
            if (connection.claimInterface(foundInterface, true)) {
                AppLogger.i("USB", "INTERFACE_CLAIMED")
                claimedInterface = foundInterface
                inEndpoint = foundInEp
                outEndpoint = foundOutEp
                isConnected = true

                AppLogger.i("USB", "READY: USB Transport ready for IO")

                snapshot.transport = transportType
                snapshot.state = DeviceState.READY
                snapshot.deviceName = device.productName ?: device.deviceName
                mainHandler.post {
                    listener.onDiagnosticInfo("USB Interface", "${foundInterface.id}")
                    listener.onDiagnosticInfo("USB Endpoints", "IN:${foundInEp?.endpointNumber} OUT:${foundOutEp?.endpointNumber}")
                    listener.onStateChanged(DeviceState.READY, transportType)
                }

                refreshDiagnosis()
            } else {
                AppLogger.e("USB", "Failed to claim USB interface!")
                connection.close()
            }
        } else {
            isConnected = true
            mainHandler.post {
                listener.onStateChanged(DeviceState.READY, transportType)
            }
        }
    }

    override fun refreshDiagnosis() {
        runSequence("Diagnosis", IqosProtocol.ALL_DIAGNOSIS_COMMANDS.toList())
    }

    /** Reads every documented value over the cable, exactly like the BLE transport does. */
    override fun refreshFullSnapshot() {
        runSequence("Snapshot", IqosCommands.FULL_SNAPSHOT_SEQUENCE.map { it.second })
    }

    /**
     * Writes a command over USB and then reads the value back to prove the device accepted it.
     * USB is synchronous, so the write and its confirmation happen in the same worker pass.
     */
    override fun submit(write: VerifiedWrite) {
        if (!isConnected || usbConnection == null) {
            listener.onCommandResult(
                CommandResult(write.tag, write.labelFa, CommandResult.Status.NOT_CONNECTED, "دستگاه USB متصل نیست")
            )
            listener.onError("دستگاه USB متصل نیست")
            return
        }

        backgroundExecutor.execute {
            write.packets.forEach { transmit(it, write.tag) }

            val matcher = write.matcher
            if (matcher == null || write.verifyPackets.isEmpty()) {
                mainHandler.post {
                    listener.onCommandResult(
                        CommandResult(
                            write.tag, write.labelFa, CommandResult.Status.SENT_UNVERIFIABLE,
                            "فرمان ارسال شد؛ این قابلیت فرمان خواندن وضعیت ندارد"
                        )
                    )
                }
                return@execute
            }

            var matched = verifyOverUsb(write, matcher)

            if (matched == false && write.retryPackets != null) {
                AppLogger.w("USB", "${write.tag} rejected, retrying with corrected frame")
                write.retryPackets.forEach { transmit(it, "${write.tag}/retry") }
                matched = verifyOverUsb(write, matcher)
            }

            val status = when (matched) {
                true -> CommandResult.Status.CONFIRMED
                false -> CommandResult.Status.REJECTED
                null -> CommandResult.Status.TIMEOUT
            }
            val detail = when (matched) {
                true -> "دستگاه مقدار جدید را تأیید کرد"
                false -> "دستگاه پاسخ داد ولی مقدار عوض نشد"
                null -> "پاسخ خواندن وضعیت از کابل نرسید"
            }
            mainHandler.post {
                listener.onCommandResult(CommandResult(write.tag, write.labelFa, status, detail))
            }
        }
    }

    private fun verifyOverUsb(write: VerifiedWrite, matcher: (IqosResponse) -> Boolean?): Boolean? {
        var result: Boolean? = null
        write.verifyPackets.forEach { packet ->
            transmit(packet, "${write.tag}/verify")
            val response = receive() ?: return@forEach
            val decision = matcher(response)
            if (decision != null) result = decision
        }
        return result
    }

    private fun runSequence(tag: String, packets: List<ByteArray>) {
        if (!isConnected || usbConnection == null) return
        backgroundExecutor.execute {
            AppLogger.i("USB", "Executing $tag sequence over USB (${packets.size} frames)")
            packets.forEach { packet ->
                transmit(packet, tag)
                receive()
            }
        }
    }

    private fun transmit(packet: ByteArray, tag: String) {
        val ep = outEndpoint
        AppLogger.i("USB", "TX $tag: ${IqosProtocol.toHexString(packet)}")
        FrameForensics.record("TX", "usb:scp", packet)
        FrameForensics.noteCommandSent(tag)
        if (ep != null) {
            usbConnection?.bulkTransfer(ep, packet, packet.size, TIMEOUT_MS)
        } else {
            usbConnection?.controlTransfer(0x21, 0x09, 0x0200, 0, packet, packet.size, TIMEOUT_MS)
        }
    }

    /** Reads one frame, decodes it, and pushes it into the shared snapshot. */
    private fun receive(): IqosResponse? {
        val epIn = inEndpoint ?: return null
        val connection = usbConnection ?: return null

        val buffer = ByteArray(epIn.maxPacketSize)
        val bytesRead = connection.bulkTransfer(epIn, buffer, buffer.size, TIMEOUT_MS)
        if (bytesRead <= 0) return null

        val rawData = buffer.copyOf(bytesRead)
        val hex = IqosProtocol.toHexString(rawData)
        AppLogger.i("USB", "USB RX: $hex")
        FrameForensics.record("RX", "usb:scp", rawData)

        val response = IqosResponse.decode(rawData)
        FrameForensics.classify("usb:scp", hex, "${response::class.simpleName}:SCP_DECODER")
        if (response is IqosResponse.Unknown) {
            val firstBytes = rawData.take(4).joinToString(" ") { String.format("%02X", it) }
            UnknownFrameHistory(context).record("SCP:$firstBytes", hex, "SCP_UNKNOWN_HEADER")
        }
        snapshot.transport = transportType
        snapshot.applyResponse(response)

        mainHandler.post {
            listener.onRawPacketReceived("USB", hex)
            when (response) {
                is IqosResponse.BatteryLevel -> listener.onBatteryReceived(response.percent, snapshot.batteryVolts())
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
        }
        return response
    }

    override fun sendCommand(command: IqosProtocol.CommandOpcode, value: Byte) {
        if (!isConnected || usbConnection == null) {
            listener.onError("دستگاه USB متصل نیست")
            return
        }

        backgroundExecutor.execute {
            val packet = IqosProtocol.buildPacket(command, value)
            AppLogger.i("USB", "Sending USB command ${command.name}: ${IqosProtocol.toHexString(packet)}")

            val ep = outEndpoint
            if (ep != null) {
                usbConnection?.bulkTransfer(ep, packet, packet.size, TIMEOUT_MS)
            } else {
                usbConnection?.controlTransfer(0x21, 0x09, 0x0200, 0, packet, packet.size, TIMEOUT_MS)
            }
        }
    }

    override fun sendRawSequence(packets: List<ByteArray>, tag: String) {
        if (!isConnected || usbConnection == null) {
            listener.onError("دستگاه USB متصل نیست")
            return
        }
        backgroundExecutor.execute {
            AppLogger.i("USB", "Sending $tag sequence (${packets.size} packets) over USB")
            packets.forEach { packet -> transmit(packet, tag) }
        }
    }

    override fun disconnect() {
        val iface = claimedInterface
        val conn = usbConnection
        claimedInterface = null
        usbConnection = null
        activeDevice = null
        isConnected = false

        if (conn != null && iface != null) {
            try {
                conn.releaseInterface(iface)
                conn.close()
                AppLogger.i("USB", "Released USB interface and closed connection")
            } catch (_: Exception) {}
        }
        mainHandler.post {
            listener.onStateChanged(DeviceState.DISCONNECTED, transportType)
        }
    }

    private fun logDeviceDiagnostics(device: UsbDevice) {
        AppLogger.i("USB", "=== USB DEVICE DIAGNOSTICS ===")
        AppLogger.i("USB", "Name: ${device.deviceName}, VID: 0x${Integer.toHexString(device.vendorId)}, PID: 0x${Integer.toHexString(device.productId)}")
        listener.onDiagnosticInfo("USB Device", "${device.deviceName} (VID:0x${Integer.toHexString(device.vendorId)})")
        listener.onDiagnosticInfo("USB VID/PID", "0x${Integer.toHexString(device.vendorId)}:0x${Integer.toHexString(device.productId)}")
    }
}
