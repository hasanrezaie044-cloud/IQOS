package com.example.iqoscontroller

/**
 * Typed decoder for SCP notification frames.
 *
 * The old decoder ([IqosProtocol.parseIncomingFrame]) only understood telemetry and battery
 * voltage, and fell back to "byte 2 looks like 0..100, must be a battery percentage" for anything
 * else - which silently mis-decoded settings replies as battery readings. This decoder keys off
 * the documented response headers instead, so every frame is either identified exactly or
 * reported as unknown (never guessed).
 *
 * Response header map (bytes[1], bytes[2], bytes[3]):
 *
 *   C0 88 00 / 08 88 00  firmware version (stick / holder)
 *   C0 88 03 / 08 88 03  product number, ASCII (stick / holder)
 *   xx 90 22             telemetry blocks (puff count, day counter)
 *   xx 80 02             timestamp (days used)
 *   xx 88 21             battery cell voltage (mV, little endian at bytes 5..6)
 *   C0 86 23             LED brightness (0x64 = high, 0x1E = low)
 *   08 84 23             vibration trigger flags
 *   08 8B 04             holder charge-start vibration flag (19-byte frame)
 *   90 85 22             FlexPuff state
 *   08 84 25             FlexBattery mode (0x00 = performance, 0x01 = eco)
 *   08 87 24             on/off setting reply, setting id at byte 4, value at byte 5
 */
sealed class IqosResponse {

    abstract val hex: String

    data class Firmware(
        val holder: Boolean,
        val major: Int,
        val minor: Int,
        val patch: Int,
        val year: Int,
        override val hex: String
    ) : IqosResponse() {
        fun versionString(): String = "v$major.$minor.$patch.$year"
    }

    data class ProductNumber(val holder: Boolean, val value: String, override val hex: String) : IqosResponse()

    data class Telemetry(val puffCount: Int?, val daysUsed: Int?, override val hex: String) : IqosResponse()

    data class Timestamp(val daysUsed: Int, override val hex: String) : IqosResponse()

    data class BatteryVoltage(val millivolts: Int, override val hex: String) : IqosResponse() {
        val volts: Float get() = millivolts / 1000.0f
    }

    data class BatteryLevel(val percent: Int, override val hex: String) : IqosResponse()

    data class Brightness(val high: Boolean, override val hex: String) : IqosResponse()

    data class VibrationFlags(
        val whenHeatingStart: Boolean,
        val whenStartingToUse: Boolean,
        val whenPuffEnd: Boolean,
        val whenManuallyTerminated: Boolean,
        override val hex: String
    ) : IqosResponse()

    data class ChargeStartVibration(val enabled: Boolean, override val hex: String) : IqosResponse()

    data class FlexPuff(val enabled: Boolean, override val hex: String) : IqosResponse()

    data class FlexBattery(val eco: Boolean, override val hex: String) : IqosResponse() {
        val modeFa: String get() = if (eco) "اکو (Eco)" else "پرفورمنس (Performance)"
    }

    /** Generic on/off setting reply. [settingId]: 0x01 Auto Start, 0x02 Pause Mode, 0x04 Smart Gesture. */
    data class SettingState(val settingId: Int, val enabled: Boolean, override val hex: String) : IqosResponse() {
        val labelFa: String
            get() = when (settingId) {
                SETTING_AUTO_START -> "روشن شدن خودکار (Auto Start)"
                SETTING_PAUSE_MODE -> "حالت توقف (Pause Mode)"
                SETTING_SMART_GESTURE -> "ژست هوشمند (Smart Gesture)"
                else -> "تنظیم ناشناخته (0x%02X)".format(settingId)
            }
    }

    data class Unknown(override val hex: String, val reason: String) : IqosResponse()

    companion object {

        const val SETTING_AUTO_START = 0x01
        const val SETTING_PAUSE_MODE = 0x02
        const val SETTING_SMART_GESTURE = 0x04

        private const val TAG_PUFF_COUNT = 0x8E
        private const val TAG_DAY_COUNTER = 0x17

        private fun u8(bytes: ByteArray, index: Int): Int = bytes[index].toInt() and 0xFF

        private fun le16(bytes: ByteArray, low: Int): Int = u8(bytes, low) or (u8(bytes, low + 1) shl 8)

        /** Decodes a frame received on the SCP control characteristic. */
        fun decode(bytes: ByteArray): IqosResponse {
            val hex = IqosProtocol.toHexString(bytes)
            if (bytes.size < 4) return Unknown(hex, "فریم کوتاه‌تر از ۴ بایت")

            val kind = u8(bytes, 1)
            val header = u8(bytes, 2)
            val register = u8(bytes, 3)

            // --- Firmware version: kind 0xC0 (stick) or 0x08 (holder), header 0x88, register 0x00
            if (header == 0x88 && register == 0x00 && bytes.size >= 10 && (kind == 0xC0 || kind == 0x08)) {
                return Firmware(
                    holder = kind == 0x08,
                    major = u8(bytes, 6),
                    minor = u8(bytes, 7),
                    patch = u8(bytes, 8),
                    year = u8(bytes, 9),
                    hex = hex
                )
            }

            // --- Product number: ASCII payload from byte 4 (stick drops the trailing checksum)
            if (header == 0x88 && register == 0x03 && bytes.size > 4 && (kind == 0xC0 || kind == 0x08)) {
                val end = if (kind == 0xC0) bytes.size - 1 else bytes.size
                val payload = if (end > 4) bytes.copyOfRange(4, end) else ByteArray(0)
                val text = payload.map { b ->
                    val c = b.toInt() and 0xFF
                    if (c in 0x20..0x7E) c.toChar() else '.'
                }.joinToString("")
                return ProductNumber(holder = kind == 0x08, value = text.trim(), hex = hex)
            }

            // --- Telemetry blocks
            if (header == 0x90 && register == 0x22) {
                if (bytes.size < 6) return Unknown(hex, "فریم تلمتری کوتاه است")
                if (le16(bytes, 4) != 0x0101) return Unknown(hex, "نشانگر تلمتری نامعتبر")

                var puffs: Int? = null
                var days: Int? = null
                val blockStart = 6
                val declaredBlocks = ((u8(bytes, 3) - 2).coerceAtLeast(0)) / 8
                val availableBlocks = (bytes.size - blockStart) / 8
                val blocks = if (declaredBlocks in 1..availableBlocks) declaredBlocks else availableBlocks

                for (i in 0 until blocks) {
                    val offset = blockStart + i * 8
                    val value = le16(bytes, offset + 4)
                    when (u8(bytes, offset + 7)) {
                        TAG_PUFF_COUNT -> puffs = value
                        TAG_DAY_COUNTER -> days = value
                    }
                }
                return Telemetry(puffs, days, hex)
            }

            // --- Timestamp (days in service) - this frame type was never parsed before
            if (header == 0x80 && register == 0x02) {
                if (bytes.size < 6) return Unknown(hex, "فریم تاریخ کوتاه است")
                return Timestamp(le16(bytes, 4), hex)
            }

            // --- Battery cell voltage in millivolts
            if (header == 0x88 && register == 0x21) {
                if (bytes.size < 7) return Unknown(hex, "فریم ولتاژ کوتاه است")
                return BatteryVoltage(le16(bytes, 5), hex)
            }

            // --- LED brightness
            if (kind == 0xC0 && header == 0x86 && register == 0x23 && bytes.size >= 9) {
                return when (u8(bytes, 4)) {
                    0x64 -> Brightness(high = true, hex = hex)
                    0x1E -> Brightness(high = false, hex = hex)
                    else -> Unknown(hex, "سطح روشنایی ناشناخته")
                }
            }

            // --- Vibration trigger flags
            if (kind == 0x08 && header == 0x84 && register == 0x23 && bytes.size >= 9) {
                val marker = u8(bytes, 4)
                if (marker != 0x10 && marker != 0x03) return Unknown(hex, "نشانگر فریم لرزش نامعتبر")
                val heatUse = u8(bytes, 6)
                val endTerminated = u8(bytes, 7)
                return VibrationFlags(
                    whenHeatingStart = heatUse and 0x01 != 0,
                    whenStartingToUse = heatUse and 0x10 != 0,
                    whenPuffEnd = endTerminated and 0x01 != 0,
                    whenManuallyTerminated = endTerminated and 0x10 != 0,
                    hex = hex
                )
            }

            // --- Holder charge-start vibration (19-byte frame, flag at byte 9 inverted)
            if (kind == 0x08 && header == 0x8B && register == 0x04 && bytes.size >= 19) {
                // The reference matches two exact frames and otherwise falls back to bytes[8].
                val enabled = when (u8(bytes, 9)) {
                    0x00 -> true
                    0x09 -> false
                    else -> u8(bytes, 8) == 0x01
                }
                return ChargeStartVibration(enabled, hex)
            }

            // --- FlexPuff
            if (kind == 0x90 && header == 0x85 && register == 0x22 && bytes.size >= 9 && u8(bytes, 4) == 0x03) {
                return when (u8(bytes, 5)) {
                    0x00 -> FlexPuff(enabled = false, hex = hex)
                    0x01 -> FlexPuff(enabled = true, hex = hex)
                    else -> Unknown(hex, "مقدار فلکس‌پاف ناشناخته")
                }
            }

            // --- FlexBattery mode
            if (kind == 0x08 && header == 0x84 && register == 0x25 && bytes.size >= 9) {
                return when (u8(bytes, 4)) {
                    0x00 -> FlexBattery(eco = false, hex = hex)
                    0x01 -> FlexBattery(eco = true, hex = hex)
                    else -> Unknown(hex, "حالت فلکس‌باتری ناشناخته")
                }
            }

            // --- Generic on/off setting reply (Auto Start, Pause Mode, Smart Gesture)
            if (kind == 0x08 && header == 0x87 && register == 0x24 && bytes.size >= 9) {
                val settingId = u8(bytes, 4)
                val value = u8(bytes, 5)
                if (value != 0x00 && value != 0x01) return Unknown(hex, "مقدار تنظیم ناشناخته")
                return SettingState(settingId, value == 0x01, hex)
            }

            return Unknown(hex, "هدر ناشناخته (${"%02X".format(header)} ${"%02X".format(register)})")
        }

        /**
         * Decodes the GATT battery characteristic. Per the reference implementation the charge
         * percentage sits at byte 2 of the 7-byte IQOS frame; a plain 1-byte frame is the
         * standard Bluetooth Battery Level format.
         */
        fun decodeBatteryCharacteristic(bytes: ByteArray): IqosResponse {
            val hex = IqosProtocol.toHexString(bytes)
            return when {
                bytes.isEmpty() -> Unknown(hex, "فریم باتری خالی است")
                bytes.size == 1 -> {
                    val percent = u8(bytes, 0)
                    if (percent in 0..100) BatteryLevel(percent, hex) else Unknown(hex, "درصد باتری نامعتبر")
                }
                bytes.size >= 3 -> {
                    val percent = u8(bytes, 2)
                    if (percent in 0..100) BatteryLevel(percent, hex) else Unknown(hex, "درصد باتری نامعتبر")
                }
                else -> Unknown(hex, "فریم باتری ناقص")
            }
        }

        /**
         * Estimates state-of-charge from cell voltage using a piecewise single-cell Li-ion
         * open-circuit-voltage curve. Only used as a secondary display value: the percentage the
         * device itself reports always wins.
         */
        fun estimatePercentFromVoltage(volts: Float): Int {
            val curve = listOf(
                3.30f to 0, 3.50f to 5, 3.60f to 10, 3.68f to 20, 3.74f to 30,
                3.79f to 40, 3.83f to 50, 3.87f to 60, 3.92f to 70, 3.98f to 80,
                4.06f to 90, 4.15f to 97, 4.20f to 100
            )
            if (volts <= curve.first().first) return 0
            if (volts >= curve.last().first) return 100
            for (i in 0 until curve.size - 1) {
                val (v0, p0) = curve[i]
                val (v1, p1) = curve[i + 1]
                if (volts in v0..v1) {
                    val ratio = (volts - v0) / (v1 - v0)
                    return (p0 + ratio * (p1 - p0)).toInt().coerceIn(0, 100)
                }
            }
            return 0
        }
    }
}
