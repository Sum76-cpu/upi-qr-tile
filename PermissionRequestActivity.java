package com.upiqr.quicktile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
 * A completely transparent, silent permission helper activity.
 *
 * Why this is needed:
 * 1. This app has NO Launcher Icon or Main Activity.
 * 2. When the user taps the Quick Settings tile for the first time,
 *    Android requires explicit user consent for SYSTEM_ALERT_WINDOW
 *    and Storage permissions.
 * 3. This activity is styled with Theme.Transparent so no UI flicker occurs.
 *    It opens the standard system settings screen, verifies grant, and finishes immediately.
 */
public class PermissionRequestActivity extends Activity {

    private static final String TAG = "PermissionHelper";
    private static final int REQ_OVERLAY_PERMISSION = 1001;
    private static final int REQ_STORAGE_PERMISSION = 1002;
    private static final int REQ_MANAGE_STORAGE_PERMISSION = 1003;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Fully transparent activity - no setContentView needed

        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        // Step 1: Check SYSTEM_ALERT_WINDOW (Overlay permission)
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionExplanationDialog();
            return;
        }

        // Step 2: Check Storage permissions to read /storage/emulated/0/IMG_20260831_143523.jpg
        if (!hasStoragePermission()) {
            requestStoragePermissions();
            return;
        }

        // If everything is already granted, finish immediately
        onAllPermissionsGranted();
    }

    private void showOverlayPermissionExplanationDialog() {
        new AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("To display your UPI QR code directly on screen from Quick Settings, please grant 'Display over other apps'.")
                .setPositiveButton("Enable in Settings", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())
                        );
                        startActivityForResult(intent, REQ_OVERLAY_PERMISSION);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to launch overlay settings", e);
                        // Generic fallback if package uri fails on custom OEM roms
                        Intent fallbackIntent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                        startActivityForResult(fallbackIntent, REQ_OVERLAY_PERMISSION);
                    }
                })
                .setNegativeButton("Cancel", (dialog, which) -> finish())
                .setCancelable(false)
                .show();
    }

    private boolean hasStoragePermission() {
        // On Android 11+ (API 30+), direct file reading from /storage/emulated/0/
        // requires MANAGE_EXTERNAL_STORAGE or MediaStore API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }

        // Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED;
        }

        // Android 12 and below
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            new AlertDialog.Builder(this)
                    .setTitle("Storage Access Required")
                    .setMessage("To load your QR image from:\n/storage/emulated/0/IMG_20260831_143523.jpg\n\nPlease allow access to manage all files.")
                    .setPositiveButton("Grant Access", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())
                            );
                            startActivityForResult(intent, REQ_MANAGE_STORAGE_PERMISSION);
                        } catch (Exception e) {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            startActivityForResult(intent, REQ_MANAGE_STORAGE_PERMISSION);
                        }
                    })
                    .setNegativeButton("Skip", (dialog, which) -> onAllPermissionsGranted())
                    .setCancelable(false)
                    .show();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    REQ_STORAGE_PERMISSION
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_STORAGE_PERMISSION
            );
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OVERLAY_PERMISSION) {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay permission granted!", Toast.LENGTH_SHORT).show();
                // Check next permission
                if (!hasStoragePermission()) {
                    requestStoragePermissions();
                } else {
                    onAllPermissionsGranted();
                }
            } else {
                Toast.makeText(this, "Overlay permission was not granted.", Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (requestCode == REQ_MANAGE_STORAGE_PERMISSION) {
            onAllPermissionsGranted();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE_PERMISSION) {
            onAllPermissionsGranted();
        }
    }

    private void onAllPermissionsGranted() {
        Toast.makeText(this, "Permissions configured! You can now tap the UPI QR tile in Quick Settings.", Toast.LENGTH_LONG).show();
        finish();
    }
}