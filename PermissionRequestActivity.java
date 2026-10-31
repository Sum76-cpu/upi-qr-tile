package com.upiqr.quicktile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * PermissionRequestActivity:
 * Transparent, launcher-less helper activity invoked from MyQuickTileService
 * whenever SYSTEM_ALERT_WINDOW or version-appropriate Storage access is missing.
 */
public class PermissionRequestActivity extends Activity {

    private static final String TAG = "PermissionHelper";
    private static final int REQ_OVERLAY_PERMISSION = 1001;
    private static final int REQ_STORAGE_PERMISSION = 1002;
    private static final int REQ_MANAGE_STORAGE_PERMISSION = 1003;

    @Nullable
    private AlertDialog activeDialog;

    /**
     * Checks storage permission in descending API order with zero dead code:
     * - Android 13+ (API 33+, TIRAMISU): READ_MEDIA_IMAGES (or MANAGE_EXTERNAL_STORAGE if granted)
     * - Android 11-12L (API 30-32, R..S_V2): Environment.isExternalStorageManager()
     * - Android 8.0-10 (API 26-29): READ_EXTERNAL_STORAGE
     */
    public static boolean hasStoragePermission(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED
                    || Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        // Step 1: Check SYSTEM_ALERT_WINDOW (Display over other apps)
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionExplanationDialog();
            return;
        }

        // Step 2: Check version-appropriate Storage permission
        if (!hasStoragePermission(this)) {
            requestStoragePermissions();
            return;
        }

        // Both permissions are active
        onAllPermissionsGranted();
    }

    private void showOverlayPermissionExplanationDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        dismissActiveDialog();

        activeDialog = new AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("To display your UPI QR code directly on screen from Quick Settings, please enable 'Display over other apps'.")
                .setPositiveButton("Enable in Settings", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                        );
                        startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
                    } catch (Exception e) {
                        Log.e(TAG, "Package-specific overlay settings failed; using generic fallback", e);
                        Intent fallbackIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                        startActivityForResult(fallbackIntent, REQ_OVERLAY_PERMISSION);
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+): Request granular READ_MEDIA_IMAGES runtime permission
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    REQ_STORAGE_PERMISSION
            );
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11 - 12L (API 30 - 32): Request All-Files Access for /storage/emulated/0/
            if (isFinishing() || isDestroyed()) {
                return;
            }
            dismissActiveDialog();

            activeDialog = new AlertDialog.Builder(this)
                    .setTitle("Storage Access Required")
                    .setMessage("To load your QR image from:\n/storage/emulated/0/IMG_20260831_143523.jpg\n\nPlease grant All Files Access.")
                    .setPositiveButton("Grant Access", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())
                            );
                            startActivityForResult(intent, REQ_MANAGE_STORAGE_PERMISSION);
                        } catch (Exception e) {
                            Log.e(TAG, "App-specific all-files settings failed; using fallback", e);
                            Intent fallbackIntent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(fallbackIntent, REQ_MANAGE_STORAGE_PERMISSION);
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
        } else {
            // Android 8.0 - 10 (API 26 - 29): Request legacy READ_EXTERNAL_STORAGE
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_STORAGE_PERMISSION
            );
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show();
                if (!hasStoragePermission(this)) {
                    requestStoragePermissions();
                } else {
                    onAllPermissionsGranted();
                }
            } else {
                Toast.makeText(this, "Overlay permission is required to show UPI QR.", Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (requestCode == REQ_MANAGE_STORAGE_PERMISSION) {
            if (hasStoragePermission(this)) {
                onAllPermissionsGranted();
            } else {
                Toast.makeText(this, "Storage access was not granted.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_STORAGE_PERMISSION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted || hasStoragePermission(this)) {
                onAllPermissionsGranted();
            } else {
                Toast.makeText(this, "Image permission is required to read your QR code.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void onAllPermissionsGranted() {
        Toast.makeText(
                this,
                "Permissions ready! Tap the UPI QR tile in Quick Settings.",
                Toast.LENGTH_LONG
        ).show();
        finish();
    }

    private void dismissActiveDialog() {
        if (activeDialog != null && activeDialog.isShowing()) {
            activeDialog.dismiss();
        }
        activeDialog = null;
    }

    @Override
    protected void onDestroy() {
        dismissActiveDialog();
        super.onDestroy();
    }
}
