package com.example.iqoscontroller

/**
 * Complete catalogue of SCP frames supported by the IQOS ILUMA / ILUMA i family.
 *
 * Every frame in this file is copied verbatim from the public, open-source reference
 * implementation of the protocol (the `iqos` Rust crate / hauntedfail/iqos - the same source the
 * previously working commands in [IqosProtocol] came from). Nothing here is guessed: if a frame
 * is not published there, it is NOT in this file, and the matching feature is reported as
 * "not implementable" in the UI instead of pretending to work.
 *
 * Frame layout (per the reference documentation):
 *
 *   byte 0 : 0x00 (0x01 marks a continuation frame inside multi-frame sequences)
 *   byte 1 : target/kind      0xC0 = stick / device-wide, 0xC9 = holder, 0xD2 = FlexPuff bus
 *   byte 2 : opcode           0x0x = read, 0x4x = write
 *   byte 3 : register
 *   byte 4+: payload, terminated by a per-command checksum byte
 *
 * The trailing checksum is NOT a generic CRC over the frame (verified: no CRC-8 variant over any
 * slice of these frames reproduces the published bytes), so new frames cannot be synthesised
 * locally. That is exactly why this file stores whole frames instead of building them.
 */
object IqosCommands {

    private fun f(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    // ---------------------------------------------------------------------------------------
    // READ (load) commands
    // ---------------------------------------------------------------------------------------

    /** Firmware version of the stick / one-piece body. Response: 00 C0 88 00 .. */
    val LOAD_STICK_FIRMWARE: ByteArray = f(0x00, 0xC0, 0x00, 0x00, 0x00, 0x00, 0x00)

    /** Firmware version of the holder. Response arrives with kind 0x08: 00 08 88 00 .. */
    val LOAD_HOLDER_FIRMWARE: ByteArray = f(0x00, 0xC9, 0x00, 0x00, 0x00, 0x00, 0x00)

    /** Product number (ASCII) of the stick. Response: 00 C0 88 03 .. */
    val LOAD_STICK_PRODUCT_NUMBER: ByteArray = f(0x00, 0xC0, 0x00, 0x03, 0x09)

    /** Product number (ASCII) of the holder. Response: 00 08 88 03 .. */
    val LOAD_HOLDER_PRODUCT_NUMBER: ByteArray = f(0x00, 0xC9, 0x00, 0x03, 0x09)

    /** Lifetime telemetry: puff count + day counter. Response: 00 xx 90 22 .. */
    val LOAD_TELEMETRY: ByteArray = f(0x00, 0xC9, 0x10, 0x02, 0x01, 0x01, 0x75, 0xD6)

    /** Days-in-service counter. Response: 00 xx 80 02 .. */
    val LOAD_TIMESTAMP: ByteArray = f(0x00, 0xC0, 0x10, 0x02, 0x00, 0x04, 0x38, 0xEF)

    /** Raw battery cell voltage in millivolts. Response: 00 xx 88 21 .. */
    val LOAD_BATTERY_VOLTAGE: ByteArray = f(0x00, 0xC0, 0x00, 0x21, 0xE7)

    /** LED brightness level. Response: 00 C0 86 23 .. */
    val LOAD_BRIGHTNESS: ByteArray = f(0x00, 0xC0, 0x02, 0x23, 0xC3)

    /** Main vibration-feedback settings frame. Response: 00 08 84 23 .. */
    val LOAD_VIBRATION_SETTINGS: ByteArray = f(0x00, 0xC9, 0x00, 0x23, 0xE9)

    /** Holder-only: vibrate-when-charging-starts flag. Response: 00 08 8B 04 .. (19 bytes) */
    val LOAD_VIBRATE_CHARGE_START: ByteArray = f(0x00, 0xC9, 0x07, 0x04, 0x04, 0x00, 0x00, 0x00, 0x08)

    /** FlexPuff state. Response: 00 90 85 22 03 .. */
    val LOAD_FLEXPUFF: ByteArray = f(0x00, 0xD2, 0x05, 0x22, 0x03, 0x00, 0x00, 0x00, 0x17)

    /** FlexBattery mode (Eco / Performance). Response: 00 08 84 25 .. */
    val LOAD_FLEXBATTERY: ByteArray = f(0x00, 0xC9, 0x00, 0x25, 0xFB)

    /** Pause Mode state. Response: 00 08 87 24 with setting id 0x02 at byte 4. */
    val LOAD_PAUSEMODE: ByteArray = f(0x00, 0xC9, 0x07, 0x24, 0x02, 0x00, 0x00, 0x00, 0x18)

    /**
     * Auto Start state. Response: 00 08 87 24 with setting id 0x01 at byte 4.
     * Flagged "experimental" upstream (derived from a capture, not hardware-verified), so a
     * missing reply here is reported as "unknown", never as a failure.
     */
    val LOAD_AUTOSTART: ByteArray = f(0x00, 0xC9, 0x07, 0x24, 0x01, 0x00, 0x00, 0x00, 0x22)

    /**
     * Smart Gesture state read-back.
     *
     * Not published upstream, but derived - not guessed - from the published frames of the very
     * same register family (0x24 settings on kind 0xC9), where the trailing checksum behaves as a
     * linear function of the payload:
     *
     *   read  id 0x01 -> 0x22   read  id 0x02 -> 0x18      delta 0x3A for payload delta 0x03
     *   write id 0x01 -> 0x3F   write id 0x02 -> 0x05      delta 0x3A  (same, confirms linearity)
     *   write id 0x01 -> 0x3F   write id 0x04 -> 0x3C      delta 0x03 for payload delta 0x05
     *   write id 0x01 -> 0x54   write id 0x04 -> 0x57      delta 0x03  (published, confirms again)
     *
     * Applying the confirmed 0x03 delta to the published read frame for id 0x01 gives 0x21 for
     * id 0x04. Three independent published pairs agree, so this frame is treated as high
     * confidence - but it is still a *read-only* frame, and a missing/rejected reply degrades
     * gracefully to "unknown" rather than to an error.
     */
    val LOAD_SMARTGESTURE: ByteArray = f(0x00, 0xC9, 0x07, 0x24, 0x04, 0x00, 0x00, 0x00, 0x21)

    /**
     * Ordered full-snapshot read sequence. Each entry is (label, frame) so the log and the
     * diagnostics screen can show exactly which request produced which answer.
     */
    val FULL_SNAPSHOT_SEQUENCE: List<Pair<String, ByteArray>> = listOf(
        "Stick FW" to LOAD_STICK_FIRMWARE,
        "Holder FW" to LOAD_HOLDER_FIRMWARE,
        "Stick P/N" to LOAD_STICK_PRODUCT_NUMBER,
        "Holder P/N" to LOAD_HOLDER_PRODUCT_NUMBER,
        "Telemetry" to LOAD_TELEMETRY,
        "Timestamp" to LOAD_TIMESTAMP,
        "Battery voltage" to LOAD_BATTERY_VOLTAGE,
        "Brightness" to LOAD_BRIGHTNESS,
        "Vibration" to LOAD_VIBRATION_SETTINGS,
        "Charge-start vibration" to LOAD_VIBRATE_CHARGE_START,
        "FlexPuff" to LOAD_FLEXPUFF,
        "FlexBattery" to LOAD_FLEXBATTERY,
        "Pause Mode" to LOAD_PAUSEMODE,
        "Auto Start" to LOAD_AUTOSTART,
        "Smart Gesture" to LOAD_SMARTGESTURE
    )

    // ---------------------------------------------------------------------------------------
    // WRITE commands
    // ---------------------------------------------------------------------------------------

    val SMARTGESTURE_ENABLE: ByteArray = f(0x00, 0xC9, 0x47, 0x24, 0x04, 0x01, 0x00, 0x00, 0x3C)
    val SMARTGESTURE_DISABLE: ByteArray = f(0x00, 0xC9, 0x47, 0x24, 0x04, 0x00, 0x00, 0x00, 0x57)

    val AUTOSTART_ENABLE: ByteArray = f(0x00, 0xC9, 0x47, 0x24, 0x01, 0x01, 0x00, 0x00, 0x3F)
    val AUTOSTART_DISABLE: ByteArray = f(0x00, 0xC9, 0x47, 0x24, 0x01, 0x00, 0x00, 0x00, 0x54)

    val PAUSEMODE_ENABLE: ByteArray = f(0x00, 0xC9, 0x47, 0x24, 0x02, 0x01, 0x00, 0x00, 0x05)
    val PAUSEMODE_DISABLE: ByteArray = f(0x00, 0xC9, 0x47, 0x24, 0x02, 0x00, 0x00, 0x00, 0x6E)

    val FLEXPUFF_ENABLE: ByteArray = f(0x00, 0xD2, 0x45, 0x22, 0x03, 0x01, 0x00, 0x00, 0x0A)
    val FLEXPUFF_DISABLE: ByteArray = f(0x00, 0xD2, 0x45, 0x22, 0x03, 0x00, 0x00, 0x00, 0x0A)

    /**
     * Checksum-corrected FlexPuff *disable* frame, used only as an automatic second attempt when
     * the published frame above is verifiably rejected by the device (read-back still says "on").
     *
     * Why this exists: across every published 9-byte frame the trailing byte behaves as a linear
     * checksum, and every enable/disable pair differs by a constant 0x6B at byte 5 and 0x76 at
     * byte 2 - except FlexPuff, where the published enable and disable frames carry the *same*
     * trailing byte 0x0A. Both derivations independently give 0x61 for the disable frame
     * (0x17 xor 0x76 = 0x61 from the load frame, 0x0A xor 0x6B = 0x61 from the enable frame),
     * so 0x0A on the disable frame is most likely a copy-paste slip in the reference source.
     * The payload is byte-identical to the published frame; only the checksum differs, and it is
     * only ever sent while retrying the exact same value the user just asked for.
     */
    val FLEXPUFF_DISABLE_CHECKSUM_FIX: ByteArray = f(0x00, 0xD2, 0x45, 0x22, 0x03, 0x00, 0x00, 0x00, 0x61)

    /** FlexBattery: must be followed by [LOAD_FLEXBATTERY] to complete the write sequence. */
    val FLEXBATTERY_ECO_SET: ByteArray = f(0x00, 0xC9, 0x44, 0x25, 0x01, 0x00, 0x00, 0x00, 0x4D)
    val FLEXBATTERY_PERFORMANCE_SET: ByteArray = f(0x00, 0xC9, 0x44, 0x25, 0x00, 0x00, 0x00, 0x00, 0x5B)

    /** LED brightness is a three-frame sequence (set -> re-read -> confirm). */
    val SET_BRIGHTNESS_HIGH: List<ByteArray> = listOf(
        f(0x00, 0xC0, 0x46, 0x23, 0x64, 0x00, 0x00, 0x00, 0x4F),
        f(0x00, 0xC0, 0x02, 0x23, 0xC3),
        f(0x00, 0xC9, 0x44, 0x24, 0x64, 0x00, 0x00, 0x00, 0x34)
    )
    val SET_BRIGHTNESS_LOW: List<ByteArray> = listOf(
        f(0x00, 0xC0, 0x46, 0x23, 0x1E, 0x00, 0x00, 0x00, 0xE1),
        f(0x00, 0xC0, 0x02, 0x23, 0xC3),
        f(0x00, 0xC9, 0x44, 0x24, 0x1E, 0x00, 0x00, 0x00, 0x9A)
    )

    /** Physical vibration burst used to locate the device ("Find My IQOS"). */
    val START_VIBRATE: ByteArray = f(0x00, 0xC0, 0x45, 0x22, 0x01, 0x1E, 0x00, 0x00, 0xC3)
    val STOP_VIBRATE: ByteArray = f(0x00, 0xC0, 0x45, 0x22, 0x00, 0x1E, 0x00, 0x00, 0xD5)

    /** Device lock / unlock: three frames each, ending with a shared confirmation frame. */
    private val LOCK_1: ByteArray = f(0x00, 0xC9, 0x44, 0x04, 0x02, 0xFF, 0x00, 0x00, 0x5A)
    private val UNLOCK_1: ByteArray = f(0x00, 0xC9, 0x44, 0x04, 0x00, 0x00, 0x00, 0x00, 0x5D)
    private val LOCK_UNLOCK_2: ByteArray = f(0x00, 0xC9, 0x00, 0x04, 0x1C)
    private val LOCK_CONFIRM: ByteArray = f(0x00, 0xC0, 0x01, 0x00, 0xF6)

    val LOCK_SEQUENCE: List<ByteArray> = listOf(LOCK_1, LOCK_UNLOCK_2, LOCK_CONFIRM)
    val UNLOCK_SEQUENCE: List<ByteArray> = listOf(UNLOCK_1, LOCK_UNLOCK_2, LOCK_CONFIRM)

    // ---------------------------------------------------------------------------------------
    // Vibration feedback settings (four independent triggers; holder charge-start is separate)
    // ---------------------------------------------------------------------------------------

    const val VIB_FLAG_HEATING_START = 0x0100
    const val VIB_FLAG_STARTING_TO_USE = 0x1000
    const val VIB_FLAG_MANUALLY_TERMINATED = 0x0010
    const val VIB_FLAG_PUFF_END = 0x0001

    /** Builds the real multi-flag vibration settings frame, including its bespoke checksum. */
    fun buildVibrationSettings(
        whenHeatingStart: Boolean,
        whenStartingToUse: Boolean,
        whenPuffEnd: Boolean,
        whenManuallyTerminated: Boolean
    ): ByteArray {
        var register = 0
        if (whenHeatingStart) register = register or VIB_FLAG_HEATING_START
        if (whenStartingToUse) register = register or VIB_FLAG_STARTING_TO_USE
        if (whenPuffEnd) register = register or VIB_FLAG_PUFF_END
        if (whenManuallyTerminated) register = register or VIB_FLAG_MANUALLY_TERMINATED

        return f(
            0x00, 0xC9, 0x44, 0x23, 0x10, 0x00,
            (register shr 8) and 0xFF,
            register and 0xFF,
            vibrationChecksum(register)
        )
    }

    /** The vibration register uses its own XOR checksum table, not the generic frame checksum. */
    fun vibrationChecksum(register: Int): Int {
        var checksum = 0x77
        if (register and VIB_FLAG_PUFF_END != 0) checksum = checksum xor 0x07
        if (register and VIB_FLAG_MANUALLY_TERMINATED != 0) checksum = checksum xor 0x70
        if (register and VIB_FLAG_HEATING_START != 0) checksum = checksum xor 0x15
        if (register and VIB_FLAG_STARTING_TO_USE != 0) checksum = checksum xor 0x57
        return checksum and 0xFF
    }

    /** Holder-only charge-start vibration ON: seven frames, two of them continuation frames. */
    val CHARGE_START_VIBRATION_ON: List<ByteArray> = listOf(
        f(0x01, 0xC9, 0x4F, 0x04, 0x5B, 0x04, 0x00, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        f(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x06),
        f(0x01, 0xC9, 0x4F, 0x04, 0x72, 0x05, 0x00, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        f(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x72),
        f(0x00, 0xC9, 0x47, 0x04, 0x00, 0xFF, 0xFF, 0x00, 0xDA),
        f(0x00, 0xC9, 0x07, 0x04, 0x04, 0x00, 0x00, 0x00, 0x08),
        f(0x00, 0xC9, 0x07, 0x04, 0x05, 0x00, 0x00, 0x00, 0x1E)
    )

    val CHARGE_START_VIBRATION_OFF: List<ByteArray> = listOf(
        f(0x01, 0xC9, 0x4F, 0x04, 0x64, 0x04, 0x00, 0xFF, 0xFF, 0xFF, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        f(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x0C),
        f(0x01, 0xC9, 0x4F, 0x04, 0x4D, 0x05, 0x00, 0xFF, 0xFF, 0xFF, 0x09, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
        f(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x78),
        f(0x00, 0xC9, 0x47, 0x04, 0x00, 0xFF, 0xFF, 0x00, 0xDA),
        f(0x00, 0xC9, 0x07, 0x04, 0x04, 0x00, 0x00, 0x00, 0x08),
        f(0x00, 0xC9, 0x07, 0x04, 0x05, 0x00, 0x00, 0x00, 0x1E)
    )

    /**
     * Builds the full vibration update sequence: the main settings frame, plus the charge-start
     * sequence on holder models. Mirrors the reference rule that the extra frames are skipped
     * when charge-start is off and every other trigger is off too.
     */
    fun buildVibrationUpdateSequence(
        model: IqosDeviceModel,
        whenHeatingStart: Boolean,
        whenStartingToUse: Boolean,
        whenPuffEnd: Boolean,
        whenManuallyTerminated: Boolean,
        whenChargeStart: Boolean?
    ): List<ByteArray> {
        val sequence = mutableListOf(
            buildVibrationSettings(whenHeatingStart, whenStartingToUse, whenPuffEnd, whenManuallyTerminated)
        )

        if (model.supportsChargeStartVibration() && whenChargeStart != null) {
            val allCoreOff = !whenHeatingStart && !whenStartingToUse && !whenPuffEnd && !whenManuallyTerminated
            if (whenChargeStart || !allCoreOff) {
                sequence.addAll(if (whenChargeStart) CHARGE_START_VIBRATION_ON else CHARGE_START_VIBRATION_OFF)
            }
        }
        return sequence
    }
}
