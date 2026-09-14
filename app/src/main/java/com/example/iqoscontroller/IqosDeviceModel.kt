package com.example.iqoscontroller

/**
 * Known IQOS models and the capability matrix taken from the public reference implementation.
 *
 * This exists so the app can tell the difference between "the command failed" and "this model
 * physically does not have that feature" - previously every switch looked equally real, which is
 * what made the UI feel fake.
 */
enum class IqosDeviceModel(val displayName: String, val displayNameFa: String) {
    ILUMA_ONE("IQOS ILUMA ONE", "ایلوما وان"),
    ILUMA("IQOS ILUMA", "ایلوما"),
    ILUMA_PRIME("IQOS ILUMA PRIME", "ایلوما پرایم"),
    ILUMA_I_ONE("IQOS ILUMA i ONE", "ایلوما آی وان"),
    ILUMA_I("IQOS ILUMA i", "ایلوما آی"),
    ILUMA_I_PRIME("IQOS ILUMA i PRIME", "ایلوما آی پرایم"),
    UNKNOWN("IQOS (نامشخص)", "نامشخص");

    fun isOneFormFactor(): Boolean = this == ILUMA_ONE || this == ILUMA_I_ONE

    fun isIlumaFamily(): Boolean = this == ILUMA_ONE || this == ILUMA || this == ILUMA_PRIME

    fun isIlumaIFamily(): Boolean = this == ILUMA_I_ONE || this == ILUMA_I || this == ILUMA_I_PRIME

    fun supportsHolderFeatures(): Boolean =
        this == ILUMA || this == ILUMA_PRIME || this == ILUMA_I || this == ILUMA_I_PRIME

    fun supports(capability: IqosCapability): Boolean = when (capability) {
        IqosCapability.BRIGHTNESS,
        IqosCapability.VIBRATION,
        IqosCapability.DEVICE_LOCK -> this != UNKNOWN

        IqosCapability.FLEX_PUFF,
        IqosCapability.FLEX_BATTERY -> this == ILUMA_I || this == ILUMA_I_PRIME

        IqosCapability.SMART_GESTURE -> this == ILUMA || this == ILUMA_PRIME || isIlumaIFamily()

        IqosCapability.AUTO_START -> isIlumaIFamily()

        IqosCapability.CHARGE_START_VIBRATION -> supportsHolderFeatures()

        IqosCapability.PAUSE_MODE -> this == ILUMA_I || this == ILUMA_I_PRIME
    }

    fun supportsChargeStartVibration(): Boolean = supports(IqosCapability.CHARGE_START_VIBRATION)

    companion object {
        /** Infers the model from the BLE advertised name / GATT model-number string. */
        fun fromName(value: String?): IqosDeviceModel {
            val normalized = (value ?: "").trim().uppercase().replace("-", " ").replace("_", " ")
            return when {
                normalized.contains("ILUMA I PRIME") -> ILUMA_I_PRIME
                normalized.contains("ILUMA I ONE") -> ILUMA_I_ONE
                normalized.contains("ILUMA I") -> ILUMA_I
                normalized.contains("ILUMA PRIME") -> ILUMA_PRIME
                normalized.contains("ILUMA ONE") -> ILUMA_ONE
                normalized.contains("ILUMA") -> ILUMA
                else -> UNKNOWN
            }
        }
    }
}

/** Device-level feature flags, mirroring the reference capability enum. */
enum class IqosCapability(val labelFa: String) {
    BRIGHTNESS("روشنایی LED"),
    VIBRATION("بازخورد لرزشی"),
    FLEX_PUFF("فلکس‌پاف"),
    FLEX_BATTERY("فلکس‌باتری"),
    SMART_GESTURE("ژست هوشمند"),
    AUTO_START("روشن شدن خودکار"),
    DEVICE_LOCK("قفل دستگاه"),
    CHARGE_START_VIBRATION("لرزش شروع شارژ"),
    PAUSE_MODE("حالت توقف")
}
