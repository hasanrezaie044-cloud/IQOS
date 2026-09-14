package com.example.iqoscontroller

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Single source of truth for everything the device has actually told us.
 *
 * Nothing in here is invented or assumed: every field is null until a real frame (or a real GATT
 * read) fills it in, and the UI renders null as "نامشخص". That is what makes the difference
 * between the app showing device data and the app showing decoration.
 */
class DeviceSnapshot {

    // ---- link layer -----------------------------------------------------------------------
    var transport: IqosTransport.Type = IqosTransport.Type.NONE
    var state: DeviceState = DeviceState.DISCONNECTED
    var deviceName: String? = null
    var address: String? = null
    var bondState: String? = null
    var rssi: Int? = null
    var mtu: Int? = null
    var connectionStartedAt: Long? = null

    // ---- identity -------------------------------------------------------------------------
    var model: IqosDeviceModel = IqosDeviceModel.UNKNOWN
    var manufacturer: String? = null
    var modelNumber: String? = null
    var serialNumber: String? = null
    var hardwareRevision: String? = null
    var firmwareRevision: String? = null
    var softwareRevision: String? = null
    var stickFirmware: String? = null
    var holderFirmware: String? = null
    var stickProductNumber: String? = null
    var holderProductNumber: String? = null

    // ---- power ----------------------------------------------------------------------------
    var batteryPercent: Int? = null
    var batteryMillivolts: Int? = null
    var estimatedPercentFromVoltage: Int? = null

    // ---- usage ----------------------------------------------------------------------------
    var lifetimePuffCount: Int? = null
    var daysUsed: Int? = null

    // ---- settings read back from the device -----------------------------------------------
    var brightnessHigh: Boolean? = null
    var vibrateOnHeatingStart: Boolean? = null
    var vibrateOnStartingToUse: Boolean? = null
    var vibrateOnPuffEnd: Boolean? = null
    var vibrateOnManualStop: Boolean? = null
    var vibrateOnChargeStart: Boolean? = null
    var flexPuffEnabled: Boolean? = null
    var flexBatteryEco: Boolean? = null
    var pauseModeEnabled: Boolean? = null
    var autoStartEnabled: Boolean? = null
    var smartGestureEnabled: Boolean? = null

    // ---- protocol health ------------------------------------------------------------------
    var framesReceived: Int = 0
    var framesDecoded: Int = 0
    var framesUnknown: Int = 0
    var lastFrameHex: String? = null
    var lastUnknownHex: String? = null
    var lastUpdatedAt: Long? = null

    /** Extra characteristics discovered on the device that we could read but do not model. */
    val extraGattValues = LinkedHashMap<String, String>()

    /**
     * Applies a decoded frame. Returns true when the frame carried recognised device data, so
     * callers can distinguish "device answered" from "device sent something unreadable".
     */
    fun applyResponse(response: IqosResponse): Boolean {
        framesReceived++
        lastFrameHex = response.hex
        lastUpdatedAt = System.currentTimeMillis()

        when (response) {
            is IqosResponse.Firmware ->
                if (response.holder) holderFirmware = response.versionString()
                else stickFirmware = response.versionString()

            is IqosResponse.ProductNumber ->
                if (response.holder) holderProductNumber = response.value
                else stickProductNumber = response.value

            is IqosResponse.Telemetry -> {
                response.puffCount?.let { lifetimePuffCount = it }
                response.daysUsed?.let { daysUsed = it }
            }

            is IqosResponse.Timestamp -> daysUsed = response.daysUsed

            is IqosResponse.BatteryVoltage -> {
                batteryMillivolts = response.millivolts
                estimatedPercentFromVoltage = IqosResponse.estimatePercentFromVoltage(response.volts)
            }

            is IqosResponse.BatteryLevel -> batteryPercent = response.percent

            is IqosResponse.Brightness -> brightnessHigh = response.high

            is IqosResponse.VibrationFlags -> {
                vibrateOnHeatingStart = response.whenHeatingStart
                vibrateOnStartingToUse = response.whenStartingToUse
                vibrateOnPuffEnd = response.whenPuffEnd
                vibrateOnManualStop = response.whenManuallyTerminated
            }

            is IqosResponse.ChargeStartVibration -> vibrateOnChargeStart = response.enabled

            is IqosResponse.FlexPuff -> flexPuffEnabled = response.enabled

            is IqosResponse.FlexBattery -> flexBatteryEco = response.eco

            is IqosResponse.SettingState -> when (response.settingId) {
                IqosResponse.SETTING_AUTO_START -> autoStartEnabled = response.enabled
                IqosResponse.SETTING_PAUSE_MODE -> pauseModeEnabled = response.enabled
                IqosResponse.SETTING_SMART_GESTURE -> smartGestureEnabled = response.enabled
                else -> {
                    framesUnknown++
                    lastUnknownHex = response.hex
                    return false
                }
            }

            is IqosResponse.Unknown -> {
                framesUnknown++
                lastUnknownHex = response.hex
                return false
            }
        }

        framesDecoded++
        return true
    }

    fun batteryVolts(): Float? = batteryMillivolts?.let { it / 1000.0f }

    /** Percentage to show: the device's own value first, the voltage estimate as a fallback. */
    fun displayPercent(): Int? = batteryPercent ?: estimatedPercentFromVoltage

    fun signalQualityFa(): String? {
        val value = rssi ?: return null
        val quality = when {
            value >= -55 -> "بسیار خوب"
            value >= -67 -> "خوب"
            value >= -80 -> "متوسط"
            value >= -90 -> "ضعیف"
            else -> "بسیار ضعیف"
        }
        return "$value dBm ($quality)"
    }

    fun connectedForFa(): String? {
        val start = connectionStartedAt ?: return null
        val seconds = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        val minutes = seconds / 60
        return if (minutes > 0) "$minutes دقیقه و ${seconds % 60} ثانیه" else "$seconds ثانیه"
    }

    private fun boolFa(value: Boolean?): String = when (value) {
        true -> "روشن"
        false -> "خاموش"
        null -> "نامشخص"
    }

    private fun textFa(value: String?): String = value?.takeIf { it.isNotBlank() } ?: "نامشخص"

    /** Full technical report, ready to render as a table or copy to the clipboard. */
    fun toReportRows(): List<Pair<String, String>> {
        val rows = mutableListOf<Pair<String, String>>()

        rows += "— اتصال —" to ""
        rows += "وضعیت" to state.displayNameFa
        rows += "نوع اتصال" to transport.displayName
        rows += "نام دستگاه" to textFa(deviceName)
        rows += "آدرس بلوتوث" to textFa(address)
        rows += "وضعیت جفت‌سازی" to textFa(bondState)
        rows += "قدرت سیگنال" to textFa(signalQualityFa())
        rows += "اندازه بسته (MTU)" to (mtu?.let { "$it بایت" } ?: "نامشخص")
        rows += "مدت اتصال" to textFa(connectedForFa())

        rows += "— شناسه دستگاه —" to ""
        rows += "مدل شناسایی‌شده" to model.displayName
        rows += "سازنده" to textFa(manufacturer)
        rows += "شماره مدل (GATT)" to textFa(modelNumber)
        rows += "شماره سریال" to textFa(serialNumber)
        rows += "نسخه سخت‌افزار" to textFa(hardwareRevision)
        rows += "نسخه فریم‌ور (GATT)" to textFa(firmwareRevision)
        rows += "نسخه نرم‌افزار (GATT)" to textFa(softwareRevision)
        rows += "فریم‌ور بدنه (Stick)" to textFa(stickFirmware)
        rows += "فریم‌ور هولدر" to textFa(holderFirmware)
        rows += "شماره محصول بدنه" to textFa(stickProductNumber)
        rows += "شماره محصول هولدر" to textFa(holderProductNumber)

        rows += "— باتری —" to ""
        rows += "درصد شارژ (اعلام دستگاه)" to (batteryPercent?.let { "$it٪" } ?: "نامشخص")
        rows += "ولتاژ سل باتری" to (batteryMillivolts?.let { "%.3f ولت (%d میلی‌ولت)".format(it / 1000.0f, it) } ?: "نامشخص")
        rows += "درصد تخمینی از ولتاژ" to (estimatedPercentFromVoltage?.let { "$it٪" } ?: "نامشخص")

        rows += "— مصرف —" to ""
        rows += "مجموع پاف عمر دستگاه" to (lifetimePuffCount?.let { "$it" } ?: "نامشخص")
        rows += "روزهای در سرویس" to (daysUsed?.let { "$it روز" } ?: "نامشخص")

        rows += "— تنظیمات خوانده‌شده از دستگاه —" to ""
        rows += "روشنایی LED" to when (brightnessHigh) {
            true -> "زیاد (۱۰۰)"
            false -> "کم (۳۰)"
            null -> "نامشخص"
        }
        rows += "لرزش در شروع گرم شدن" to boolFa(vibrateOnHeatingStart)
        rows += "لرزش در آماده شدن برای مصرف" to boolFa(vibrateOnStartingToUse)
        rows += "لرزش نزدیک پایان پاف" to boolFa(vibrateOnPuffEnd)
        rows += "لرزش در خاتمه دستی" to boolFa(vibrateOnManualStop)
        rows += "لرزش در شروع شارژ" to boolFa(vibrateOnChargeStart)
        rows += "فلکس‌پاف" to boolFa(flexPuffEnabled)
        rows += "حالت فلکس‌باتری" to when (flexBatteryEco) {
            true -> "اکو (Eco)"
            false -> "پرفورمنس (Performance)"
            null -> "نامشخص"
        }
        rows += "حالت توقف" to boolFa(pauseModeEnabled)
        rows += "روشن شدن خودکار" to boolFa(autoStartEnabled)
        rows += "ژست هوشمند" to boolFa(smartGestureEnabled)

        if (extraGattValues.isNotEmpty()) {
            rows += "— مشخصه‌های دیگر دستگاه —" to ""
            extraGattValues.forEach { (key, value) -> rows += key to value }
        }

        rows += "— سلامت پروتکل —" to ""
        rows += "فریم‌های دریافتی" to "$framesReceived"
        rows += "فریم‌های رمزگشایی‌شده" to "$framesDecoded"
        rows += "فریم‌های ناشناخته" to "$framesUnknown"
        rows += "آخرین فریم" to textFa(lastFrameHex)
        rows += "آخرین فریم ناشناخته" to textFa(lastUnknownHex)
        rows += "آخرین بروزرسانی" to (lastUpdatedAt?.let {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it))
        } ?: "نامشخص")

        return rows
    }

    /** Which features this model is documented to have, for the capability panel. */
    fun capabilityRows(): List<Pair<String, String>> = IqosCapability.values().map { capability ->
        capability.labelFa to if (model.supports(capability)) "پشتیبانی می‌شود" else
            if (model == IqosDeviceModel.UNKNOWN) "نامشخص (مدل شناسایی نشد)" else "در این مدل وجود ندارد"
    }

    fun toPlainText(): String = buildString {
        append("گزارش فنی دستگاه IQOS\n")
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        append("\n\n")
        toReportRows().forEach { (key, value) ->
            if (value.isEmpty()) append("\n$key\n") else append("$key: $value\n")
        }
        append("\nقابلیت‌های مدل\n")
        capabilityRows().forEach { (key, value) -> append("$key: $value\n") }
    }

    fun resetLinkState() {
        rssi = null
        mtu = null
        connectionStartedAt = null
        bondState = null
    }
}
