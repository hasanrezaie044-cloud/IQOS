package com.example.iqoscontroller

/**
 * Builds every supported device operation as a [VerifiedWrite]: the frames to send, the frames to
 * read back, and the rule that decides whether the device actually accepted the change.
 */
object IqosOperations {

    fun smartGesture(enabled: Boolean) = VerifiedWrite(
        tag = "SmartGesture",
        labelFa = "ژست هوشمند",
        packets = listOf(if (enabled) IqosCommands.SMARTGESTURE_ENABLE else IqosCommands.SMARTGESTURE_DISABLE),
        verifyPackets = listOf(IqosCommands.LOAD_SMARTGESTURE),
        matcher = { response ->
            if (response is IqosResponse.SettingState && response.settingId == IqosResponse.SETTING_SMART_GESTURE)
                response.enabled == enabled else null
        }
    )

    fun autoStart(enabled: Boolean) = VerifiedWrite(
        tag = "AutoStart",
        labelFa = "روشن شدن خودکار",
        packets = listOf(if (enabled) IqosCommands.AUTOSTART_ENABLE else IqosCommands.AUTOSTART_DISABLE),
        verifyPackets = listOf(IqosCommands.LOAD_AUTOSTART),
        matcher = { response ->
            if (response is IqosResponse.SettingState && response.settingId == IqosResponse.SETTING_AUTO_START)
                response.enabled == enabled else null
        }
    )

    fun pauseMode(enabled: Boolean) = VerifiedWrite(
        tag = "PauseMode",
        labelFa = "حالت توقف",
        packets = listOf(if (enabled) IqosCommands.PAUSEMODE_ENABLE else IqosCommands.PAUSEMODE_DISABLE),
        verifyPackets = listOf(IqosCommands.LOAD_PAUSEMODE),
        matcher = { response ->
            if (response is IqosResponse.SettingState && response.settingId == IqosResponse.SETTING_PAUSE_MODE)
                response.enabled == enabled else null
        }
    )

    fun flexPuff(enabled: Boolean) = VerifiedWrite(
        tag = "FlexPuff",
        labelFa = "فلکس‌پاف",
        packets = listOf(if (enabled) IqosCommands.FLEXPUFF_ENABLE else IqosCommands.FLEXPUFF_DISABLE),
        verifyPackets = listOf(IqosCommands.LOAD_FLEXPUFF),
        matcher = { response ->
            if (response is IqosResponse.FlexPuff) response.enabled == enabled else null
        },
        // The published disable frame carries the same checksum as the enable frame, so if the
        // device ignores it we retry once with the checksum-corrected frame (same payload).
        retryPackets = if (enabled) null else listOf(IqosCommands.FLEXPUFF_DISABLE_CHECKSUM_FIX),
        retryNoteFa = "تلاش دوم با چک‌سام اصلاح‌شده"
    )

    fun brightness(high: Boolean) = VerifiedWrite(
        tag = "Brightness",
        labelFa = "روشنایی LED",
        packets = if (high) IqosCommands.SET_BRIGHTNESS_HIGH else IqosCommands.SET_BRIGHTNESS_LOW,
        verifyPackets = listOf(IqosCommands.LOAD_BRIGHTNESS),
        matcher = { response ->
            if (response is IqosResponse.Brightness) response.high == high else null
        }
    )

    fun flexBattery(eco: Boolean) = VerifiedWrite(
        tag = "FlexBattery",
        labelFa = "حالت فلکس‌باتری",
        // The reference requires the set frame to be followed by the load frame to complete the
        // write sequence, so the load frame is part of the write itself here.
        packets = listOf(
            if (eco) IqosCommands.FLEXBATTERY_ECO_SET else IqosCommands.FLEXBATTERY_PERFORMANCE_SET,
            IqosCommands.LOAD_FLEXBATTERY
        ),
        verifyPackets = listOf(IqosCommands.LOAD_FLEXBATTERY),
        matcher = { response ->
            if (response is IqosResponse.FlexBattery) response.eco == eco else null
        }
    )

    fun deviceLock(locked: Boolean) = VerifiedWrite(
        tag = "DeviceLock",
        labelFa = if (locked) "قفل دستگاه" else "بازکردن قفل دستگاه",
        packets = if (locked) IqosCommands.LOCK_SEQUENCE else IqosCommands.UNLOCK_SEQUENCE
        // No lock-state read frame is published anywhere, so this one is intentionally
        // unverifiable and reported as such instead of claiming success.
    )

    fun findMyDevice(start: Boolean) = VerifiedWrite(
        tag = "FindMyDevice",
        labelFa = if (start) "لرزش برای پیدا کردن دستگاه" else "توقف لرزش",
        packets = listOf(if (start) IqosCommands.START_VIBRATE else IqosCommands.STOP_VIBRATE)
        // A physical burst has no state to read back; the user hears/feels the result.
    )

    fun vibrationSettings(
        model: IqosDeviceModel,
        whenHeatingStart: Boolean,
        whenStartingToUse: Boolean,
        whenPuffEnd: Boolean,
        whenManuallyTerminated: Boolean,
        whenChargeStart: Boolean?
    ): VerifiedWrite {
        val verify = mutableListOf(IqosCommands.LOAD_VIBRATION_SETTINGS)
        if (model.supportsChargeStartVibration()) verify += IqosCommands.LOAD_VIBRATE_CHARGE_START

        return VerifiedWrite(
            tag = "VibrationSettings",
            labelFa = "تنظیمات بازخورد لرزشی",
            packets = IqosCommands.buildVibrationUpdateSequence(
                model, whenHeatingStart, whenStartingToUse, whenPuffEnd, whenManuallyTerminated, whenChargeStart
            ),
            verifyPackets = verify,
            matcher = { response ->
                if (response is IqosResponse.VibrationFlags) {
                    response.whenHeatingStart == whenHeatingStart &&
                        response.whenStartingToUse == whenStartingToUse &&
                        response.whenPuffEnd == whenPuffEnd &&
                        response.whenManuallyTerminated == whenManuallyTerminated
                } else null
            }
        )
    }

    /** Capability gate for a UI action. Returns null when the action is allowed. */
    fun blockingCapability(model: IqosDeviceModel, capability: IqosCapability): IqosCapability? =
        if (model == IqosDeviceModel.UNKNOWN || model.supports(capability)) null else capability
}
