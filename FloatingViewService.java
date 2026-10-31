package com.upiqr.quicktile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;

/**
 * FloatingViewService:
 * Manages the floating WindowManager overlay card, enforces 100% screen brightness
 * for effortless QR scanner detection, implements a 60-second auto-close countdown,
 * decodes the local QR image from "/storage/emulated/0/IMG_20260831_143523.jpg", and notifies
 * MyQuickTileService upon dismissal.
 */
public class FloatingViewService extends Service {

    private static final String TAG = "FloatingViewService";

    public static final String ACTION_SHOW = "com.upiqr.quicktile.ACTION_SHOW";
    public static final String ACTION_HIDE = "com.upiqr.quicktile.ACTION_HIDE";
    public static final String ACTION_OVERLAY_DISMISSED = "com.upiqr.quicktile.ACTION_OVERLAY_DISMISSED";

    // Target image path as requested
    private static final String TARGET_IMAGE_PATH = "/storage/emulated/0/IMG_20260831_143523.jpg";
    private static final int AUTO_CLOSE_MS = 60000; // 60 seconds

    private static boolean isShowing = false;
    private static OverlayStateListener stateListener;

    public interface OverlayStateListener {
        void onOverlayDismissed();
    }

    public static void setStateListener(OverlayStateListener listener) {
        stateListener = listener;
    }

    public static boolean isShowing() {
        return isShowing;
    }

    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams layoutParams;
    private CountDownTimer countDownTimer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            dismissOverlay();
            return START_NOT_STICKY;
        }

        // Show overlay
        showFloatingOverlay();
        return START_NOT_STICKY;
    }

    private void showFloatingOverlay() {
        if (isShowing && floatingView != null) {
            Log.d(TAG, "Overlay is already showing. Refreshing timer.");
            resetAutoCloseTimer();
            return;
        }

        try {
            // 1. Inflate custom card layout
            LayoutInflater inflater = (LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE);
            floatingView = inflater.inflate(R.layout.floating_qr_layout, null);

            // 2. Configure WindowManager LayoutParams
            int overlayType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                overlayType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                // Legacy fallback for older API levels
                overlayType = WindowManager.LayoutParams.TYPE_PHONE;
            }

            layoutParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    // FLAG_NOT_TOUCH_MODAL: Allows outside touches if needed
                    // FLAG_KEEP_SCREEN_ON: Keep screen on during payment scan
                    // FLAG_LAYOUT_IN_SCREEN: Cover full window area
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );

            layoutParams.gravity = Gravity.CENTER;

            // ==============================================================
            // CRITICAL REQUIREMENT: BOOST BRIGHTNESS TO 100%
            // WindowManager allows overriding screen brightness for this window.
            // 1.0f = Maximum 100% brightness.
            // ==============================================================
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL; // 100% boost

            // 3. Bind UI Views and load local QR Bitmap
            ImageView ivQr = floatingView.findViewById(R.id.iv_qr_code);
            View errorLayout = floatingView.findViewById(R.id.layout_error_state);
            TextView tvErrorDetails = floatingView.findViewById(R.id.tv_error_details);
            TextView tvCountdown = floatingView.findViewById(R.id.tv_countdown);
            ProgressBar progressCountdown = floatingView.findViewById(R.id.progress_countdown);
            View cardView = floatingView.findViewById(R.id.card_view_qr);
            View rootContainer = floatingView.findViewById(R.id.root_overlay_container);

            loadQrBitmap(ivQr, errorLayout, tvErrorDetails);

            // 4. Setup Click-to-Dismiss on both the Card and root backdrop
            View.OnClickListener dismissClickListener = v -> {
                Log.d(TAG, "User tapped to dismiss overlay.");
                dismissOverlay();
            };
            cardView.setOnClickListener(dismissClickListener);
            rootContainer.setOnClickListener(dismissClickListener);

            // 5. Attach overlay to WindowManager
            windowManager.addView(floatingView, layoutParams);
            isShowing = true;

            // 6. Start 60s Auto-Close Countdown Timer
            startCountdownTimer(tvCountdown, progressCountdown);

            Log.d(TAG, "Floating QR Overlay displayed successfully with 100% brightness.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to display floating overlay: " + e.getMessage(), e);
            Toast.makeText(this, "Failed to display overlay: " + e.getMessage(), Toast.LENGTH_LONG).show();
            dismissOverlay();
        }
    }

    /**
     * Loads the image directly from the requested path.
     * Safely handles missing files, permissions issues, or OOM.
     */
    private void loadQrBitmap(ImageView ivQr, View errorLayout, TextView tvErrorDetails) {
        new Thread(() -> {
            try {
                File file = new File(TARGET_IMAGE_PATH);
                if (file.exists() && file.canRead()) {
                    // Efficient decode: check dimensions first if very large
                    BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
                    boundsOptions.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(TARGET_IMAGE_PATH, boundsOptions);

                    int sampleSize = 1;
                    int maxDimension = 1024;
                    if (boundsOptions.outWidth > maxDimension || boundsOptions.outHeight > maxDimension) {
                        sampleSize = Math.max(boundsOptions.outWidth / maxDimension, boundsOptions.outHeight / maxDimension);
                    }

                    BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
                    decodeOptions.inSampleSize = sampleSize;
                    decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;

                    final Bitmap qrBitmap = BitmapFactory.decodeFile(TARGET_IMAGE_PATH, decodeOptions);

                    mainHandler.post(() -> {
                        if (qrBitmap != null && ivQr != null) {
                            ivQr.setImageBitmap(qrBitmap);
                            errorLayout.setVisibility(View.GONE);
                            ivQr.setVisibility(View.VISIBLE);
                        } else {
                            showErrorState(ivQr, errorLayout, tvErrorDetails, "Corrupt or unreadable image file.");
                        }
                    });
                } else {
                    mainHandler.post(() -> showErrorState(
                            ivQr,
                            errorLayout,
                            tvErrorDetails,
                            "File not found at:\n" + TARGET_IMAGE_PATH + "\n\nEnsure storage permissions are granted."
                    ));
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error decoding QR bitmap", t);
                mainHandler.post(() -> showErrorState(ivQr, errorLayout, tvErrorDetails, "Error: " + t.getMessage()));
            }
        }).start();
    }

    private void showErrorState(ImageView ivQr, View errorLayout, TextView tvErrorDetails, String message) {
        if (ivQr != null) ivQr.setVisibility(View.GONE);
        if (errorLayout != null) {
            errorLayout.setVisibility(View.VISIBLE);
            if (tvErrorDetails != null) tvErrorDetails.setText(message);
        }
    }

    /**
     * Starts the 60-second auto-close timer with real-time UI updates.
     */
    private void startCountdownTimer(TextView tvCountdown, ProgressBar progressBar) {
        cancelTimer();

        countDownTimer = new CountDownTimer(AUTO_CLOSE_MS, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) (millisUntilFinished / 1000);
                if (tvCountdown != null) {
                    tvCountdown.setText("Auto closes in " + secondsRemaining + "s");
                }
                if (progressBar != null) {
                    progressBar.setProgress(secondsRemaining);
                }
            }

            @Override
            public void onFinish() {
                Log.d(TAG, "60s auto-close timer expired. Dismissing overlay.");
                dismissOverlay();
            }
        }.start();
    }

    private void resetAutoCloseTimer() {
        if (floatingView != null) {
            TextView tvCountdown = floatingView.findViewById(R.id.tv_countdown);
            ProgressBar progressCountdown = floatingView.findViewById(R.id.progress_countdown);
            startCountdownTimer(tvCountdown, progressCountdown);
        }
    }

    private void cancelTimer() {
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
    }

    /**
     * Dismisses the overlay, restores default screen brightness,
     * detaches the view from WindowManager, and notifies MyQuickTileService.
     */
    public void dismissOverlay() {
        cancelTimer();

        if (isShowing && floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay view: " + e.getMessage());
            } finally {
                floatingView = null;
                isShowing = false;
            }
        } else {
            isShowing = false;
        }

        // Notify Quick Settings Tile to update state to INACTIVE
        if (stateListener != null) {
            stateListener.onOverlayDismissed();
        }

        sendBroadcast(new Intent(ACTION_OVERLAY_DISMISSED));

        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        dismissOverlay();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}