package com.example.iqoscontroller

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

object IqosProtocol {

    val RAB_SERVICE_UUID: UUID = UUID.fromString("01b5f798-be55-42bc-8aa8-0025b903dc3b")
    val RRP_SERVICE_UUID: UUID = UUID.fromString("daebb240-b041-11e4-9e45-0002a5d5c51b")

    val STANDARD_BATTERY_SERVICE_UUID: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val STANDARD_BATTERY_LEVEL_CHAR_UUID: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    val DEVICE_INFO_SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val SERIAL_NUMBER_CHAR_UUID: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION_CHAR_UUID: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")

    val DEVICE_STATUS_CHAR_UUID: UUID = UUID.fromString("ecdfa4c0-b041-11e4-8b67-0002a5d5c51b")
    val DEVICE_BATTERY_CHAR_UUID: UUID = UUID.fromString("f8a54120-b041-11e4-9be7-0002a5d5c51b")
    val SCP_CONTROL_POINT_UUID: UUID = UUID.fromString("e16c6e20-b041-11e4-a4c3-0002a5d5c51b")
    val CCCD_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val DEVICE_NAME_TOKENS = listOf("IQOS", "ILUMA", "PRIME", "PMI")

    // Core SCP Diagnosis commands
    val LOAD_TELEMETRY_COMMAND = byteArrayOf(0x00, 0xC9.toByte(), 0x10, 0x02, 0x01, 0x01, 0x75.toByte(), 0xD6.toByte())
    val LOAD_TIMESTAMP_COMMAND = byteArrayOf(0x00, 0xC0.toByte(), 0x10, 0x02, 0x00, 0x04, 0x38.toByte(), 0xEF.toByte())
    val LOAD_BATTERY_VOLTAGE_COMMAND = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x21, 0xE7.toByte())

    // Verified SCP Feature Commands from hauntedfail/iqos
    val SMARTGESTURE_ENABLE_COMMAND = byteArrayOf(0x00, 0xC9.toByte(), 0x47, 0x24, 0x04, 0x01, 0x00, 0x00, 0x3C)
    val SMARTGESTURE_DISABLE_COMMAND = byteArrayOf(0x00, 0xC9.toByte(), 0x47, 0x24, 0x04, 0x00, 0x00, 0x00, 0x57.toByte())

    val AUTOSTART_ENABLE_COMMAND = byteArrayOf(0x00, 0xC9.toByte(), 0x47, 0x24, 0x01, 0x01, 0x00, 0x00, 0x3F)
    val AUTOSTART_DISABLE_COMMAND = byteArrayOf(0x00, 0xC9.toByte(), 0x47, 0x24, 0x01, 0x00, 0x00, 0x00, 0x54.toByte())

    val LOAD_FLEXPUFF_COMMAND = byteArrayOf(0x00, 0xD2.toByte(), 0x05, 0x22, 0x03, 0x00, 0x00, 0x00, 0x17)
    val FLEXPUFF_ENABLE_COMMAND = byteArrayOf(0x00, 0xD2.toByte(), 0x45, 0x22, 0x03, 0x01, 0x00, 0x00, 0x0A)
    val FLEXPUFF_DISABLE_COMMAND = byteArrayOf(0x00, 0xD2.toByte(), 0x45, 0x22, 0x03, 0x00, 0x00, 0x00, 0x0A)

    val LOAD_FLEXBATTERY_COMMAND = byteArrayOf(0x00, 0xC9.toByte(), 0x00, 0x25, 0xFB.toByte())
    val LOAD_PAUSEMODE_COMMAND = byteArrayOf(0x00, 0xC9.toByte(), 0x07, 0x24, 0x02, 0x00, 0x00, 0x00, 0x18)
    val PAUSEMODE_ENABLE_SET_COMMAND = byteArrayOf(0x00, 0xC9.toByte(), 0x47, 0x24, 0x02, 0x01, 0x00, 0x00, 0x05)
    val PAUSEMODE_DISABLE_SET_COMMAND = byteArrayOf(0x00, 0xC9.toByte(), 0x47, 0x24, 0x02, 0x00, 0x00, 0x00, 0x6E)

    // Verified real Brightness/Vibration commands, sourced from the "iqos" Rust crate protocol
    // layer (docs.rs/crate/iqos - the reference library also cited for the commands above).
    // Brightness is NOT a single-packet write: the real firmware expects a 3-command sequence
    // (set -> re-read -> confirm), which is why the old generic single-byte packet never worked.
    val LOAD_BRIGHTNESS_COMMAND = byteArrayOf(0x00, 0xC0.toByte(), 0x02, 0x23, 0xC3.toByte())
    val SET_BRIGHTNESS_HIGH_COMMANDS = listOf(
        byteArrayOf(0x00, 0xC0.toByte(), 0x46, 0x23, 0x64, 0x00, 0x00, 0x00, 0x4F),
        byteArrayOf(0x00, 0xC0.toByte(), 0x02, 0x23, 0xC3.toByte()),
        byteArrayOf(0x00, 0xC9.toByte(), 0x44, 0x24, 0x64, 0x00, 0x00, 0x00, 0x34)
    )
    val SET_BRIGHTNESS_LOW_COMMANDS = listOf(
        byteArrayOf(0x00, 0xC0.toByte(), 0x46, 0x23, 0x1E, 0x00, 0x00, 0x00, 0xE1.toByte()),
        byteArrayOf(0x00, 0xC0.toByte(), 0x02, 0x23, 0xC3.toByte()),
        byteArrayOf(0x00, 0xC9.toByte(), 0x44, 0x24, 0x1E, 0x00, 0x00, 0x00, 0x9A.toByte())
    )

    // The real "vibration" endpoint on the device is a multi-flag settings frame (heating
    // start / puff end / etc), not a plain on-off switch, and its SET command bitmask isn't
    // publicly documented anywhere we could verify. What IS verified is the physical vibration
    // burst used for "Find My IQOS" - that's what the Vibration widget now actually triggers,
    // so the toggle does something real on the hardware instead of nothing.
    val START_VIBRATE_COMMAND = byteArrayOf(0x00, 0xC0.toByte(), 0x45, 0x22, 0x01, 0x1E, 0x00, 0x00, 0xC3.toByte())
    val STOP_VIBRATE_COMMAND = byteArrayOf(0x00, 0xC0.toByte(), 0x45, 0x22, 0x00, 0x1E, 0x00, 0x00, 0xD5.toByte())

    val ALL_DIAGNOSIS_COMMANDS = listOf(
        LOAD_TELEMETRY_COMMAND,
        LOAD_TIMESTAMP_COMMAND,
        LOAD_TELEMETRY_COMMAND,
        LOAD_BATTERY_VOLTAGE_COMMAND
    )

    const val TAG_PUFF_COUNT: Byte = 0x8E.toByte()
    const val TAG_DAY_COUNTER: Byte = 0x17.toByte()

    enum class CommandOpcode(val opcode: Byte, val descriptionFa: String) {
        DEVICE_LOCK(0x0A.toByte(), "قفل/بازگشایی دستگاه"),
        VIBRATION(0x12.toByte(), "هشدار لرزش"),
        BRIGHTNESS(0x14.toByte(), "روشنایی LED"),
        SMART_GESTURE(0x24.toByte(), "ژست هوشمند (Smart Gesture)"),
        AUTO_START(0x25.toByte(), "روشن شدن خودکار (AutoStart)"),
        FLEX_PUFF(0x22.toByte(), "فلکس‌پاف (FlexPuff)"),
        PAUSE_MODE(0x26.toByte(), "حالت توقف (Pause Mode)")
    }

    fun buildPacket(command: CommandOpcode, value: Byte): ByteArray {
        return when (command) {
            CommandOpcode.SMART_GESTURE -> if (value.toInt() != 0) SMARTGESTURE_ENABLE_COMMAND else SMARTGESTURE_DISABLE_COMMAND
            CommandOpcode.AUTO_START -> if (value.toInt() != 0) AUTOSTART_ENABLE_COMMAND else AUTOSTART_DISABLE_COMMAND
            CommandOpcode.FLEX_PUFF -> if (value.toInt() != 0) FLEXPUFF_ENABLE_COMMAND else FLEXPUFF_DISABLE_COMMAND
            CommandOpcode.PAUSE_MODE -> if (value.toInt() != 0) PAUSEMODE_ENABLE_SET_COMMAND else PAUSEMODE_DISABLE_SET_COMMAND
            else -> byteArrayOf(command.opcode, 0x01.toByte(), value)
        }
    }

    data class DecodedDeviceData(
        val batteryPercent: Int? = null,
        val batteryVoltage: Float? = null,
        val totalPuffCount: Int? = null,
        val daysUsed: Int? = null,
        val rawHex: String = ""
    )

    fun parseIncomingFrame(bytes: ByteArray): DecodedDeviceData {
        if (bytes.isEmpty()) return DecodedDeviceData()
        val hex = toHexString(bytes)

        if (bytes.size >= 5) {
            val h0 = bytes[2].toInt() and 0xFF
            val h1 = bytes[3].toInt() and 0xFF

            if (h0 == 0x90 && h1 == 0x22) {
                var puffs: Int? = null
                var days: Int? = null
                var offset = 6
                while (offset + 8 <= bytes.size) {
                    val value = (bytes[offset + 4].toInt() and 0xFF) or ((bytes[offset + 5].toInt() and 0xFF) shl 8)
                    val tag = bytes[offset + 7]
                    if (tag == TAG_PUFF_COUNT) puffs = value
                    else if (tag == TAG_DAY_COUNTER) days = value
                    offset += 8
                }
                return DecodedDeviceData(totalPuffCount = puffs, daysUsed = days, rawHex = hex)
            }

            if (h0 == 0x88 && h1 == 0x21 && bytes.size >= 7) {
                val rawMv = (bytes[5].toInt() and 0xFF) or ((bytes[6].toInt() and 0xFF) shl 8)
                val volts = rawMv.toFloat() / 1000.0f
                val calculatedPercent = when {
                    volts >= 4.18f -> 100
                    volts <= 3.50f -> 0
                    else -> (((volts - 3.50f) / (4.18f - 3.50f)) * 100f).toInt().coerceIn(0, 100)
                }
                return DecodedDeviceData(
                    batteryPercent = calculatedPercent,
                    batteryVoltage = volts,
                    rawHex = hex
                )
            }
        }

        if (bytes.size == 1) {
            val v = bytes[0].toInt() and 0xFF
            if (v in 0..100) return DecodedDeviceData(batteryPercent = v, rawHex = hex)
        } else if (bytes.size >= 3) {
            val b2 = bytes[2].toInt() and 0xFF
            if (b2 in 0..100) return DecodedDeviceData(batteryPercent = b2, rawHex = hex)
        }

        if (bytes.size >= 4) {
            val total32 = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (total32 > 0 && total32 < 200000) {
                return DecodedDeviceData(totalPuffCount = total32, rawHex = hex)
            }
        }

        return DecodedDeviceData(rawHex = hex)
    }

    fun toHexString(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it) }
}
