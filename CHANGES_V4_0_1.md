# v4.0.1 - رفع رگرسیون اتصال بلوتوث

## چه چیزی خراب شده بود
تو v4.0.0 برای برطرف کردن یک نظریه (OEM skins مثل MIUI که بدون location permission
اسکن BLE رو بلاک می‌کنن)، فلگ `android:usesPermissionFlags="neverForLocation"` از
روی `BLUETOOTH_SCAN` برداشته شد و `ACCESS_FINE_LOCATION` بدون `maxSdkVersion` اجباری شد.

این خودش یک باگ واقعی ساخت: روی Android 12+ (API 31+)، اگر `BLUETOOTH_SCAN` فلگ
`neverForLocation` را نداشته باشد، سیستم‌عامل **علاوه بر مجوز**، نیاز داره که
Location Services (GPS/مکان) هم در تنظیمات سیستم روشن باشه، وگرنه نتیجه‌ی اسکن
همیشه خالی برمی‌گرده - حتی اگر همه‌ی مجوزها Grant شده باشن. این دقیقاً همون چیزی
بود که باعث شد اتصال بلوتوث از کار بیفته.

## رفع شد
- `neverForLocation` دوباره به `BLUETOOTH_SCAN` برگشت (دقیقاً مثل کد اصلی‌ای که
  فرستادی).
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` دوباره `maxSdkVersion="30"`
  گرفتن - یعنی فقط روی اندروید ۱۱ و پایین‌تر درخواست می‌شن (جایی که واقعاً برای
  اسکن BLE لازمن)، نه روی ۱۲ به بالا.
- منطق `hasPermissions()` در `BleIqosTransport.kt` و `requestPermissionsAndConnect()`
  در `MainActivity.kt` دقیقاً به همون شرطی که تو کد اصلی داشتی برگشت: روی API 31+
  فقط `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` لازمه، location اصلاً گیت‌کننده‌ی
  اتصال نیست.

## چیزهایی که دست نخوردن (هنوز از v4.0.0 فعالن)
- POST_NOTIFICATIONS (رفع باگ نوتیفیکیشن)
- کیستور ثابت برای امضای پایدار بیلدها
- نوار پایین آیکونی + ۶ ویجت واقعی تب Devices
