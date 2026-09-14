# IQOS ILUMA i PRIME Controller

Offline Android app built with Kotlin to communicate with IQOS ILUMA i PRIME via Bluetooth Low Energy (BLE).

## GitHub Actions Build
This repository includes `.github/workflows/build.yml` which automatically compiles `app-debug.apk` upon push or pull request using JDK 17 and Gradle 8.5.

## v5.0.0
- کل ارتباط با دستگاه بازنویسی شد: مشخصه فرمان در تمام سرویس‌ها جستجو می‌شود، هر فرمان با خواندن مجدد از دستگاه تأیید می‌شود، و گزارش فنی کامل دستگاه (فریم‌ور، سریال، ولتاژ، پاف، تنظیمات) نمایش داده می‌شود.
- جزئیات کامل در `CHANGES_V5_0_0.md`.
- تست‌های واحد پروتکل: `gradle testDebugUnitTest`
