# UPI QR Quick Settings Overlay Utility (Android)

Specialized background Android utility app with:
1. No launcher icon (pure background utility).
2. Quick Settings Tile ("UPI QR") to toggle floating overlay.
3. Floating overlay window with WindowManager:
   - 100% maximum screen brightness override (`layoutParams.screenBrightness = 1.0f`).
   - Tapping card or background immediately dismisses.
   - Auto-close countdown timer after 60 seconds.
   - 2nd click on Quick Settings tile toggles it closed.
4. Direct bitmap decoding from local path: `/storage/emulated/0/IMG_20260831_143523.jpg`.
5. Silent/transparent permission helper activity for `SYSTEM_ALERT_WINDOW` and storage access.

## Quick Start
1. Push your QR code image to device:
   ```bash
   adb push <your_qr.jpg> /storage/emulated/0/IMG_20260831_143523.jpg
   ```
2. Install APK:
   ```bash
   adb install app-debug.apk
   ```
3. Grant permissions:
   ```bash
   adb shell appops set com.upiqr.quicktile SYSTEM_ALERT_WINDOW allow
   adb shell pm grant com.upiqr.quicktile android.permission.READ_MEDIA_IMAGES
   ```
4. Pull down notification panel twice -> Tap Edit (Pencil) -> Drag "UPI QR" to active tiles.
