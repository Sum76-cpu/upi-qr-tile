package com.upiqr.quicktile;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

/**
 * MyQuickTileService:
 * Quick Settings Tile labeled "UPI QR".
 * - 1st Click: Verifies both Overlay and Storage permissions -> launches overlay at 100% brightness -> ACTIVE.
 * - 2nd Click: Stops FloatingViewService immediately -> INACTIVE.
 * - Automatically resets to INACTIVE on 60s expiry, card tap, or Back key press.
 */
public class MyQuickTileService extends TileService {

    private static final String TAG = "MyQuickTileService";
    private static final int REQ_PERMISSION_PENDING_INTENT = 2001;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isReceiverRegistered = false;

    private final FloatingViewService.OverlayStateListener overlayStateListener =
            () -> mainHandler.post(() -> updateTileState(false));

    private final BroadcastReceiver dismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && FloatingViewService.ACTION_OVERLAY_DISMISSED.equals(intent.getAction())) {
                Log.d(TAG, "Received ACTION_OVERLAY_DISMISSED broadcast. Setting tile INACTIVE.");
                updateTileState(false);
            }
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        Log.d(TAG, "onStartListening");

        FloatingViewService.setStateListener(overlayStateListener);

        if (!isReceiverRegistered) {
            IntentFilter filter = new IntentFilter(FloatingViewService.ACTION_OVERLAY_DISMISSED);
            ContextCompat.registerReceiver(
                    this,
                    dismissReceiver,
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
            isReceiverRegistered = true;
        }

        updateTileState(FloatingViewService.isShowing());
    }

    @Override
    public void onStopListening() {
        Log.d(TAG, "onStopListening");
        // Clear static listener reference to prevent leaking TileService context
        FloatingViewService.setStateListener(null);
        mainHandler.removeCallbacksAndMessages(null);
        unregisterDismissReceiverSafely();
        super.onStopListening();
    }

    @Override
    public void onDestroy() {
        FloatingViewService.setStateListener(null);
        mainHandler.removeCallbacksAndMessages(null);
        unregisterDismissReceiverSafely();
        super.onDestroy();
    }

    private void unregisterDismissReceiverSafely() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(dismissReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered
            } finally {
                isReceiverRegistered = false;
            }
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Log.d(TAG, "Tile clicked.");

        // 1. If overlay is already visible, dismiss immediately on 2nd click
        if (FloatingViewService.isShowing()) {
            Log.d(TAG, "Overlay is currently active. Stopping FloatingViewService.");
            Intent stopIntent = new Intent(this, FloatingViewService.class);
            stopService(stopIntent);
            updateTileState(false);
            return;
        }

        // 2. Check BOTH Overlay (SYSTEM_ALERT_WINDOW) and Storage permissions before launching
        boolean hasOverlayPermission = Settings.canDrawOverlays(this);
        boolean hasStoragePermission = PermissionRequestActivity.hasStoragePermission(this);

        if (!hasOverlayPermission || !hasStoragePermission) {
            Log.w(TAG, "Permissions missing (overlay=" + hasOverlayPermission
                    + ", storage=" + hasStoragePermission + "). Launching PermissionRequestActivity.");
            promptForPermissions(hasOverlayPermission);
            return;
        }

        // 3. Start Foreground Overlay Service
        Log.d(TAG, "Permissions verified. Starting FloatingViewService.");
        Intent showIntent = new Intent(this, FloatingViewService.class);
        showIntent.setAction(FloatingViewService.ACTION_SHOW);

        try {
            ContextCompat.startForegroundService(this, showIntent);
            updateTileState(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start FloatingViewService", e);
            Toast.makeText(this, "Unable to launch QR overlay: " + e.getMessage(), Toast.LENGTH_LONG).show();
            updateTileState(false);
        }
    }

    private void updateTileState(boolean active) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        tile.setLabel(getString(R.string.tile_label));
        tile.setContentDescription("UPI QR Quick Payment Overlay");
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_upi_qr));
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @SuppressWarnings("deprecation")
    private void promptForPermissions(boolean hasOverlayPermission) {
        String message = !hasOverlayPermission
                ? "Please grant Overlay permission to display UPI QR"
                : "Please grant Storage permission to load your QR image";
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(this, PermissionRequestActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    REQ_PERMISSION_PENDING_INTENT,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            startActivityAndCollapse(pendingIntent);
        } else {
            try {
                startActivityAndCollapse(intent);
            } catch (Exception e) {
                Log.e(TAG, "startActivityAndCollapse failed, falling back to startActivity", e);
                startActivity(intent);
            }
        }
    }
}
