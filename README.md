# UPI QR Quick Settings Overlay (Android — Fixed API 35 Ready)

Pure background Android utility app (no launcher icon) providing an instant Quick Settings Tile ("UPI QR") that mounts a 100%-brightness QR overlay card.

## Fixes Applied for Production APK Build
1. **Gradle Kotlin DSL**: Fixed unclosed blocks and Groovy syntax in `build.gradle.kts`, `settings.gradle.kts`, and `app/build.gradle.kts` (`compileSdk = 35`, `targetSdk = 35`, Java 17).
2. **AndroidManifest.xml**: Removed deprecated `package` attribute for AGP 8.7.3 compatibility and declared `FOREGROUND_SERVICE_SPECIAL_USE`.
3. **Layout XML**: Closed unclosed XML comment in `floating_qr_layout.xml` and enabled `focusableInTouchMode` for Back key routing.
4. **FloatingViewService.java**: Added `NotificationChannel` + `startForeground()` with `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` (Android 14+), package-scoped `ACTION_OVERLAY_DISMISSED` broadcast, and hardware/gesture Back key listener.
5. **MyQuickTileService.java**: Verified both Overlay and Storage permissions before launch, used `startActivityAndCollapse(PendingIntent)` on API 34+, switched to `stopService()` on toggle-off, and cleared static listener in `onStopListening()` to prevent memory leaks.
6. **PermissionRequestActivity.java**: Ordered storage checks strictly descending (`TIRAMISU` API 33+ -> `R` API 30–32 -> API 26–29) eliminating all unreachable dead code.

## Build & Install Commands
```bash
./gradlew assembleDebug
adb push your_qr.jpg /storage/emulated/0/IMG_20260831_143523.jpg
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
