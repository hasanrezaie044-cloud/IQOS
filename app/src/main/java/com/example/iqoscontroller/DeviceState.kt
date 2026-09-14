package com.example.iqoscontroller

/**
 * Strict device state representation reflecting actual BLE lifecycle.
 */
enum class DeviceState(val displayNameFa: String) {
    DISCONNECTED("قطع ارتباط"),
    SCANNING("در حال جستجوی دستگاه…"),
    CONNECTING("در حال اتصال…"),
    CONNECTED("متصل شد"),
    DISCOVERING_SERVICES("در حال بررسی سرویس‌ها…"),
    READY("آماده به کار"),
    BUSY("دستگاه مشغول است"),
    ERROR("خطا در ارتباط")
}
