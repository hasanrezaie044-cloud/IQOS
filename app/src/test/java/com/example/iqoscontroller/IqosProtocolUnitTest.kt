package com.example.iqoscontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-layer tests that run on the JVM with no device and no Android framework.
 *
 * The frames and expected values are the reference implementation's own published test vectors,
 * so `./gradlew test` proves the decoder agrees with the documented protocol before the app ever
 * touches real hardware.
 */
class IqosProtocolUnitTest {

    private fun frame(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    // ---------------------------------------------------------------------------- diagnosis

    @Test
    fun decodesBatteryVoltageFrame() {
        val response = IqosResponse.decode(frame(0x00, 0x08, 0x88, 0x21, 0x00, 0xA8, 0x10, 0x00, 0x00))
        assertTrue(response is IqosResponse.BatteryVoltage)
        assertEquals(4264, (response as IqosResponse.BatteryVoltage).millivolts)
        assertEquals(4.264f, response.volts, 0.0001f)
    }

    @Test
    fun decodesTimestampFrame() {
        val response = IqosResponse.decode(frame(0x00, 0x08, 0x80, 0x02, 0x1E, 0x00, 0x00, 0x00))
        assertEquals(30, (response as IqosResponse.Timestamp).daysUsed)
    }

    @Test
    fun decodesTelemetryBlocks() {
        val bytes = mutableListOf(0x00, 0x08, 0x90, 0x22, 0x01, 0x01)
        bytes += listOf(0, 0, 0, 0, 0x2A, 0x01, 0, 0x8E) // 298 puffs
        bytes += listOf(0, 0, 0, 0, 0x1E, 0x00, 0, 0x17) // 30 days
        bytes += listOf(0, 0, 0, 0, 0x00, 0x00, 0, 0x00)
        bytes += listOf(0, 0, 0, 0, 0x00, 0x00, 0, 0x00)

        val response = IqosResponse.decode(frame(*bytes.toIntArray())) as IqosResponse.Telemetry
        assertEquals(298, response.puffCount)
        assertEquals(30, response.daysUsed)
    }

    @Test
    fun rejectsTelemetryWithWrongMarker() {
        val bytes = IntArray(38) { 0 }
        bytes[1] = 0x08; bytes[2] = 0x90; bytes[3] = 0x22
        assertTrue(IqosResponse.decode(frame(*bytes)) is IqosResponse.Unknown)
    }

    // ---------------------------------------------------------------------------- identity

    @Test
    fun decodesStickAndHolderFirmware() {
        val stick = IqosResponse.decode(
            frame(0x00, 0xC0, 0x88, 0x00, 0x00, 0x00, 0x02, 0x05, 0x07, 0x18)
        ) as IqosResponse.Firmware
        assertFalse(stick.holder)
        assertEquals("v2.5.7.24", stick.versionString())

        val holder = IqosResponse.decode(
            frame(0x00, 0x08, 0x88, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x19)
        ) as IqosResponse.Firmware
        assertTrue(holder.holder)
        assertEquals("v1.2.3.25", holder.versionString())
    }

    @Test
    fun decodesProductNumbers() {
        val stickBytes = frame(0x00, 0xC0, 0x88, 0x03) + "STICK123456".toByteArray() + frame(0xAA)
        val stick = IqosResponse.decode(stickBytes) as IqosResponse.ProductNumber
        assertEquals("STICK123456", stick.value)

        val holderBytes = frame(0x00, 0x08, 0x88, 0x03) + "HOLDER123456".toByteArray()
        val holder = IqosResponse.decode(holderBytes) as IqosResponse.ProductNumber
        assertEquals("HOLDER123456", holder.value)
    }

    // ---------------------------------------------------------------------------- settings

    @Test
    fun decodesBrightnessLevels() {
        assertTrue((IqosResponse.decode(frame(0x00, 0xC0, 0x86, 0x23, 0x64, 0, 0, 0, 0)) as IqosResponse.Brightness).high)
        assertFalse((IqosResponse.decode(frame(0x00, 0xC0, 0x86, 0x23, 0x1E, 0, 0, 0, 0)) as IqosResponse.Brightness).high)
        assertTrue(IqosResponse.decode(frame(0x00, 0xC0, 0x86, 0x23, 0xFF, 0, 0, 0, 0)) is IqosResponse.Unknown)
    }

    @Test
    fun decodesVibrationFlags() {
        val basic = IqosResponse.decode(
            frame(0x00, 0x08, 0x84, 0x23, 0x10, 0x00, 0x01, 0x10, 0x77)
        ) as IqosResponse.VibrationFlags
        assertTrue(basic.whenHeatingStart)
        assertFalse(basic.whenStartingToUse)
        assertFalse(basic.whenPuffEnd)
        assertTrue(basic.whenManuallyTerminated)

        val alt = IqosResponse.decode(
            frame(0x00, 0x08, 0x84, 0x23, 0x03, 0x00, 0x10, 0x01, 0x77)
        ) as IqosResponse.VibrationFlags
        assertFalse(alt.whenHeatingStart)
        assertTrue(alt.whenStartingToUse)
        assertTrue(alt.whenPuffEnd)
        assertFalse(alt.whenManuallyTerminated)
    }

    @Test
    fun decodesChargeStartVibration() {
        val on = frame(0x00, 0x08, 0x8B, 0x04, 0x04, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x56)
        val off = frame(0x00, 0x08, 0x8B, 0x04, 0x04, 0, 0, 0, 0, 0x09, 0, 0, 0, 0, 0, 0, 0, 0, 0xEE)
        assertTrue((IqosResponse.decode(on) as IqosResponse.ChargeStartVibration).enabled)
        assertFalse((IqosResponse.decode(off) as IqosResponse.ChargeStartVibration).enabled)
    }

    @Test
    fun decodesFlexPuffAndFlexBattery() {
        assertTrue((IqosResponse.decode(frame(0x00, 0x90, 0x85, 0x22, 0x03, 0x01, 0, 0, 0)) as IqosResponse.FlexPuff).enabled)
        assertFalse((IqosResponse.decode(frame(0x00, 0x90, 0x85, 0x22, 0x03, 0x00, 0, 0, 0)) as IqosResponse.FlexPuff).enabled)
        assertTrue((IqosResponse.decode(frame(0x00, 0x08, 0x84, 0x25, 0x01, 0, 0, 0, 0)) as IqosResponse.FlexBattery).eco)
        assertFalse((IqosResponse.decode(frame(0x00, 0x08, 0x84, 0x25, 0x00, 0, 0, 0, 0)) as IqosResponse.FlexBattery).eco)
    }

    @Test
    fun decodesOnOffSettingReplies() {
        val autoStart = IqosResponse.decode(frame(0x00, 0x08, 0x87, 0x24, 0x01, 0x01, 0, 0, 0xA5)) as IqosResponse.SettingState
        assertEquals(IqosResponse.SETTING_AUTO_START, autoStart.settingId)
        assertTrue(autoStart.enabled)

        val pause = IqosResponse.decode(frame(0x00, 0x08, 0x87, 0x24, 0x02, 0x00, 0, 0, 0)) as IqosResponse.SettingState
        assertEquals(IqosResponse.SETTING_PAUSE_MODE, pause.settingId)
        assertFalse(pause.enabled)

        assertTrue(IqosResponse.decode(frame(0x00, 0x08, 0x87, 0x24, 0x01, 0xFF, 0, 0, 0)) is IqosResponse.Unknown)
    }

    @Test
    fun rejectsShortAndUnknownFrames() {
        assertTrue(IqosResponse.decode(frame(0x00, 0x08, 0x88)) is IqosResponse.Unknown)
        assertTrue(IqosResponse.decode(frame(0x00, 0x08, 0xFF, 0xFF, 0, 0, 0, 0)) is IqosResponse.Unknown)
        assertTrue(IqosResponse.decode(frame(0x00, 0x08, 0x88, 0x21, 0x00, 0xA8)) is IqosResponse.Unknown)
    }

    // ---------------------------------------------------------------------------- battery char

    @Test
    fun decodesBatteryCharacteristic() {
        val iqosFrame = IqosResponse.decodeBatteryCharacteristic(frame(0x0F, 0x02, 0x5A, 0, 0, 0, 0))
        assertEquals(90, (iqosFrame as IqosResponse.BatteryLevel).percent)

        val standard = IqosResponse.decodeBatteryCharacteristic(frame(0x4B))
        assertEquals(75, (standard as IqosResponse.BatteryLevel).percent)
    }

    // ---------------------------------------------------------------------------- commands

    @Test
    fun buildsVibrationSettingsWithCorrectChecksum() {
        assertArrayEqualsHex(
            frame(0x00, 0xC9, 0x44, 0x23, 0x10, 0x00, 0x01, 0x01, 0x65),
            IqosCommands.buildVibrationSettings(
                whenHeatingStart = true, whenStartingToUse = false,
                whenPuffEnd = true, whenManuallyTerminated = false
            )
        )
        assertArrayEqualsHex(
            frame(0x00, 0xC9, 0x44, 0x23, 0x10, 0x00, 0x01, 0x00, 0x62),
            IqosCommands.buildVibrationSettings(
                whenHeatingStart = true, whenStartingToUse = false,
                whenPuffEnd = false, whenManuallyTerminated = false
            )
        )
        assertEquals(0x77, IqosCommands.vibrationChecksum(0))
    }

    @Test
    fun publishedCommandFramesAreUnchanged() {
        assertArrayEqualsHex(frame(0x00, 0xC9, 0x10, 0x02, 0x01, 0x01, 0x75, 0xD6), IqosCommands.LOAD_TELEMETRY)
        assertArrayEqualsHex(frame(0x00, 0xC0, 0x00, 0x21, 0xE7), IqosCommands.LOAD_BATTERY_VOLTAGE)
        assertArrayEqualsHex(frame(0x00, 0xC9, 0x00, 0x23, 0xE9), IqosCommands.LOAD_VIBRATION_SETTINGS)
        assertArrayEqualsHex(frame(0x00, 0xC9, 0x44, 0x25, 0x01, 0x00, 0x00, 0x00, 0x4D), IqosCommands.FLEXBATTERY_ECO_SET)
        assertArrayEqualsHex(frame(0x00, 0xC0, 0x45, 0x22, 0x01, 0x1E, 0x00, 0x00, 0xC3), IqosCommands.START_VIBRATE)
        assertEquals(3, IqosCommands.LOCK_SEQUENCE.size)
        assertEquals(3, IqosCommands.SET_BRIGHTNESS_HIGH.size)
        assertEquals(7, IqosCommands.CHARGE_START_VIBRATION_ON.size)
    }

    @Test
    fun vibrationSequenceSkipsHolderFramesWhenEverythingIsOff() {
        val oneFormFactor = IqosCommands.buildVibrationUpdateSequence(
            IqosDeviceModel.ILUMA_I_ONE, false, false, false, false, null
        )
        assertEquals(1, oneFormFactor.size)

        val holderAllOff = IqosCommands.buildVibrationUpdateSequence(
            IqosDeviceModel.ILUMA_I_PRIME, false, false, false, false, false
        )
        assertEquals(1, holderAllOff.size)

        val holderChargeOn = IqosCommands.buildVibrationUpdateSequence(
            IqosDeviceModel.ILUMA_I_PRIME, true, false, false, false, true
        )
        assertEquals(8, holderChargeOn.size)
    }

    // ---------------------------------------------------------------------------- model matrix

    @Test
    fun detectsModelsFromAdvertisedName() {
        assertEquals(IqosDeviceModel.ILUMA_I_PRIME, IqosDeviceModel.fromName("IQOS ILUMA i PRIME"))
        assertEquals(IqosDeviceModel.ILUMA_PRIME, IqosDeviceModel.fromName("iqos iluma prime"))
        assertEquals(IqosDeviceModel.ILUMA_I_ONE, IqosDeviceModel.fromName("ILUMA-i-ONE"))
        assertEquals(IqosDeviceModel.ILUMA, IqosDeviceModel.fromName("ILUMA"))
        assertEquals(IqosDeviceModel.UNKNOWN, IqosDeviceModel.fromName("Galaxy Buds"))
        assertEquals(IqosDeviceModel.UNKNOWN, IqosDeviceModel.fromName(null))
    }

    @Test
    fun capabilityMatrixMatchesReference() {
        assertTrue(IqosDeviceModel.ILUMA_I_PRIME.supports(IqosCapability.FLEX_PUFF))
        assertFalse(IqosDeviceModel.ILUMA_PRIME.supports(IqosCapability.FLEX_PUFF))
        assertTrue(IqosDeviceModel.ILUMA_PRIME.supports(IqosCapability.SMART_GESTURE))
        assertFalse(IqosDeviceModel.ILUMA_ONE.supports(IqosCapability.SMART_GESTURE))
        assertTrue(IqosDeviceModel.ILUMA_I.supports(IqosCapability.AUTO_START))
        assertFalse(IqosDeviceModel.ILUMA.supports(IqosCapability.AUTO_START))
        assertTrue(IqosDeviceModel.ILUMA_I_PRIME.supportsChargeStartVibration())
        assertFalse(IqosDeviceModel.ILUMA_I_ONE.supportsChargeStartVibration())
        assertFalse(IqosDeviceModel.UNKNOWN.supports(IqosCapability.BRIGHTNESS))
    }

    // ---------------------------------------------------------------------------- snapshot

    @Test
    fun snapshotCollectsEveryFrameType() {
        val snapshot = DeviceSnapshot()
        assertTrue(snapshot.applyResponse(IqosResponse.decode(frame(0x00, 0x08, 0x88, 0x21, 0x00, 0xA8, 0x10, 0x00, 0x00))))
        assertTrue(snapshot.applyResponse(IqosResponse.decode(frame(0x00, 0x08, 0x80, 0x02, 0x0A, 0x00, 0x00, 0x00))))
        assertTrue(snapshot.applyResponse(IqosResponse.decode(frame(0x00, 0xC0, 0x86, 0x23, 0x64, 0, 0, 0, 0))))
        assertFalse(snapshot.applyResponse(IqosResponse.decode(frame(0x00, 0x08, 0xFF, 0xFF, 0, 0, 0, 0))))

        assertEquals(4264, snapshot.batteryMillivolts)
        assertEquals(10, snapshot.daysUsed)
        assertEquals(true, snapshot.brightnessHigh)
        assertEquals(1, snapshot.framesUnknown)
        assertEquals(3, snapshot.framesDecoded)
        assertTrue(snapshot.toReportRows().isNotEmpty())
        assertTrue(snapshot.toPlainText().isNotEmpty())
    }

    @Test
    fun voltageEstimateStaysInRange() {
        assertEquals(0, IqosResponse.estimatePercentFromVoltage(3.0f))
        assertEquals(100, IqosResponse.estimatePercentFromVoltage(4.30f))
        val mid = IqosResponse.estimatePercentFromVoltage(3.83f)
        assertTrue(mid in 45..55)
    }

    @Test
    fun operationsCarryVerificationRules() {
        val flexPuffOff = IqosOperations.flexPuff(false)
        assertTrue(flexPuffOff.verifyPackets.isNotEmpty())
        assertTrue(flexPuffOff.retryPackets != null)
        assertEquals(true, flexPuffOff.matcher?.invoke(IqosResponse.FlexPuff(enabled = false, hex = "")))
        assertEquals(false, flexPuffOff.matcher?.invoke(IqosResponse.FlexPuff(enabled = true, hex = "")))
        assertEquals(null, flexPuffOff.matcher?.invoke(IqosResponse.Unknown("", "")))

        // Lock has no published read-back, so it must not pretend to be verifiable.
        assertTrue(IqosOperations.deviceLock(true).matcher == null)
        assertTrue(IqosOperations.findMyDevice(true).matcher == null)
    }

    private fun assertArrayEqualsHex(expected: ByteArray, actual: ByteArray) {
        assertEquals(IqosProtocol.toHexString(expected), IqosProtocol.toHexString(actual))
    }
}
