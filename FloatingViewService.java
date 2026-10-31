package com.upiqr.quicktile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.lang.ref.WeakReference;

/**
 * FloatingViewService:
 * Manages the floating WindowManager overlay card, enforces 100% screen brightness
 * for effortless QR scanner detection, implements a 60-second auto-close countdown,
 * intercepts the Back key to dismiss, decodes "/storage/emulated/0/IMG_20260831_143523.jpg",
 * and safely notifies MyQuickTileService upon dismissal.
 */
public class FloatingViewService extends Service {

    private static final String TAG = "FloatingViewService";

    private static final String CHANNEL_ID = "upi_qr_overlay_channel";
    private static final String CHANNEL_NAME = "UPI QR Overlay Service";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_SHOW = "com.upiqr.quicktile.ACTION_SHOW";
    public static final String ACTION_HIDE = "com.upiqr.quicktile.ACTION_HIDE";
    public static final String ACTION_OVERLAY_DISMISSED = "com.upiqr.quicktile.ACTION_OVERLAY_DISMISSED";

    private static final String TARGET_IMAGE_PATH = "/storage/emulated/0/IMG_20260831_143523.jpg";
    private static final long AUTO_CLOSE_MS = 60_000L;

    private static volatile boolean isShowing = false;
    private static WeakReference<OverlayStateListener> stateListenerRef;

    public interface OverlayStateListener {
        void onOverlayDismissed();
    }

    public static void setStateListener(@Nullable OverlayStateListener listener) {
        stateListenerRef = (listener != null) ? new WeakReference<>(listener) : null;
    }

    public static boolean isShowing() {
        return isShowing;
    }

    private WindowManager windowManager;
    private View floatingView;
    private CountDownTimer countDownTimer;
    private boolean isDismissing = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent != null && ACTION_HIDE.equals(intent.getAction())) {
            dismissOverlay();
            return START_NOT_STICKY;
        }

        // Always promote immediately to honor the Android 8.0+ / 14+ ForegroundService contract
        promoteToForeground();

        if (!Settings.canDrawOverlays(this)) {
            Log.e(TAG, "SYSTEM_ALERT_WINDOW permission missing. Stopping service.");
            dismissOverlay();
            return START_NOT_STICKY;
        }

        showFloatingOverlay();
        return START_NOT_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Keeps the UPI QR payment card visible at 100% brightness");
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void promoteToForeground() {
        Intent hideIntent = new Intent(this, FloatingViewService.class);
        hideIntent.setAction(ACTION_HIDE);

        PendingIntent hidePendingIntent = PendingIntent.getService(
                this,
                0,
                hideIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_upi_qr)
                .setContentTitle("UPI QR Active")
                .setContentText("Showing QR at 100% brightness. Tap to dismiss.")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(hidePendingIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void showFloatingOverlay() {
        isDismissing = false;

        if (isShowing && floatingView != null) {
            Log.d(TAG, "Overlay is already showing. Refreshing 60s countdown.");
            resetAutoCloseTimer();
            return;
        }

        try {
            // 1. Wrap inflated view in a focusable container to reliably intercept KEYCODE_BACK
            FrameLayout keyInterceptorRoot = new FrameLayout(this) {
                @Override
                public boolean dispatchKeyEvent(KeyEvent event) {
                    int keyCode = event.getKeyCode();
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                            Log.d(TAG, "Back button pressed. Dismissing UPI QR overlay.");
                            dismissOverlay();
                        }
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
                }
            };
            keyInterceptorRoot.setFocusable(true);
            keyInterceptorRoot.setFocusableInTouchMode(true);

            LayoutInflater inflater = LayoutInflater.from(this);
            inflater.inflate(R.layout.floating_qr_layout, keyInterceptorRoot, true);
            floatingView = keyInterceptorRoot;

            // 2. Configure WindowManager LayoutParams (focusable so Back key events arrive)
            int overlayType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
            );
            layoutParams.gravity = Gravity.CENTER;

            // Boost screen brightness to 100% (1.0f) while overlay window is attached
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;

            // 3. Bind UI Views and load local QR Bitmap
            ImageView ivQr = floatingView.findViewById(R.id.iv_qr_code);
            View errorLayout = floatingView.findViewById(R.id.layout_error_state);
            TextView tvErrorDetails = floatingView.findViewById(R.id.tv_error_details);
            TextView tvCountdown = floatingView.findViewById(R.id.tv_countdown);
            ProgressBar progressCountdown = floatingView.findViewById(R.id.progress_countdown);
            View cardView = floatingView.findViewById(R.id.card_view_qr);
            View rootContainer = floatingView.findViewById(R.id.root_overlay_container);

            loadQrBitmap(ivQr, errorLayout, tvErrorDetails);

            // 4. Setup Click-to-Dismiss and Back Key-to-Dismiss listeners
            View.OnClickListener dismissClickListener = v -> {
                Log.d(TAG, "User tapped to dismiss overlay.");
                dismissOverlay();
            };
            cardView.setOnClickListener(dismissClickListener);
            rootContainer.setOnClickListener(dismissClickListener);

            View.OnKeyListener backKeyListener = (v, keyCode, event) -> {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    Log.d(TAG, "OnKeyListener caught Back key. Dismissing overlay.");
                    dismissOverlay();
                    return true;
                }
                return false;
            };
            floatingView.setOnKeyListener(backKeyListener);
            rootContainer.setOnKeyListener(backKeyListener);

            // 5. Attach overlay to WindowManager and request focus for key events
            windowManager.addView(floatingView, layoutParams);
            floatingView.requestFocus();
            isShowing = true;

            // 6. Start 60s Auto-Close Countdown Timer
            startCountdownTimer(tvCountdown, progressCountdown);
            Log.d(TAG, "Floating QR Overlay displayed at 100% brightness.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to display floating overlay", e);
            Toast.makeText(this, "Failed to display overlay: " + e.getMessage(), Toast.LENGTH_LONG).show();
            dismissOverlay();
        }
    }

    private void loadQrBitmap(ImageView ivQr, View errorLayout, TextView tvErrorDetails) {
        new Thread(() -> {
            try {
                File file = new File(TARGET_IMAGE_PATH);
                if (file.exists() && file.canRead()) {
                    BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
                    boundsOptions.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(TARGET_IMAGE_PATH, boundsOptions);

                    int sampleSize = 1;
                    int maxDimension = 1024;
                    while ((boundsOptions.outWidth / sampleSize) > maxDimension
                            || (boundsOptions.outHeight / sampleSize) > maxDimension) {
                        sampleSize *= 2;
                    }

                    BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
                    decodeOptions.inSampleSize = sampleSize;
                    decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;

                    final Bitmap qrBitmap = BitmapFactory.decodeFile(TARGET_IMAGE_PATH, decodeOptions);

                    mainHandler.post(() -> {
                        if (!isShowing || floatingView == null) {
                            return;
                        }
                        if (qrBitmap != null && ivQr != null) {
                            ivQr.setImageBitmap(qrBitmap);
                            errorLayout.setVisibility(View.GONE);
                            ivQr.setVisibility(View.VISIBLE);
                        } else {
                            showErrorState(ivQr, errorLayout, tvErrorDetails, "Corrupt or unreadable image file.");
                        }
                    });
                } else {
                    mainHandler.post(() -> {
                        if (!isShowing || floatingView == null) {
                            return;
                        }
                        showErrorState(
                                ivQr,
                                errorLayout,
                                tvErrorDetails,
                                "File not found or unreadable at:\n" + TARGET_IMAGE_PATH
                                        + "\n\nVerify file path and storage permissions."
                        );
                    });
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error decoding QR bitmap", t);
                mainHandler.post(() -> {
                    if (isShowing && floatingView != null) {
                        showErrorState(ivQr, errorLayout, tvErrorDetails, "Error: " + t.getMessage());
                    }
                });
            }
        }).start();
    }

    private void showErrorState(ImageView ivQr, View errorLayout, TextView tvErrorDetails, String message) {
        if (ivQr != null) {
            ivQr.setVisibility(View.GONE);
        }
        if (errorLayout != null) {
            errorLayout.setVisibility(View.VISIBLE);
            if (tvErrorDetails != null) {
                tvErrorDetails.setText(message);
            }
        }
    }

    private void startCountdownTimer(TextView tvCountdown, ProgressBar progressBar) {
        cancelTimer();
        countDownTimer = new CountDownTimer(AUTO_CLOSE_MS, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsRemaining = (int) Math.ceil(millisUntilFinished / 1000.0);
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

    public void dismissOverlay() {
        if (isDismissing) {
            return;
        }
        isDismissing = true;

        cancelTimer();
        mainHandler.removeCallbacksAndMessages(null);

        if (floatingView != null && windowManager != null) {
            try {
                if (floatingView.isAttachedToWindow()) {
                    windowManager.removeView(floatingView);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error removing overlay view", e);
            } finally {
                floatingView = null;
            }
        }
        isShowing = false;

        // Notify Quick Settings Tile via WeakReference listener
        OverlayStateListener listener = (stateListenerRef != null) ? stateListenerRef.get() : null;
        if (listener != null) {
            listener.onOverlayDismissed();
        }

        // Explicit package-scoped broadcast required for Android 14+ RECEIVER_NOT_EXPORTED
        Intent dismissedIntent = new Intent(ACTION_OVERLAY_DISMISSED);
        dismissedIntent.setPackage(getPackageName());
        sendBroadcast(dismissedIntent);

        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        dismissOverlay();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
