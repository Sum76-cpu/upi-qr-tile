package com.upiqr.quicktile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.widget.Toast;

/**
 * MyQuickTileService:
 * Quick Settings Tile labeled "UPI QR".
 * Clicking this tile toggles the floating overlay window.
 *
 * Click Behavior:
 * - 1st Click: Checks permissions -> Launches overlay at 100% brightness -> Tile becomes ACTIVE.
 * - 2nd Click: If overlay is already visible -> Dismisses overlay immediately -> Tile becomes INACTIVE.
 * - Auto-syncs tile state when overlay auto-closes after 60s or on user tap.
 */
public class MyQuickTileService extends TileService {

    private static final String TAG = "MyQuickTileService";

    private final BroadcastReceiver dismissReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "Received dismiss broadcast. Updating tile to INACTIVE.");
            updateTileState(false);
        }
    };

    @Override
    public void onStartListening() {
        super.onStartListening();
        Log.d(TAG, "onStartListening");

        // Register listener for overlay dismissal
        FloatingViewService.setStateListener(() -> {
            Log.d(TAG, "Callback received: Overlay dismissed.");
            updateTileState(false);
        });

        // Register broadcast receiver for redundancy
        IntentFilter filter = new IntentFilter(FloatingViewService.ACTION_OVERLAY_DISMISSED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dismissReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(dismissReceiver, filter);
        }

        // Sync tile state with current overlay visibility
        updateTileState(FloatingViewService.isShowing());
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        Log.d(TAG, "onStopListening");

        try {
            unregisterReceiver(dismissReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onClick() {
        super.onClick();
        Log.d(TAG, "Tile clicked.");

        // ==============================================================
        // 1. Check Overlay Permission (SYSTEM_ALERT_WINDOW)
        // ==============================================================
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission missing! Launching PermissionRequestActivity.");
            promptForPermissions();
            return;
        }

        // ==============================================================
        // 2. Toggle Floating Overlay
        // ==============================================================
        if (FloatingViewService.isShowing()) {
            // 2nd Click: Hide/dismiss immediately
            Log.d(TAG, "Overlay is showing. Hiding overlay.");
            Intent hideIntent = new Intent(this, FloatingViewService.class);
            hideIntent.setAction(FloatingViewService.ACTION_HIDE);
            startService(hideIntent);

            updateTileState(false);
        } else {
            // 1st Click: Show floating overlay at 100% brightness
            Log.d(TAG, "Overlay is NOT showing. Showing overlay.");
            Intent showIntent = new Intent(this, FloatingViewService.class);
            showIntent.setAction(FloatingViewService.ACTION_SHOW);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(showIntent);
            } else {
                startService(showIntent);
            }

            updateTileState(true);
        }
    }

    /**
     * Updates the Quick Settings Tile label, icon, and visual state (ACTIVE vs INACTIVE).
     */
    private void updateTileState(boolean active) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        tile.setLabel(getString(R.string.tile_label));
        tile.setContentDescription("UPI QR Quick Payment QR");

        if (active) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_upi_qr));
        } else {
            tile.setState(Tile.STATE_INACTIVE);
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_upi_qr));
        }

        tile.updateTile();
    }

    /**
     * Launches the transparent PermissionRequestActivity helper to guide the user
     * to grant the required Display Over Other Apps and Storage permissions.
     */
    private void promptForPermissions() {
        Toast.makeText(this, "Please grant Overlay permission to display UPI QR", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(this, PermissionRequestActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // On Android 14+ (UpsideDownCake), TileService requires startActivityAndCollapse
        // or starting activity via pending intent depending on unlock state.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                // Collapses notification shade and opens activity seamlessly
                startActivityAndCollapse(intent);
                return;
            } catch (Exception e) {
                Log.e(TAG, "startActivityAndCollapse failed, using startActivity", e);
            }
        }

        startActivity(intent);
    }
}