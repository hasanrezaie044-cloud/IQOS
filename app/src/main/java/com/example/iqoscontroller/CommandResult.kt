package com.example.iqoscontroller

/**
 * Outcome of a command that was sent to the hardware.
 *
 * The old UI reported success as soon as bytes were handed to the Bluetooth stack, which is why
 * switches appeared to work while the device ignored them. Every command now ends in one of these
 * states, and the UI shows exactly which one.
 */
data class CommandResult(
    val tag: String,
    val labelFa: String,
    val status: Status,
    val detailFa: String
) {
    enum class Status {
        /** Written to the device, but this feature has no documented read-back to confirm it. */
        SENT_UNVERIFIABLE,

        /** Written and the device read-back confirmed the new value. */
        CONFIRMED,

        /** Written, device answered, but the value did not change - the device refused it. */
        REJECTED,

        /** Written, but the device never answered the read-back within the timeout. */
        TIMEOUT,

        /** Not sent: this model does not have the feature. */
        UNSUPPORTED,

        /** Not sent: nothing is connected. */
        NOT_CONNECTED,

        /** Not sent: the frame needed for this action is not published anywhere. */
        NOT_IMPLEMENTABLE
    }

    val isSuccess: Boolean get() = status == Status.CONFIRMED || status == Status.SENT_UNVERIFIABLE

    fun messageFa(): String = when (status) {
        Status.CONFIRMED -> "$labelFa: تأیید شد توسط دستگاه ✓"
        Status.SENT_UNVERIFIABLE -> "$labelFa: ارسال شد (این قابلیت فرمان خواندن وضعیت ندارد)"
        Status.REJECTED -> "$labelFa: دستگاه فرمان را نپذیرفت"
        Status.TIMEOUT -> "$labelFa: دستگاه پاسخی نداد"
        Status.UNSUPPORTED -> "$labelFa: این مدل از این قابلیت پشتیبانی نمی‌کند"
        Status.NOT_CONNECTED -> "$labelFa: دستگاه متصل نیست"
        Status.NOT_IMPLEMENTABLE -> "$labelFa: فرمان معتبری برای این کار وجود ندارد"
    }
}

/**
 * A write plus the read-back needed to prove it landed.
 *
 * [matcher] receives every decoded frame while the write is pending and returns:
 *   null  - irrelevant frame, keep waiting
 *   true  - device now reports the requested value
 *   false - device answered with a different value (rejected)
 */
class VerifiedWrite(
    val tag: String,
    val labelFa: String,
    val packets: List<ByteArray>,
    val verifyPackets: List<ByteArray> = emptyList(),
    val matcher: ((IqosResponse) -> Boolean?)? = null,
    val retryPackets: List<ByteArray>? = null,
    val retryNoteFa: String? = null
)
